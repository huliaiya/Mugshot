package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
)

const (
	// WS protocol-level keepalive: server pings, client pongs.
	pingPeriod   = 30 * time.Second
	pongWait     = 90 * time.Second
	writeWait    = 10 * time.Second
	maxMsgSize   = 256 * 1024
	sendChanSize = 64
)

var errConnClosed = errors.New("connection closed")

// ---- envelopes ----

type deviceMsg struct {
	Type       string         `json:"type"`
	DeviceID   string         `json:"device_id,omitempty"`
	DeviceName string         `json:"device_name,omitempty"`
	Model      string         `json:"model,omitempty"`
	AppVersion string         `json:"app_version,omitempty"`
	RequestID  string         `json:"request_id,omitempty"`
	Reason     string         `json:"reason,omitempty"`
	Timestamp  int64          `json:"timestamp,omitempty"`
	Payload    *ReportPayload `json:"payload,omitempty"`
}

// ---- connection plumbing ----

type wsConn struct {
	ws        *websocket.Conn
	send      chan []byte
	closeOnce sync.Once
	dead      atomic.Bool
}

func (c *wsConn) trySend(b []byte) bool {
	if c.dead.Load() {
		return false
	}
	select {
	case c.send <- b:
		return true
	default:
		// slow consumer: drop the connection rather than blocking the hub
		c.Close()
		return false
	}
}

func (c *wsConn) Close() {
	c.closeOnce.Do(func() {
		c.dead.Store(true)
		close(c.send)
	})
}

// writePump drains the send channel and sends protocol pings.
func (c *wsConn) writePump(onClose func()) {
	ticker := time.NewTicker(pingPeriod)
	defer func() {
		ticker.Stop()
		_ = c.ws.Close()
		if onClose != nil {
			onClose()
		}
	}()
	for {
		select {
		case msg, ok := <-c.send:
			if !ok {
				_ = c.ws.WriteMessage(websocket.CloseMessage, websocket.FormatCloseMessage(websocket.CloseGoingAway, ""))
				return
			}
			_ = c.ws.SetWriteDeadline(time.Now().Add(writeWait))
			if err := c.ws.WriteMessage(websocket.TextMessage, msg); err != nil {
				return
			}
		case <-ticker.C:
			_ = c.ws.SetWriteDeadline(time.Now().Add(writeWriteDeadline))
			if err := c.ws.WriteMessage(websocket.PingMessage, nil); err != nil {
				return
			}
		}
	}
}

const writeWriteDeadline = writeWait

type DeviceConn struct {
	wsConn
	hub        *Hub
	DeviceID   string
	DeviceName string
}

type PluginConn struct {
	wsConn
	hub *Hub
}

type pendingRequest struct {
	deviceID string
	ch       chan *Report
	created  time.Time
}

// ---- hub ----

// Hub tracks live device connections, plugin subscribers and in-flight
// request_report commands. Lock order: never acquire the store lock while
// holding the hub lock for store writes (store methods are called after
// releasing or without hub lock held); store never calls back into hub.
type Hub struct {
	cfg   *Config
	store *Store

	mu          sync.RWMutex
	deviceConns map[string]*DeviceConn // registered device connections
	pluginConns map[*PluginConn]struct{}
	pending     map[string]*pendingRequest
	freshLast   map[string]int64 // device_id -> last fresh request (ms)
	reportHits  map[string][]int64

	closed atomic.Bool
}

func NewHub(cfg *Config, store *Store) *Hub {
	h := &Hub{
		cfg:         cfg,
		store:       store,
		deviceConns: map[string]*DeviceConn{},
		pluginConns: map[*PluginConn]struct{}{},
		pending:     map[string]*pendingRequest{},
		freshLast:   map[string]int64{},
		reportHits:  map[string][]int64{},
	}
	return h
}

// ---- routing ----

func (h *Hub) RegisterRoutes(mux *http.ServeMux) {
	mux.HandleFunc("/ws/device", h.handleDeviceWS)
	mux.HandleFunc("/ws/plugin", h.handlePluginWS)
}

