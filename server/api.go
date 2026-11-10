package main

import (
	"crypto/subtle"
	"encoding/json"
	"log"
	"net"
	"net/http"
	"os"
	"strconv"
	"time"
)

// API exposes the HTTP surface used by the Mugshot Yunzai plugin (and health
// probes / CLI tooling). All plugin-facing endpoints require the plugin
// token; the HTTP report ingest requires the device token.
type API struct {
	cfg   *Config
	store *Store
	hub   *Hub
}

func NewAPI(cfg *Config, store *Store, hub *Hub) *API {
	return &API{cfg: cfg, store: store, hub: hub}
}

func (a *API) Register(mux *http.ServeMux) {
	mux.HandleFunc("/health", a.handleHealth)
	mux.HandleFunc("/version", a.handleVersion)
	mux.HandleFunc("/devices", a.requirePlugin(a.handleDevices))
	mux.HandleFunc("/status", a.requirePlugin(a.handleStatus))
	mux.HandleFunc("/admin/update_check", a.requirePlugin(a.handleUpdateCheck))
	mux.HandleFunc("/admin/update", a.requirePlugin(a.handleUpdate))
	mux.HandleFunc("/api/v1/report", a.requireDevice(a.handleHTTPReport))
}

// ---- auth ----

func bearerToken(r *http.Request) string {
	const prefix = "Bearer "
	auth := r.Header.Get("Authorization")
	if len(auth) > len(prefix) && auth[:len(prefix)] == prefix {
		return auth[len(prefix):]
	}
	return r.URL.Query().Get("token")
}

func tokenEqual(got, want string) bool {
	if want == "" || got == "" {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(got), []byte(want)) == 1
}

func (a *API) requirePlugin(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !tokenEqual(bearerToken(r), a.cfg.PluginToken) {
			log.Printf("[api] 插件鉴权失败 ip=%s path=%s", remoteIP(r), r.URL.Path)
			writeJSON(w, http.StatusUnauthorized, map[string]any{"ok": false, "error": "unauthorized"})
			return
		}
		next(w, r)
	}
}

func (a *API) requireDevice(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !tokenEqual(bearerToken(r), a.cfg.DeviceToken) {
			log.Printf("[api] 设备鉴权失败 ip=%s path=%s", remoteIP(r), r.URL.Path)
			writeJSON(w, http.StatusUnauthorized, map[string]any{"ok": false, "error": "unauthorized"})
			return
		}
		next(w, r)
	}
}

func remoteIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

// ---- handlers ----

func (a *API) handleHealth(w http.ResponseWriter, r *http.Request) {
	views := a.store.ListDevices()
	online := 0
	a.hub.FillOnline(views)
	for _, v := range views {
		if v.Online {
			online++
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":             true,
		"name":           "mugshot-server",
		"version":        version,
		"commit":         commit,
		"go":             goVersion(),
		"uptime_sec":     uptimeSec(),
		"devices_total":  len(views),
		"devices_online": online,
		"server_time":    nowMs(),
	})
}

func (a *API) handleVersion(w http.ResponseWriter, r *http.Request) {
	rel, err := CheckLatestRelease(a.cfg)
	latest := ""
	if err == nil {
		latest = rel.Tag
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"name":        "mugshot-server",
		"version":     version,
		"commit":      commit,
		"go":          goVersion(),
		"latest":      latest,
		"update":      latest != "" && NewerThan(latest, releaseTag),
		"server_time": nowMs(),
	})
}

func (a *API) handleDevices(w http.ResponseWriter, r *http.Request) {
	views := a.store.ListDevices()
	a.hub.FillOnline(views)
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":          true,
		"devices":     views,
		"count":       len(views),
		"server_time": nowMs(),
	})
}

