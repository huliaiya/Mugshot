package main

import (
	"crypto/subtle"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
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
	mux.HandleFunc("/admin/config", a.requirePlugin(a.handleAdminConfig))
	mux.HandleFunc("/local/config", a.handleLocalConfig)
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

// deviceTokenValid 判断请求令牌是否有效：统一 token 或旧 device_token 都接受。
func deviceTokenValid(cfg *Config, got string) bool {
	return tokenEqual(got, cfg.Token) || tokenEqual(got, cfg.DeviceToken)
}

// pluginTokenValid 判断请求令牌是否有效：统一 token 或旧 plugin_token 都接受。
func pluginTokenValid(cfg *Config, got string) bool {
	return tokenEqual(got, cfg.Token) || tokenEqual(got, cfg.PluginToken)
}

// pluginAuth 判断请求方是否持有有效令牌。token 是统一令牌；
// device_token / plugin_token 是旧配置里的字段，迁移期间继续接受。
func (a *API) pluginAuth(got string) bool {
	return pluginTokenValid(a.cfg, got)
}

func (a *API) deviceAuth(got string) bool {
	return deviceTokenValid(a.cfg, got)
}

func (a *API) requirePlugin(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !a.pluginAuth(bearerToken(r)) {
			log.Printf("[api] 插件鉴权失败 ip=%s path=%s", remoteIP(r), r.URL.Path)
			writeJSON(w, http.StatusUnauthorized, map[string]any{"ok": false, "error": "unauthorized"})
			return
		}
		next(w, r)
	}
}

func (a *API) requireDevice(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !a.deviceAuth(bearerToken(r)) {
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

// handleLocalConfig 给同机运行的 GUI 免授权读取统一 Token。
// 仅接受本机回环来源，局域网/远程请求一律拒绝，避免 Token 泄露到网内其他主机。
func (a *API) handleLocalConfig(w http.ResponseWriter, r *http.Request) {
	host := remoteIP(r)
	if host != "127.0.0.1" && host != "::1" {
		writeJSON(w, http.StatusForbidden, map[string]any{"ok": false, "error": "local only"})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":     true,
		"token":  a.cfg.Token,
		"listen": a.cfg.Listen,
	})
}

// adminConfigSettings 是 GET /admin/config 返回给 GUI 的服务端设置视图。
type adminConfigSettings struct {
	OK               bool   `json:"ok"`
	Listen           string `json:"listen"`
	Token            string `json:"token"`
	DataDir          string `json:"data_dir"`
	ReportRatePerMin int    `json:"report_rate_per_min"`
	RetentionDays    int    `json:"retention_days"`
	OnlineTimeoutSec int    `json:"online_timeout_sec"`
	GithubRepo       string `json:"github_repo"`
	GithubMirror     string `json:"github_mirror"`
	UpdateAutoCheck  bool   `json:"update_auto_check"`
	UpdateAutoApply  bool   `json:"update_auto_apply"`
}

func (a *API) handleAdminConfig(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		writeJSON(w, http.StatusOK, adminConfigSettings{
			OK:               true,
			Listen:           a.cfg.Listen,
			Token:            a.cfg.Token,
			DataDir:          a.cfg.DataDir,
			ReportRatePerMin: a.cfg.ReportRatePerMin,
			RetentionDays:    a.cfg.RetentionDays,
			OnlineTimeoutSec: a.cfg.OnlineTimeoutSec,
			GithubRepo:       a.cfg.GithubRepo,
			GithubMirror:     a.cfg.GithubMirror,
			UpdateAutoCheck:  a.cfg.UpdateAutoCheck,
			UpdateAutoApply:  a.cfg.UpdateAutoApply,
		})
	case http.MethodPost:
		a.handleSetConfig(w, r)
	default:
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"ok": false, "error": "method not allowed"})
	}
}

// configPatch 用指针区分"未提交"与"提交了零值"。
type configPatch struct {
	Listen           *string `json:"listen"`
	Token            *string `json:"token"`
	ReportRatePerMin *int    `json:"report_rate_per_min"`
	RetentionDays    *int    `json:"retention_days"`
	OnlineTimeoutSec *int    `json:"online_timeout_sec"`
	GithubMirror     *string `json:"github_mirror"`
	UpdateAutoCheck  *bool   `json:"update_auto_check"`
}

