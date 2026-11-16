package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// multiFlag 支持 -set 重复出现（Go flag 默认只保留最后一个值）。
type multiFlag []string

func (m *multiFlag) String() string { return strings.Join(*m, ",") }

func (m *multiFlag) Set(v string) error {
	*m = append(*m, v)
	return nil
}

// 命令行配置管理：mugshot-server -print-config / -set key=value
// 面向无桌面环境（SSH / x86_64 服务器），不依赖 jq 或图形界面。

func resolveConfigCLIPath(explicit string) string {
	if explicit != "" {
		return explicit
	}
	if p := os.Getenv("MUGSHOT_CONFIG"); p != "" {
		return p
	}
	return "config.json"
}

func runConfigCLI(configPath string, printConfig bool, sets []string) error {
	path := resolveConfigCLIPath(configPath)

	if printConfig && len(sets) == 0 {
		data, err := os.ReadFile(path)
		if err != nil {
			return fmt.Errorf("读取配置文件 %s: %w", path, err)
		}
		var raw map[string]any
		if err := json.Unmarshal(data, &raw); err != nil {
			return fmt.Errorf("解析配置文件 %s: %w", path, err)
		}
		out, err := json.MarshalIndent(raw, "", "  ")
		if err != nil {
			return err
		}
		fmt.Println(string(out))
		return nil
	}

	if len(sets) == 0 {
		return nil
	}

	data, err := os.ReadFile(path)
	if err != nil {
		return fmt.Errorf("读取配置文件 %s: %w", path, err)
	}
	var raw map[string]any
	if err := json.Unmarshal(data, &raw); err != nil {
		return fmt.Errorf("解析配置文件 %s: %w", path, err)
	}

	for _, item := range sets {
		key, value, ok := strings.Cut(item, "=")
		key = strings.TrimSpace(key)
		if !ok || key == "" {
			return fmt.Errorf("参数格式应为 key=value，收到 %q", item)
		}
		typed, err := coerceConfigValue(key, strings.TrimSpace(value), raw[key])
		if err != nil {
			return err
		}
		raw[key] = typed
	}

	out, err := json.MarshalIndent(raw, "", "  ")
	if err != nil {
		return err
	}
	out = append(out, '\n')
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, out, 0600); err != nil {
		return fmt.Errorf("写入临时文件失败: %w", err)
	}
	if err := os.Rename(tmp, path); err != nil {
		return fmt.Errorf("替换配置文件失败: %w", err)
	}
	fmt.Printf("已更新 %s：%s\n", filepath.Clean(path), strings.Join(sets, " "))
	if printConfig {
		fmt.Println()
		fmt.Println(string(out))
	}
	return nil
}

// coerceConfigValue 按已有值的类型转换，保证写回的是 JSON 数字/布尔而不是字符串；
// token=auto 会生成新的随机令牌，避免手输弱口令。
func coerceConfigValue(key, value string, current any) (any, error) {
	switch key {
	case "token", "device_token", "plugin_token":
		if value == "auto" || value == "random" {
			return randomToken(), nil
		}
		if value == "" {
			return nil, fmt.Errorf("%s 不能为空（可用 token=auto 生成随机值）", key)
		}
		return value, nil
	case "listen":
		if value == "" {
			return nil, fmt.Errorf("listen 不能为空")
		}
		return value, nil
	case "data_dir", "github_repo", "github_mirror":
		return value, nil
	}

	switch current.(type) {
	case bool:
		b, err := strconv.ParseBool(value)
		if err != nil {
			return nil, fmt.Errorf("%s 需要布尔值 true/false，收到 %q", key, value)
		}
		return b, nil
	case float64, int, int64:
		n, err := strconv.ParseInt(value, 10, 64)
		if err != nil {
			return nil, fmt.Errorf("%s 需要整数，收到 %q", key, value)
		}
		if n < 0 {
			return nil, fmt.Errorf("%s 不能为负数", key)
		}
		return n, nil
	}

	// 未知键：按数字/布尔/字符串自动判断，方便以后新增配置项
	if b, err := strconv.ParseBool(value); err == nil {
		return b, nil
	}
	if n, err := strconv.ParseInt(value, 10, 64); err == nil {
		return n, nil
	}
	return value, nil
}