// handleStatus implements GET /status?device=<id|name>&fresh=0|1&timeout_ms=N
func (a *API) handleStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"ok": false, "error": "method not allowed"})
		return
	}
	idOrName := r.URL.Query().Get("device")
	fresh := r.URL.Query().Get("fresh") == "1" || r.URL.Query().Get("fresh") == "true"

	view, _, err := a.store.GetDevice(idOrName)
	if err != nil {
		writeJSON(w, http.StatusNotFound, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	a.hub.FillOnline([]*DeviceView{view})

	timeout := time.Duration(a.cfg.FreshTimeoutMs) * time.Millisecond
	if t := r.URL.Query().Get("timeout_ms"); t != "" {
		if n, err := strconv.Atoi(t); err == nil && n >= 500 && n <= 15000 {
			timeout = time.Duration(n) * time.Millisecond
		}
	}

	resp := map[string]any{
		"ok":          true,
		"device":      view,
		"server_time": nowMs(),
	}
	lastReportAt := view.LastReportAt
	resp["last_report_at"] = lastReportAt

	staleAfter := int64(a.cfg.OnlineTimeoutSec)
	if staleAfter < 300 {
		staleAfter = 300
	}
	stale := lastReportAt > 0 && nowMs()-lastReportAt > staleAfter*1000
	resp["stale"] = stale

	switch {
	case fresh && view.Online:
		rep, err := a.hub.RequestReport(view.DeviceID, "plugin fresh query from "+remoteIP(r), timeout)
		if err != nil {
			resp["data_source"] = "cache"
			resp["note"] = err.Error()
			resp["report"] = view.LastReport
			log.Printf("[api] fresh 查询降级缓存 device=%s(%s) err=%v", view.DeviceName, maskID(view.DeviceID), err)
		} else {
			resp["data_source"] = "live"
			resp["report"] = rep
			resp["note"] = ""
			// refresh the view so last_report_at reflects the fresh report
			if v2, _, err := a.store.GetDevice(view.DeviceID); err == nil {
				a.hub.FillOnline([]*DeviceView{v2})
				resp["device"] = v2
				resp["last_report_at"] = v2.LastReportAt
			}
		}
	case fresh && !view.Online:
		resp["data_source"] = "cache"
		resp["report"] = view.LastReport
		resp["note"] = "设备离线，已返回最近缓存"
	default:
		resp["data_source"] = "cache"
		resp["report"] = view.LastReport
		resp["note"] = ""
	}

	if resp["report"] == nil {
		resp["data_source"] = "none"
	}
	writeJSON(w, http.StatusOK, resp)
}

// handleHTTPReport is the HTTP fallback ingest for devices without a live
// WebSocket (e.g. the WorkManager degraded mode on Android 15 after the
// dataSync foreground-service time budget is exhausted).
func (a *API) handleHTTPReport(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"ok": false, "error": "method not allowed"})
		return
	}
	var msg deviceMsg
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxMsgSize)).Decode(&msg); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "bad json"})
		return
	}
	if msg.DeviceID == "" || msg.Payload == nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "device_id and payload required"})
		return
	}
	deviceID, deviceName, _ := normalizeDevice(msg.DeviceID, msg.DeviceName, "")
	rep := &Report{Timestamp: msg.Timestamp, RequestID: msg.RequestID, Payload: *msg.Payload}
	if rep.Timestamp <= 0 || rep.Timestamp > nowMs()+60_000 {
		rep.Timestamp = nowMs()
	}
	if rep.Payload.BatteryLevel < -1 || rep.Payload.BatteryLevel > 100 {
		rep.Payload.BatteryLevel = -1
	}
	d := a.store.ApplyReport(deviceID, deviceName, rep)
	log.Printf("[report] 设备 %s(%s) HTTP上报 app=%q battery=%d%% net=%s",
		d.DeviceName, maskID(d.DeviceID), rep.Payload.ForegroundApp, rep.Payload.BatteryLevel, rep.Payload.NetworkType)
	a.hub.broadcast("device_update", map[string]any{"device": d, "report": rep}, nil)
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "server_time": nowMs()})
}

// ---- admin: update ----

func (a *API) handleUpdateCheck(w http.ResponseWriter, r *http.Request) {
	rel, err := CheckLatestRelease(a.cfg)
	if err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":               true,
		"current":          version,
		"latest":           rel.Tag,
		"update_available": NewerThan(rel.Tag, releaseTag),
		"notes":            rel.Notes,
		"published_at":     rel.PublishedAt,
	})
}

func (a *API) handleUpdate(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"ok": false, "error": "method not allowed"})
		return
	}
	rel, err := CheckLatestRelease(a.cfg)
	if err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	if !NewerThan(rel.Tag, releaseTag) {
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "updated": false, "current": version, "latest": rel.Tag, "note": "已是最新版本"})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "updated": true, "from": version, "to": rel.Tag, "note": "正在后台下载并应用，进程将自动重启"})
	go func() {
		time.Sleep(time.Second) // let the response flush
		if err := SelfUpdate(a.cfg, rel); err != nil {
			log.Printf("[update] 自更新失败: %v", err)
			return
		}
		log.Printf("[update] 已更新到 %s，2 秒后退出，由 systemd Restart=always 拉起新版本", rel.Tag)
		time.Sleep(2 * time.Second)
		a.hub.Close()
		a.store.Close()
		osExit(0)
	}()
}

var osExit = os.Exit
