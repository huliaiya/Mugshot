package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

// TestSyncServerURLUpdatesSavedPort 验证设置页改端口后，
// 接入信息页保存的地址自动换成新端口并落盘。
func TestSyncServerURLUpdatesSavedPort(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("XDG_CONFIG_HOME", dir)
	confPath := filepath.Join(dir, "mugshot", "gui.conf")
	if err := os.MkdirAll(filepath.Dir(confPath), 0o755); err != nil {
		t.Fatal(err)
	}
	seed := guiConf{ServerURL: "http://127.0.0.1:18080", PluginToken: "tok-123456"}
	data, err := json.Marshal(seed)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(confPath, data, 0o600); err != nil {
		t.Fatal(err)
	}

	loaded, ok := loadConf()
	if !ok {
		t.Fatal("应能读到已保存的连接信息")
	}
	u := &ui{conf: loaded, quit: make(chan struct{})}
	u.cli = u.newClient()

	u.syncServerURL("127.0.0.1:18081")

	if got := u.serverURL(); got != "http://127.0.0.1:18081" {
		t.Fatalf("接入信息地址应同步为新端口，实际 %q", got)
	}
	if got := u.client().base; got != "http://127.0.0.1:18081" {
		t.Fatalf("客户端应指向新端口，实际 %q", got)
	}

	saved, ok := loadConf()
	if !ok {
		t.Fatal("配置文件里的地址应已更新")
	}
	if saved.ServerURL != "http://127.0.0.1:18081" {
		t.Fatalf("配置文件里的地址应已更新，实际 %q", saved.ServerURL)
	}
	if saved.PluginToken != "tok-123456" {
		t.Fatalf("Token 不应被端口同步影响，实际 %q", saved.PluginToken)
	}
}

// TestSyncServerURLKeepsHost 验证换端口时保留用户填写的主机名。
func TestSyncServerURLKeepsHost(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("XDG_CONFIG_HOME", dir)
	if err := os.MkdirAll(filepath.Join(dir, "mugshot"), 0o755); err != nil {
		t.Fatal(err)
	}
	seed := guiConf{ServerURL: "http://192.168.1.20:8080", PluginToken: "tok-123456"}
	data, _ := json.Marshal(seed)
	if err := os.WriteFile(filepath.Join(dir, "mugshot", "gui.conf"), data, 0o600); err != nil {
		t.Fatal(err)
	}
	loaded, _ := loadConf()
	u := &ui{conf: loaded, quit: make(chan struct{})}
	u.cli = u.newClient()

	u.syncServerURL(":9090")

	if got := u.serverURL(); got != "http://192.168.1.20:9090" {
		t.Fatalf("应保留主机名并只换端口，实际 %q", got)
	}
}
