package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

// newTestConfig 造一份落在临时目录里的测试配置。
func newTestConfig(t *testing.T) (*Config, string) {
	t.Helper()
	dir := t.TempDir()
	cfgPath := filepath.Join(dir, "config.json")
	raw := map[string]any{
		"listen":   "127.0.0.1:0",
		"data_dir": filepath.Join(dir, "data"),
		"token":    "test-unified-token",
	}
	data, err := json.Marshal(raw)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(cfgPath, data, 0600); err != nil {
		t.Fatal(err)
	}
	cfg := &Config{
		Listen:  "127.0.0.1:0",
		DataDir: filepath.Join(dir, "data"),
		Token:   "test-unified-token",
		path:    cfgPath,
	}
	return cfg, cfgPath
}

// postAdminConfig 以插件令牌调用 POST /admin/config。
func postAdminConfig(t *testing.T, base string, body map[string]any) (int, map[string]any) {
	t.Helper()
	raw, err := json.Marshal(body)
	if err != nil {
		t.Fatal(err)
	}
	req, err := http.NewRequest(http.MethodPost, base+"/admin/config", bytes.NewReader(raw))
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Authorization", "Bearer test-unified-token")
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var out map[string]any
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		t.Fatalf("解析响应失败: %v", err)
	}
	return resp.StatusCode, out
}

// TestHotReloadPortSwitch 验证改监听端口后新端口立即可用、旧端口停止接收，
// 整个过程不重启进程。
func TestHotReloadPortSwitch(t *testing.T) {
	cfg, cfgPath := newTestConfig(t)
	store, err := OpenStore(cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()

	hub := NewHub(cfg, store)
	api := NewAPI(cfg, store, hub)
	mux := http.NewServeMux()
	api.Register(mux)
	hub.RegisterRoutes(mux)

	rt := newHTTPRuntime(mux)
	if err := rt.Start("127.0.0.1:0"); err != nil {
		t.Fatal(err)
	}
	defer func() {
		_ = rt.Shutdown(t.Context())
	}()
	api.SetRelisten(rt.Rebind)

	base := "http://" + rt.Addr()
	resp, err := http.Get(base + "/health")
	if err != nil {
		t.Fatalf("初始端口应可用: %v", err)
	}
	resp.Body.Close()

	// 换到一个空闲端口
	newAddr := freeLocalAddr(t)
	code, out := postAdminConfig(t, base, map[string]any{"listen": newAddr})
	if code != http.StatusOK {
		t.Fatalf("换端口应成功，得到 HTTP %d: %v", code, out)
	}
	if out["restart_required"] == true {
		t.Fatal("端口热切换不应要求重启")
	}
	if out["listen"] != newAddr {
		t.Fatalf("响应里的监听地址应为 %s，得到 %v", newAddr, out["listen"])
	}

	// 新端口立即可用
	newBase := "http://" + newAddr
	deadline := time.Now().Add(3 * time.Second)
	for {
		resp, err = http.Get(newBase + "/health")
		if err == nil {
			resp.Body.Close()
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("新端口 %s 应立即可用: %v", newAddr, err)
		}
		time.Sleep(20 * time.Millisecond)
	}

	// 配置已落盘
	saved, err := os.ReadFile(cfgPath)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(saved), newAddr) {
		t.Fatalf("配置文件里应是新监听地址 %s，实际内容：%s", newAddr, saved)
	}
	if cfg.GetListen() != newAddr {
		t.Fatalf("内存里的监听地址应为 %s，得到 %s", newAddr, cfg.GetListen())
	}
}

