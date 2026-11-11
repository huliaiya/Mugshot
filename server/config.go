package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"strings"
)

// Config holds all server settings. It is loaded from (in order of priority):
//  1. environment variables (MUGSHOT_*)
//  2. JSON config file (default ./config.json)
//  3. built-in defaults
//
// Tokens are secrets: if they are not configured, the server generates random
// ones and persists them into the config file. Logs never print full tokens.
type Config struct {
	Listen  string `json:"listen"`   // HTTP/WS listen address
	DataDir string `json:"data_dir"` // persistence directory
	// Token 是 App / 插件 / GUI 共用的唯一令牌。device_token 与 plugin_token
	// 是历史字段：仅作兼容保留（读取旧配置文件时迁移到 Token，鉴权时仍然接受），
	// 新配置文件只写 token。
	Token            string `json:"token"`
	DeviceToken      string `json:"device_token,omitempty"` // 旧版安卓设备令牌（兼容）
	PluginToken      string `json:"plugin_token,omitempty"` // 旧版插件令牌（兼容）
	ReportRatePerMin int    `json:"report_rate_per_min"`    // per-device report rate cap
	FreshTimeoutMs   int    `json:"fresh_timeout_ms"`       // how long /status?fresh=1 waits for the device
	FreshCooldownMs  int    `json:"fresh_cooldown_ms"`      // min interval between request_report per device
	RetentionDays    int    `json:"retention_days"`         // prune devices unseen for this many days
	OnlineTimeoutSec int    `json:"online_timeout_sec"`     // no data for this long => treat as stale/offline
	GithubRepo       string `json:"github_repo"`            // "owner/repo" used by the update checker
	GithubMirror     string `json:"github_mirror"`          // optional download mirror prefix, e.g. https://ghfast.top
	UpdateAutoCheck  bool   `json:"update_auto_check"`      // check GitHub Releases at startup + daily
	UpdateAutoApply  bool   `json:"update_auto_apply"`      // dangerous: auto self-update when newer release found

	path string `json:"-"` // resolved config file path (not serialized)
}

// Path returns the resolved config file path used at load time.
func (c *Config) Path() string { return c.path }

func DefaultConfig() *Config {
	return &Config{
		Listen:           ":8080",
		DataDir:          "./data",
		ReportRatePerMin: 30,
		FreshTimeoutMs:   5000,
		FreshCooldownMs:  5000,
		RetentionDays:    7,
		OnlineTimeoutSec: 120,
		UpdateAutoCheck:  true,
	}
}

// LoadConfig reads the config file (if any), applies env overrides, and makes
// sure tokens exist (generating + persisting them when missing).
func LoadConfig(explicitPath string) (*Config, error) {
	cfg := DefaultConfig()

	path := explicitPath
	if path == "" {
		path = os.Getenv("MUGSHOT_CONFIG")
	}
	if path == "" {
		path = "config.json"
	}

	if data, err := os.ReadFile(path); err == nil {
		if err := json.Unmarshal(data, cfg); err != nil {
			return nil, fmt.Errorf("解析配置文件 %s: %w", path, err)
		}
	} else if explicitPath != "" || os.Getenv("MUGSHOT_CONFIG") != "" {
		return nil, fmt.Errorf("读取配置文件 %s: %w", path, err)
	}
	cfg.path = path

	// environment overrides
	applyEnv(cfg)

	// 统一 Token：旧配置只写了 device_token / plugin_token 时迁移到 token；
	// 旧字段原样保留在文件里，鉴权时继续接受，升级后两端无需立刻改配置。
	changed := false
	if cfg.Token == "" {
		if cfg.PluginToken != "" {
			cfg.Token = cfg.PluginToken
		} else {
			cfg.Token = cfg.DeviceToken
		}
		changed = cfg.Token != ""
	}
	if cfg.Token == "" {
		cfg.Token = randomToken()
		changed = true
		logGenerated("token")
	}
	if changed {
		if err := SaveConfig(cfg, path); err != nil {
			logConfigWrite(path, err)
		}
	}
	return cfg, nil
}

func SaveConfig(cfg *Config, path string) error {
	data, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0750); err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, 0600); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

func applyEnv(cfg *Config) {
	envStr := func(key string, dst *string) {
		if v := strings.TrimSpace(os.Getenv(key)); v != "" {
			*dst = v
		}
	}
	envInt := func(key string, dst *int) {
		if v := strings.TrimSpace(os.Getenv(key)); v != "" {
			var n int
			if _, err := fmt.Sscanf(v, "%d", &n); err == nil {
				*dst = n
			}
		}
	}
	envBool := func(key string, dst *bool) {
		v := strings.ToLower(strings.TrimSpace(os.Getenv(key)))
		switch v {
		case "1", "true", "yes", "on":
			*dst = true
		case "0", "false", "no", "off":
			*dst = false
		}
	}

	envStr("MUGSHOT_LISTEN", &cfg.Listen)
	envStr("MUGSHOT_DATA_DIR", &cfg.DataDir)
	envStr("MUGSHOT_TOKEN", &cfg.Token)
	envStr("MUGSHOT_DEVICE_TOKEN", &cfg.DeviceToken)
	envStr("MUGSHOT_PLUGIN_TOKEN", &cfg.PluginToken)
	envStr("MUGSHOT_GITHUB_REPO", &cfg.GithubRepo)
	envStr("MUGSHOT_GITHUB_MIRROR", &cfg.GithubMirror)
	envInt("MUGSHOT_REPORT_RATE_PER_MIN", &cfg.ReportRatePerMin)
	envInt("MUGSHOT_FRESH_TIMEOUT_MS", &cfg.FreshTimeoutMs)
	envInt("MUGSHOT_FRESH_COOLDOWN_MS", &cfg.FreshCooldownMs)
	envInt("MUGSHOT_RETENTION_DAYS", &cfg.RetentionDays)
	envInt("MUGSHOT_ONLINE_TIMEOUT_SEC", &cfg.OnlineTimeoutSec)
	envBool("MUGSHOT_UPDATE_AUTO_CHECK", &cfg.UpdateAutoCheck)
	envBool("MUGSHOT_UPDATE_AUTO_APPLY", &cfg.UpdateAutoApply)
}

func randomToken() string {
	buf := make([]byte, 24)
	if _, err := rand.Read(buf); err != nil {
		return fmt.Sprintf("mugshot-%d", timeNowUnixNano())
	}
	return hex.EncodeToString(buf)
}

func maskToken(tok string) string {
	if len(tok) <= 4 {
		return "****"
	}
	return tok[:4] + "****"
}

// The next two helpers exist so that this file does not print full tokens;
// they log through the standard logger.
func logGenerated(name string) {
	log.Printf("[config] 未配置 %s，已自动生成随机 token 并写入配置文件（日志不显示完整值）", name)
}

func logConfigWrite(path string, err error) {
	log.Printf("[config] 配置文件写入失败 path=%s err=%v（token 仅存在于本次进程，重启会重新生成，请手动配置）", path, err)
}
