package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
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

// fetchLocalToken 从服务端的 /local/config 读取统一 Token。
// 该端点只接受回环连接：GUI 与服务端同机时无需任何授权弹窗；
// GUI 指向远程服务端时会被拒绝，此时在接入信息页手动填写即可。
func fetchLocalToken(base string) (string, error) {
	req, err := http.NewRequest(http.MethodGet, strings.TrimRight(base, "/")+"/local/config", nil)
	if err != nil {
		return "", err
	}
	hc := &http.Client{Timeout: 4 * time.Second}
	resp, err := hc.Do(req)
	if err != nil {
		return "", fmt.Errorf("连接服务端失败: %w", err)
	}
	defer resp.Body.Close()
	switch {
	case resp.StatusCode == http.StatusForbidden:
		return "", errors.New("服务端拒绝：Token 自动读取仅限与本机服务端同机时使用")
	case resp.StatusCode != http.StatusOK:
		return "", fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	var out struct {
		OK    bool   `json:"ok"`
		Token string `json:"token"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return "", fmt.Errorf("解析响应失败: %w", err)
	}
	if !out.OK || out.Token == "" {
		return "", errors.New("响应缺少 Token")
	}
	return out.Token, nil
}