// TestHotReloadPortConflictKeepsOldPort 验证新端口被占用时保留旧端口继续服务。
func TestHotReloadPortConflictKeepsOldPort(t *testing.T) {
	cfg, _ := newTestConfig(t)
	store, err := OpenStore(cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()

	hub := NewHub(cfg, store)
	api := NewAPI(cfg, store, hub)
	mux := http.NewServeMux()
	api.Register(mux)
	hub.RegisterRoutes(mux)

	rt := newHTTPRuntime(mux)
	if err := rt.Start("127.0.0.1:0"); err != nil {
		t.Fatal(err)
	}
	defer func() {
		_ = rt.Shutdown(t.Context())
	}()
	api.SetRelisten(rt.Rebind)

	base := "http://" + rt.Addr()
	busy, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer busy.Close()

	oldCfgAddr := cfg.GetListen()
	code, out := postAdminConfig(t, base, map[string]any{"listen": busy.Addr().String()})
	if code == http.StatusOK {
		t.Fatalf("端口被占用时应报错，得到 HTTP %d: %v", code, out)
	}
	// 旧端口仍然可用
	resp, err := http.Get(base + "/health")
	if err != nil {
		t.Fatalf("绑定失败后旧端口应继续可用: %v", err)
	}
	resp.Body.Close()
	if cfg.GetListen() != oldCfgAddr {
		t.Fatalf("绑定失败时监听地址不应改变，当前 %s", cfg.GetListen())
	}
	if rt.Addr() != base[len("http://"):] {
		t.Fatalf("绑定失败后实际监听端口不应改变，当前 %s", rt.Addr())
	}
}

// TestHotReloadSettingsApplyWithoutRestart 验证其余设置改完即时生效，无需重启。
func TestHotReloadSettingsApplyWithoutRestart(t *testing.T) {
	cfg, cfgPath := newTestConfig(t)
	store, err := OpenStore(cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()

	hub := NewHub(cfg, store)
	api := NewAPI(cfg, store, hub)
	mux := http.NewServeMux()
	api.Register(mux)
	hub.RegisterRoutes(mux)

	rt := newHTTPRuntime(mux)
	if err := rt.Start("127.0.0.1:0"); err != nil {
		t.Fatal(err)
	}
	defer func() {
		_ = rt.Shutdown(t.Context())
	}()
	api.SetRelisten(rt.Rebind)

	base := "http://" + rt.Addr()
	code, out := postAdminConfig(t, base, map[string]any{
		"report_rate_per_min": 42,
		"retention_days":      9,
		"online_timeout_sec":  321,
		"github_mirror":       "https://mirror.example.com",
		"update_auto_check":   false,
	})
	if code != http.StatusOK {
		t.Fatalf("保存设置应成功，得到 HTTP %d: %v", code, out)
	}
	if cfg.GetReportRatePerMin() != 42 || cfg.GetRetentionDays() != 9 ||
		cfg.GetOnlineTimeoutSec() != 321 || cfg.GetGithubMirror() != "https://mirror.example.com" {
		t.Fatalf("设置应即时生效，当前 rate=%d retention=%d online=%d mirror=%s",
			cfg.GetReportRatePerMin(), cfg.GetRetentionDays(), cfg.GetOnlineTimeoutSec(), cfg.GetGithubMirror())
	}
	if cfg.GetUpdateAutoCheck() {
		t.Fatal("update_auto_check 应已关闭")
	}
	saved, err := os.ReadFile(cfgPath)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(saved), "\"report_rate_per_min\": 42") {
		t.Fatalf("配置文件应已更新，实际内容：%s", saved)
	}
}

// TestConfigConcurrentReadWrite 验证配置热更新期间并发读取不会触发数据竞争，
// 同时保证鉴权读到的是一致的令牌。
func TestConfigConcurrentReadWrite(t *testing.T) {
	cfg := &Config{Token: "token-a", Listen: ":8080"}
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			for j := 0; j < 200; j++ {
				cfg.Update(func(c *Config) {
					c.ReportRatePerMin = i*1000 + j
					c.RetentionDays = j % 30
					c.OnlineTimeoutSec = 60 + j%600
				})
			}
		}(i)
	}
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < 200; j++ {
				_ = cfg.GetReportRatePerMin()
				_ = cfg.GetRetentionDays()
				_ = cfg.GetOnlineTimeoutSec()
				_ = cfg.DeviceTokenValid("token-a")
				_ = cfg.PluginTokenValid("token-a")
			}
		}()
	}
	wg.Wait()
}

// TestTokenRotationUnderLoad 验证轮换令牌时并发鉴权只会看到新旧两种完整令牌，
// 不会出现拼一半的中间态。
func TestTokenRotationUnderLoad(t *testing.T) {
	cfg := &Config{Token: "old-token-value"}
	var wg sync.WaitGroup
	stop := make(chan struct{})
	wg.Add(1)
	go func() {
		defer wg.Done()
		for {
			select {
			case <-stop:
				return
			default:
			}
			if !cfg.DeviceTokenValid("old-token-value") && !cfg.DeviceTokenValid("new-token-value") {
				t.Error("鉴权读到的令牌既不是旧值也不是新值")
				return
			}
		}
	}()
	for i := 0; i < 100; i++ {
		cfg.Update(func(c *Config) {
			c.Token = "new-token-value"
		})
	}
	close(stop)
	wg.Wait()
	if !cfg.DeviceTokenValid("new-token-value") {
		t.Fatal("轮换后新令牌应有效")
	}
	if cfg.DeviceTokenValid("old-token-value") {
		t.Fatal("轮换后旧令牌应立即失效")
	}
}

// TestListenValidation 验证非法监听地址会被拒绝。
func TestListenValidation(t *testing.T) {
	cfg, _ := newTestConfig(t)
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

	for _, bad := range []string{"8080", ":0", ":70000", "host:port"} {
		code, out := postAdminConfig(t, srv.URL, map[string]any{"listen": bad})
		if code == http.StatusOK {
			t.Fatalf("非法监听地址 %q 应被拒绝，得到 %v", bad, out)
		}
	}
}

// freeLocalAddr 找一个当前空闲的回环端口。
func freeLocalAddr(t *testing.T) string {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	addr := ln.Addr().String()
	_ = ln.Close()
	return fmt.Sprintf("%s", addr)
}