func (h *Hub) handleDeviceWS(w http.ResponseWriter, r *http.Request) {
	if h.closed.Load() {
		http.Error(w, "server shutting down", http.StatusServiceUnavailable)
		return
	}
	if !tokenEqual(bearerToken(r), h.cfg.DeviceToken) {
		log.Printf("[ws] 设备连接鉴权失败 ip=%s", remoteIP(r))
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	up := websocket.Upgrader{ReadBufferSize: 4096, WriteBufferSize: 4096}
	ws, err := up.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	conn := &DeviceConn{wsConn: wsConn{ws: ws, send: make(chan []byte, sendChanSize)}, hub: h}

	// The first message must be a valid register within 10s.
	_ = ws.SetReadDeadline(time.Now().Add(10 * time.Second))
	var msg deviceMsg
	if err := ws.ReadJSON(&msg); err != nil || msg.Type != "register" {
		log.Printf("[ws] 设备首条消息不是 register，关闭连接 ip=%s err=%v", remoteIP(r), err)
		_ = ws.Close()
		return
	}
	deviceID, deviceName, model := normalizeDevice(msg.DeviceID, msg.DeviceName, msg.Model)
	if deviceID == "" {
		log.Printf("[ws] 设备 register 缺少 device_id，关闭连接 ip=%s", remoteIP(r))
		_ = ws.Close()
		return
	}
	conn.DeviceID = deviceID
	conn.DeviceName = deviceName

	h.registerDevice(conn, model)
	// acknowledge registration so the device can start reporting
	h.reply(conn, &deviceMsg{Type: "register_ok", Timestamp: nowMs()})

	go conn.writePump(func() { h.unregisterDevice(conn) })

	// reset deadline for the long-lived loop
	_ = ws.SetReadDeadline(time.Now().Add(pongWait))
	ws.SetPongHandler(func(string) error { return ws.SetReadDeadline(time.Now().Add(pongWait)) })
	h.deviceReadLoop(conn)
}

func (h *Hub) deviceReadLoop(conn *DeviceConn) {
	ws := conn.ws
	defer conn.Close()
	for {
		_ = ws.SetReadDeadline(time.Now().Add(pongWait))
		var msg deviceMsg
		if err := ws.ReadJSON(&msg); err != nil {
			if !h.closed.Load() && !websocket.IsCloseError(err, websocket.CloseNormalClosure, websocket.CloseGoingAway) {
				log.Printf("[ws] 设备连接断开 device=%s err=%v", maskID(conn.DeviceID), err)
			}
			return
		}
		h.handleDeviceMessage(conn, &msg)
	}
}

func (h *Hub) handleDeviceMessage(conn *DeviceConn, msg *deviceMsg) {
	switch msg.Type {
	case "register":
		// re-register (e.g. after app reconnect with new name)
		deviceID, deviceName, model := normalizeDevice(msg.DeviceID, msg.DeviceName, msg.Model)
		if deviceID == conn.DeviceID {
			conn.DeviceName = deviceName
			h.store.UpsertFromRegister(deviceID, deviceName, model)
			h.reply(conn, &deviceMsg{Type: "register_ok", Timestamp: nowMs()})
			d := h.store.GetDeviceByID(deviceID)
			if d != nil {
				h.broadcast("device_online", map[string]any{"device": d}, nil)
			}
		}
	case "heartbeat":
		h.store.TouchHeartbeat(conn.DeviceID)
	case "report":
		h.handleReport(conn, msg)
	default:
		log.Printf("[ws] 设备 %s 发送未知消息类型 %q，忽略", maskID(conn.DeviceID), msg.Type)
	}
}

func (h *Hub) handleReport(conn *DeviceConn, msg *deviceMsg) {
	if msg.Payload == nil {
		return
	}
	// identity check: a connection may only report for its own device_id
	if msg.DeviceID != "" && msg.DeviceID != conn.DeviceID {
		log.Printf("[ws] 拒绝设备身份不匹配的上报 conn=%s report.device=%s", maskID(conn.DeviceID), maskID(msg.DeviceID))
		return
	}
	if !h.allowReport(conn.DeviceID) {
		log.Printf("[ws] 设备 %s 上报频率超限（>%d/min），本次丢弃", maskID(conn.DeviceID), h.cfg.ReportRatePerMin)
		return
	}

	rep := &Report{Timestamp: msg.Timestamp, RequestID: msg.RequestID, Payload: *msg.Payload}
	if rep.Timestamp <= 0 || rep.Timestamp > nowMs()+60_000 {
		rep.Timestamp = nowMs()
	}
	if rep.Payload.BatteryLevel < -1 || rep.Payload.BatteryLevel > 100 {
		rep.Payload.BatteryLevel = -1
	}

	// store first (no hub lock held inside store), then resolve pending
	d := h.store.ApplyReport(conn.DeviceID, conn.DeviceName, rep)

	if msg.RequestID != "" {
		h.mu.Lock()
		p := h.pending[msg.RequestID]
		h.mu.Unlock()
		if p != nil && p.deviceID == conn.DeviceID {
			select {
			case p.ch <- rep:
			default:
			}
			log.Printf("[report] 设备 %s 已响应按需上报 request=%s（耗时 %s）",
				maskID(conn.DeviceID), maskID(msg.RequestID), time.Since(p.created).Round(time.Millisecond))
		}
	}

	source := "scheduled"
	if msg.RequestID != "" {
		source = "on-demand"
	}
	log.Printf("[report] 设备 %s(%s) %s上报 app=%q screen=%v battery=%d%% %s net=%s",
		d.DeviceName, maskID(d.DeviceID), source, rep.Payload.ForegroundApp, rep.Payload.ScreenOn,
		rep.Payload.BatteryLevel, rep.Payload.BatteryStatus, rep.Payload.NetworkType)

	h.broadcast("device_update", map[string]any{"device": d, "report": rep}, nil)
}

// registerDevice replaces any previous connection of the same device.
func (h *Hub) registerDevice(conn *DeviceConn, model string) {
	h.mu.Lock()
	if old := h.deviceConns[conn.DeviceID]; old != nil {
		go old.Close()
	}
	h.deviceConns[conn.DeviceID] = conn
	h.mu.Unlock()

	d := h.store.UpsertFromRegister(conn.DeviceID, conn.DeviceName, model)
	log.Printf("[ws] 设备已注册 device=%s id=%s（当前在线 %d 台）",
		d.DeviceName, maskID(conn.DeviceID), h.OnlineCount())
	h.broadcast("device_online", map[string]any{"device": d}, nil)
}

func (h *Hub) unregisterDevice(conn *DeviceConn) {
	h.mu.Lock()
	if h.deviceConns[conn.DeviceID] == conn {
		delete(h.deviceConns, conn.DeviceID)
	} else {
		h.mu.Unlock()
		return
	}
	h.mu.Unlock()
	log.Printf("[ws] 设备离线 device=%s id=%s（当前在线 %d 台）",
		conn.DeviceName, maskID(conn.DeviceID), h.OnlineCount())
	h.broadcast("device_offline", map[string]any{"device_id": conn.DeviceID, "device_name": conn.DeviceName}, &conn.wsConn)
}

// ---- plugin connections ----

func (h *Hub) handlePluginWS(w http.ResponseWriter, r *http.Request) {
	if h.closed.Load() {
		http.Error(w, "server shutting down", http.StatusServiceUnavailable)
		return
	}
	if !tokenEqual(bearerToken(r), h.cfg.PluginToken) {
		log.Printf("[ws] 插件连接鉴权失败 ip=%s", remoteIP(r))
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	up := websocket.Upgrader{ReadBufferSize: 4096, WriteBufferSize: 4096}
	ws, err := up.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	conn := &PluginConn{wsConn: wsConn{ws: ws, send: make(chan []byte, sendChanSize)}, hub: h}

	h.mu.Lock()
	h.pluginConns[conn] = struct{}{}
	h.mu.Unlock()
	log.Printf("[ws] 插件已连接 ip=%s（当前 %d 个插件连接）", remoteIP(r), h.PluginCount())

	go conn.writePump(func() {
		h.mu.Lock()
		delete(h.pluginConns, conn)
		n := len(h.pluginConns)
		h.mu.Unlock()
		log.Printf("[ws] 插件连接断开（剩余 %d 个）", n)
	})

	// send a hello with the current device list
	if views := h.store.ListDevices(); len(views) > 0 {
		data := mustJSON(map[string]any{"type": "hello", "data": map[string]any{"devices": views}, "ts": nowMs()})
		conn.trySend(data)
	}

	_ = ws.SetReadDeadline(time.Now().Add(pongWait))
	ws.SetPongHandler(func(string) error { return ws.SetReadDeadline(time.Now().Add(pongWait)) })
	for {
		_ = ws.SetReadDeadline(time.Now().Add(pongWait))
		if _, _, err := ws.ReadMessage(); err != nil {
			return
		}
		// plugin -> server messages are not required today; ignore payload
	}
}

// ---- broadcasts ----

// broadcast sends an event to all plugin connections (excluding optionally one).
func (h *Hub) broadcast(event string, payload any, exclude *wsConn) {
	if h.closed.Load() {
		return
	}
	data, err := json.Marshal(map[string]any{"type": event, "data": payload, "ts": nowMs()})
	if err != nil {
		return
	}
	h.mu.RLock()
	conns := make([]*wsConn, 0, len(h.pluginConns))
	for p := range h.pluginConns {
		conns = append(conns, &p.wsConn)
	}
	h.mu.RUnlock()
	sent := 0
	for _, c := range conns {
		if c == exclude {
			continue
		}
		if c.trySend(data) {
			sent++
		}
	}
	if sent > 0 {
		log.Printf("[ws] 已向 %d 个插件连接广播 %s", sent, event)
	}
}

// ---- request_report ----

var (
	ErrDeviceOffline = errors.New("设备离线")
	ErrCooldown      = errors.New("按需上报冷却中")
)

// RequestReport sends a request_report to the device and waits for the
// matching fresh report. Rate limiting: one in-flight on-demand request per
// device, plus a per-device cooldown.
func (h *Hub) RequestReport(deviceID, reason string, timeout time.Duration) (*Report, error) {
	if h.closed.Load() {
		return nil, ErrDeviceOffline
	}

	h.mu.Lock()
	conn := h.deviceConns[deviceID]
	if conn == nil {
		h.mu.Unlock()
		log.Printf("[fresh] 设备 %s 离线，无法下发 request_report", deviceID)
		return nil, ErrDeviceOffline
	}
	now := nowMs()
	if last := h.freshLast[deviceID]; now-last < int64(h.cfg.FreshCooldownMs) {
		h.mu.Unlock()
		log.Printf("[fresh] 设备 %s 冷却中（距上次 %dms < %dms），跳过下发", deviceID, now-last, h.cfg.FreshCooldownMs)
		return nil, fmt.Errorf("%w，请稍后再试", ErrCooldown)
	}
	h.freshLast[deviceID] = now

	requestID := newRequestID()
	p := &pendingRequest{deviceID: deviceID, ch: make(chan *Report, 1), created: time.Now()}
	h.pending[requestID] = p
	sent := conn.trySend(mustJSON(&deviceMsg{
		Type:      "request_report",
		RequestID: requestID,
		Reason:    reason,
		Timestamp: now,
	}))
	if !sent {
		delete(h.pending, requestID)
		h.mu.Unlock()
		log.Printf("[fresh] request_report 下发失败（连接不可用）device=%s request=%s", deviceID, maskID(requestID))
		return nil, ErrDeviceOffline
	}
	h.mu.Unlock()

	log.Printf("[fresh] 已向设备 %s 下发 request_report request=%s reason=%q 等待=%s",
		deviceID, maskID(requestID), reason, timeout)

	timer := time.NewTimer(timeout)
	defer timer.Stop()
	defer func() {
		h.mu.Lock()
		delete(h.pending, requestID)
		h.mu.Unlock()
	}()

	select {
	case rep := <-p.ch:
		return rep, nil
	case <-timer.C:
		log.Printf("[fresh] 等待设备 %s 新鲜上报超时（%s），将降级返回缓存", deviceID, timeout)
		return nil, fmt.Errorf("设备在 %s 内未响应按需上报", timeout)
	}
}

// ---- helpers ----

func (h *Hub) reply(conn *DeviceConn, msg *deviceMsg) {
	conn.trySend(mustJSON(msg))
}

func (h *Hub) allowReport(deviceID string) bool {
	if h.cfg.ReportRatePerMin <= 0 {
		return true
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	now := nowMs()
	hits := h.reportHits[deviceID]
	kept := hits[:0]
	for _, t := range hits {
		if now-t < 60_000 {
			kept = append(kept, t)
		}
	}
	hits = kept
	if len(hits) >= h.cfg.ReportRatePerMin {
		h.reportHits[deviceID] = hits
		return false
	}
	h.reportHits[deviceID] = append(hits, now)
	return true
}

func (h *Hub) FillOnline(views []*DeviceView) {
	h.mu.RLock()
	defer h.mu.RUnlock()
	for _, v := range views {
		_, v.Online = h.deviceConns[v.DeviceID]
	}
}

func (h *Hub) IsDeviceOnline(deviceID string) bool {
	h.mu.RLock()
	defer h.mu.RUnlock()
	_, ok := h.deviceConns[deviceID]
	return ok
}

func (h *Hub) OnlineCount() int {
	h.mu.RLock()
	defer h.mu.RUnlock()
	return len(h.deviceConns)
}

func (h *Hub) PluginCount() int {
	h.mu.RLock()
	defer h.mu.RUnlock()
	return len(h.pluginConns)
}

// HeartbeatLoop logs a periodic liveness line and prunes stale pending
// requests (defensive; timeouts are handled per-request).
func (h *Hub) HeartbeatLoop() {
	t := time.NewTicker(60 * time.Second)
	defer t.Stop()
	for {
		select {
		case <-t.C:
			h.mu.Lock()
			stale := 0
			for id, p := range h.pending {
				if time.Since(p.created) > 2*time.Minute {
					delete(h.pending, id)
					stale++
				}
			}
			h.mu.Unlock()
			online := h.OnlineCount()
			onlineDevices.Store(int64(online))
			log.Printf("[hub] 心跳 在线设备=%d 插件连接=%d 待处理按需请求=%d(清理陈旧=%d)",
				online, h.PluginCount(), len(h.pending), stale)
		}
	}
}

func (h *Hub) Close() {
	h.closed.Store(true)
	h.mu.Lock()
	conns := make([]*wsConn, 0, len(h.deviceConns)+len(h.pluginConns))
	for _, d := range h.deviceConns {
		conns = append(conns, &d.wsConn)
	}
	for p := range h.pluginConns {
		conns = append(conns, &p.wsConn)
	}
	h.deviceConns = map[string]*DeviceConn{}
	h.pluginConns = map[*PluginConn]struct{}{}
	h.mu.Unlock()
	for _, c := range conns {
		c.Close()
	}
}

// ---- small utils ----

func normalizeDevice(id, name, model string) (string, string, string) {
	id = trimClamp(id, 64)
	name = trimClamp(name, 64)
	model = trimClamp(model, 64)
	if name == "" {
		name = "未命名设备"
	}
	return id, name, model
}

func trimClamp(s string, n int) string {
	s = strings.TrimSpace(s)
	if len(s) > n {
		r := []rune(s)
		if len(r) > n {
			s = string(r[:n])
		}
	}
	return s
}

func newRequestID() string {
	buf := make([]byte, 16)
	if _, err := rand.Read(buf); err != nil {
		return fmt.Sprintf("req-%d-%d", nowMs(), timeNowUnixNano()%1_000_000)
	}
	return hex.EncodeToString(buf)
}

func maskID(id string) string {
	if len(id) <= 8 {
		return id
	}
	return id[:6] + "..." + id[len(id)-4:]
}

func mustJSON(v any) []byte {
	data, err := json.Marshal(v)
	if err != nil {
		return []byte(`{"type":"error"}`)
	}
	return data
}