// handleSetConfig 校验并应用 GUI 提交的设置，热生效（listen 除外，需重启），
// 并以"读原文件 -> 覆盖 -> 原子写回"的方式持久化，保留文件中的其他字段。
func (a *API) handleSetConfig(w http.ResponseWriter, r *http.Request) {
	var p configPatch
	body, err := readAllLimit(r.Body, 1<<20)
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "读取请求体失败"})
		return
	}
	if err := json.Unmarshal(body, &p); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "请求格式错误（需要 JSON）"})
		return
	}

	// ---- 校验 ----
	if p.Listen != nil {
		l := strings.TrimSpace(*p.Listen)
		if _, _, err := net.SplitHostPort(l); err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "监听地址格式应为 host:port，例如 :8080"})
			return
		}
		if msg := validPort(l); msg != "" {
			writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": msg})
			return
		}
	}
	if p.Token != nil {
		t := strings.TrimSpace(*p.Token)
		if len(t) < 6 || len(t) > 128 || strings.ContainsAny(t, " \t\r\n") {
			writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "Token 需为 6-128 位且不含空白字符"})
			return
		}
	}
	between := func(v, lo, hi int) bool { return v >= lo && v <= hi }
	if p.ReportRatePerMin != nil && !between(*p.ReportRatePerMin, 1, 100000) {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "上报频率需在 1-100000 次/分钟之间"})
		return
	}
	if p.RetentionDays != nil && !between(*p.RetentionDays, 1, 3650) {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "保留天数需在 1-3650 之间"})
		return
	}
	if p.OnlineTimeoutSec != nil && !between(*p.OnlineTimeoutSec, 5, 86400) {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "离线判定需在 5-86400 秒之间"})
		return
	}
	if p.GithubMirror != nil {
		m := strings.TrimSpace(*p.GithubMirror)
		if m != "" && !strings.HasPrefix(m, "http://") && !strings.HasPrefix(m, "https://") {
			writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "下载镜像需以 http:// 或 https:// 开头"})
			return
		}
	}

	// ---- 持久化（先写文件，成功后再改内存，失败时保持原状）----
	rotated := false
	restart := false
	if err := a.persistConfig(p, &rotated); err != nil {
		log.Printf("[api] 保存设置失败: %v", err)
		writeJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "error": "写入配置文件失败: " + err.Error()})
		return
	}
	if p.Listen != nil {
		if nv := strings.TrimSpace(*p.Listen); nv != a.cfg.Listen {
			a.cfg.Listen = nv
			restart = true
		}
	}
	if p.Token != nil {
		a.cfg.Token = strings.TrimSpace(*p.Token)
	}
	if p.ReportRatePerMin != nil {
		a.cfg.ReportRatePerMin = *p.ReportRatePerMin
	}
	if p.RetentionDays != nil {
		a.cfg.RetentionDays = *p.RetentionDays
	}
	if p.OnlineTimeoutSec != nil {
		a.cfg.OnlineTimeoutSec = *p.OnlineTimeoutSec
	}
	if p.GithubMirror != nil {
		a.cfg.GithubMirror = strings.TrimSpace(*p.GithubMirror)
	}
	if p.UpdateAutoCheck != nil {
		a.cfg.UpdateAutoCheck = *p.UpdateAutoCheck
	}

	log.Printf("[api] 设置已更新 ip=%s listen=%v token_rotated=%v", remoteIP(r), p.Listen != nil, rotated)
	note := "已保存并即时生效"
	if restart {
		note = "监听地址已修改，重启服务后生效"
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":               true,
		"restart_required": restart,
		"note":             note,
	})
}

// persistConfig 把补丁覆盖进配置文件。轮换 Token 时同时清除旧的
// device_token / plugin_token 字段，保证文件里只剩一个有效令牌。
func (a *API) persistConfig(p configPatch, rotated *bool) error {
	m := map[string]any{}
	if data, err := os.ReadFile(a.cfg.Path()); err == nil && len(data) > 0 {
		if err := json.Unmarshal(data, &m); err != nil {
			return fmt.Errorf("现有配置文件无法解析: %w", err)
		}
	}
	if p.Listen != nil {
		m["listen"] = strings.TrimSpace(*p.Listen)
	}
	if p.Token != nil {
		m["token"] = strings.TrimSpace(*p.Token)
		delete(m, "device_token")
		delete(m, "plugin_token")
		*rotated = true
	}
	if p.ReportRatePerMin != nil {
		m["report_rate_per_min"] = *p.ReportRatePerMin
	}
	if p.RetentionDays != nil {
		m["retention_days"] = *p.RetentionDays
	}
	if p.OnlineTimeoutSec != nil {
		m["online_timeout_sec"] = *p.OnlineTimeoutSec
	}
	if p.GithubMirror != nil {
		m["github_mirror"] = strings.TrimSpace(*p.GithubMirror)
	}
	if p.UpdateAutoCheck != nil {
		m["update_auto_check"] = *p.UpdateAutoCheck
	}
	data, err := json.MarshalIndent(m, "", "  ")
	if err != nil {
		return err
	}
	data = append(data, '\n')
	path := a.cfg.Path()
	if dir := filepath.Dir(path); dir != "" {
		if err := os.MkdirAll(dir, 0750); err != nil {
			return err
		}
	}
	return writeConfigFile(path, data)
}

func validPort(listen string) string {
	_, portStr, _ := net.SplitHostPort(listen)
	port, err := strconv.Atoi(portStr)
	if err != nil || port < 1 || port > 65535 {
		return "端口需为 1-65535 的数字"
	}
	return ""
}

func readAllLimit(r io.Reader, limit int64) ([]byte, error) {
	return io.ReadAll(io.LimitReader(r, limit))
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
	rel, err := CheckLatestReleaseForce(a.cfg)
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
