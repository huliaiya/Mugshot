package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// TestAuthFailLimiterSuppressesWithinWindow 验证同一 key 在一分钟窗口内只记录一次。
func TestAuthFailLimiterSuppressesWithinWindow(t *testing.T) {
	l := newAuthFailLimiter()
	ok, count := l.shouldLog("插件|127.0.0.1|/devices")
	if !ok || count != 1 {
		t.Fatalf("首次失败应立即记录且计数为 1，得到 ok=%v count=%d", ok, count)
	}
	for i := 0; i < 9; i++ {
		ok, count := l.shouldLog("插件|127.0.0.1|/devices")
		if ok {
			t.Fatalf("窗口内第 %d 次重复失败不应记录", i+2)
		}
		if count != i+2 {
			t.Fatalf("窗口内计数应为 %d，得到 %d", i+2, count)
		}
	}
}

// TestAuthFailLimiterAggregatesAfterWindow 验证窗口结束后下一条日志带上累计次数。
func TestAuthFailLimiterAggregatesAfterWindow(t *testing.T) {
	l := newAuthFailLimiter()
	l.shouldLog("设备|127.0.0.1|/api/v1/report")
	l.shouldLog("设备|127.0.0.1|/api/v1/report")
	l.mu.Lock()
	l.last["设备|127.0.0.1|/api/v1/report"].windowStart = time.Now().Add(-2 * time.Minute)
	l.mu.Unlock()
	ok, count := l.shouldLog("设备|127.0.0.1|/api/v1/report")
	if !ok {
		t.Fatal("窗口结束后的失败应重新记录")
	}
	// 汇总的是刚结束的那个窗口里的失败次数（首次 1 次 + 被抑制的 1 次），
	// 当前这次失败属于新窗口，会计入下一轮汇总
	if count != 2 {
		t.Fatalf("汇总次数应为 2，得到 %d", count)
	}
}

// TestAuthFailLimiterSeparatesKeys 验证不同来源与路径各自独立计数。
func TestAuthFailLimiterSeparatesKeys(t *testing.T) {
	l := newAuthFailLimiter()
	l.shouldLog("插件|127.0.0.1|/devices")
	ok, _ := l.shouldLog("插件|127.0.0.1|/status")
	if !ok {
		t.Fatal("不同路径的失败应独立记录")
	}
	ok, _ = l.shouldLog("设备|127.0.0.1|/devices")
	if !ok {
		t.Fatal("不同来源的失败应独立记录")
	}
	ok, _ = l.shouldLog("插件|127.0.0.2|/devices")
	if !ok {
		t.Fatal("不同 IP 的失败应独立记录")
	}
}

// TestAuthFailLimiterCleanup 验证长期不出现的 key 会被清理。
func TestAuthFailLimiterCleanup(t *testing.T) {
	l := newAuthFailLimiter()
	l.shouldLog("stale")
	l.shouldLog("active")
	l.mu.Lock()
	l.last["stale"].windowStart = time.Now().Add(-20 * time.Minute)
	l.last["active"].windowStart = time.Now().Add(-2 * time.Minute)
	l.mu.Unlock()
	l.shouldLog("active")
	l.mu.Lock()
	_, staleLeft := l.last["stale"]
	_, activeLeft := l.last["active"]
	l.mu.Unlock()
	if staleLeft {
		t.Fatal("超过 10 分钟未出现的 key 应被清理")
	}
	if !activeLeft {
		t.Fatal("仍在窗口内的 key 不应被清理")
	}
}

// TestRotateTokenInvalidatesLegacyTokens 验证轮换 Token 后旧令牌立即失效：
// 内存里的兼容字段被清空，配置文件里也不再保留旧字段。
func TestRotateTokenInvalidatesLegacyTokens(t *testing.T) {
	dir := t.TempDir()
	cfgPath := filepath.Join(dir, "config.json")
	raw := map[string]any{
		"listen":       ":8080",
		"data_dir":     filepath.Join(dir, "data"),
		"token":        "old-unified-token",
		"device_token": "old-device-token",
		"plugin_token": "old-plugin-token",
	}
	data, err := json.Marshal(raw)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(cfgPath, data, 0600); err != nil {
		t.Fatal(err)
	}

	cfg := &Config{
		Listen:      ":8080",
		DataDir:     filepath.Join(dir, "data"),
		Token:       "old-unified-token",
		DeviceToken: "old-device-token",
		PluginToken: "old-plugin-token",
		path:        cfgPath,
	}
	store, err := OpenStore(cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()

	hub := NewHub(cfg, store)
	api := NewAPI(cfg, store, hub)
	mux := http.NewServeMux()
	api.Register(mux)
	srv := httptest.NewServer(mux)
	defer srv.Close()

	// 旧令牌此时仍然可用
	req, _ := http.NewRequest(http.MethodGet, srv.URL+"/devices", nil)
	req.Header.Set("Authorization", "Bearer old-plugin-token")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("轮换前旧插件令牌应可用，得到 HTTP %d", resp.StatusCode)
	}

	// 轮换 Token
	body, _ := json.Marshal(map[string]any{"token": "new-unified-token"})
	req, _ = http.NewRequest(http.MethodPost, srv.URL+"/admin/config", bytes.NewReader(body))
	req.Header.Set("Authorization", "Bearer old-plugin-token")
	req.Header.Set("Content-Type", "application/json")
	resp, err = http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("轮换 Token 应成功，得到 HTTP %d", resp.StatusCode)
	}

	if cfg.DeviceToken != "" || cfg.PluginToken != "" {
		t.Fatalf("轮换后内存里的兼容字段应清空，得到 device=%q plugin=%q", cfg.DeviceToken, cfg.PluginToken)
	}

	for _, old := range []string{"old-unified-token", "old-plugin-token", "old-device-token"} {
		req, _ = http.NewRequest(http.MethodGet, srv.URL+"/devices", nil)
		req.Header.Set("Authorization", "Bearer "+old)
		resp, err = http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		if resp.StatusCode != http.StatusUnauthorized {
			t.Fatalf("轮换后旧令牌 %q 应失效，得到 HTTP %d", old, resp.StatusCode)
		}
	}

	req, _ = http.NewRequest(http.MethodGet, srv.URL+"/devices", nil)
	req.Header.Set("Authorization", "Bearer new-unified-token")
	resp, err = http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("新令牌应可用，得到 HTTP %d", resp.StatusCode)
	}

	saved, err := os.ReadFile(cfgPath)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(saved), "old-device-token") || strings.Contains(string(saved), "old-plugin-token") {
		t.Fatalf("配置文件里不应保留旧令牌字段：%s", saved)
	}
}
