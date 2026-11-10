package main

import (
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

// guiConf 是 GUI 自己的连接配置，缓存在 ~/.config/mugshot/gui.conf。
type guiConf struct {
	ServerURL   string `json:"server_url"`
	PluginToken string `json:"plugin_token"`
}

func confFile() string {
	if dir, err := os.UserConfigDir(); err == nil {
		return filepath.Join(dir, "mugshot", "gui.conf")
	}
	return filepath.Join(os.TempDir(), "mugshot-gui.conf")
}

func loadConf() (guiConf, bool) {
	data, err := os.ReadFile(confFile())
	if err != nil {
		return guiConf{}, false
	}
	var c guiConf
	if json.Unmarshal(data, &c) != nil || c.ServerURL == "" {
		return guiConf{}, false
	}
	return c, true
}

func saveConf(c guiConf) error {
	if err := os.MkdirAll(filepath.Dir(confFile()), 0o755); err != nil {
		return err
	}
	data, err := json.MarshalIndent(c, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(confFile(), data, 0o600)
}

// fetchSystemConf 首次启动时通过 polkit 弹窗授权读取服务端配置，
// 从中取出插件 Token 与监听地址并缓存，之后不再需要授权。
func fetchSystemConf() (guiConf, error) {
	out, err := exec.Command("pkexec", "cat", "/etc/mugshot/config.json").Output()
	if err != nil {
		return guiConf{}, fmt.Errorf("读取服务端配置失败（需要 polkit 授权）: %v", err)
	}
	var raw struct {
		Listen      string `json:"listen"`
		PluginToken string `json:"plugin_token"`
	}
	if err := json.Unmarshal(out, &raw); err != nil {
		return guiConf{}, fmt.Errorf("解析服务端配置失败: %v", err)
	}
	c := guiConf{ServerURL: listenToURL(raw.Listen), PluginToken: raw.PluginToken}
	if c.ServerURL == "" {
		c.ServerURL = "http://127.0.0.1:8080"
	}
	if c.PluginToken == "" {
		return guiConf{}, fmt.Errorf("服务端配置缺少 plugin_token，请在界面中手动填写")
	}
	_ = saveConf(c)
	return c, nil
}

// listenToURL 把服务端 listen 配置转成本机可访问的 URL。
func listenToURL(l string) string {
	l = strings.TrimSpace(l)
	if l == "" {
		return ""
	}
	switch {
	case strings.HasPrefix(l, ":"):
		return "http://127.0.0.1" + l
	case strings.HasPrefix(l, "0.0.0.0:"), strings.HasPrefix(l, "[::]:"):
		return "http://127.0.0.1:" + l[strings.LastIndex(l, ":")+1:]
	case strings.Contains(l, "://"):
		return l
	default:
		return "http://" + l
	}
}
