package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/url"
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

// isLocalHost 判断 host 是否指向本机：回环地址或本机网卡的 IP。
// 只有指向本机时才允许走免授权的 Token 自动同步，避免误读另一台机器的服务端。
func isLocalHost(host string) bool {
	h := strings.TrimSpace(strings.ToLower(host))
	h = strings.Trim(h, "[]")
	if h == "" || h == "localhost" {
		return true
	}
	if ip := net.ParseIP(h); ip != nil {
		if ip.IsLoopback() {
			return true
		}
		addrs, err := net.InterfaceAddrs()
		if err != nil {
			return false
		}
		for _, a := range addrs {
			if ipNet, ok := a.(*net.IPNet); ok && ipNet.IP.Equal(ip) {
				return true
			}
		}
	}
	return false
}

// localBaseCandidates 返回读取本机 Token 的候选地址：
// 用户填的地址本身，以及把主机名换成 127.0.0.1 的同端口地址。
// GUI 与服务端同机但用户填了局域网 IP 时，服务端看到的来源是网卡 IP，
// /local/config 会拒绝；走 127.0.0.1 才能稳定读到。
func localBaseCandidates(base string) []string {
	out := []string{}
	trimmed := strings.TrimRight(strings.TrimSpace(base), "/")
	if trimmed != "" {
		out = append(out, trimmed)
	}
	u, err := url.Parse(trimmed)
	if err != nil || u.Host == "" {
		return out
	}
	port := u.Port()
	if port == "" {
		if u.Scheme == "https" {
			port = "443"
		} else {
			port = "80"
		}
	}
	loop := "http://127.0.0.1:" + port
	if u.Scheme == "https" {
		loop = "https://127.0.0.1:" + port
	}
	if loop != trimmed {
		out = append(out, loop)
	}
	return out
}
