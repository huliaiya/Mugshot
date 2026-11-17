package main

import (
	"os"
	"path/filepath"
	"runtime"
	"strings"
)

// ensureAutostart 写入桌面自启动项：登录后以 --minimized 启动，直接收进托盘。
// 这样只要服务端在运行，托盘里就能看到状态与快捷操作，不必每次手动打开程序。
// 写入失败（无桌面环境、目录不可写）时静默跳过，不影响主程序。
func ensureAutostart() {
	if runtime.GOOS != "linux" {
		return
	}
	exe, err := os.Executable()
	if err != nil || exe == "" {
		return
	}
	base, err := os.UserConfigDir()
	if err != nil || base == "" {
		return
	}
	dir := filepath.Join(base, "autostart")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return
	}
	path := filepath.Join(dir, "mugshot-gui.desktop")
	content := autostartEntry(exe)
	if old, err := os.ReadFile(path); err == nil && string(old) == content {
		return
	}
	_ = os.WriteFile(path, []byte(content), 0o644)
}

// autostartEntry 生成 .desktop 内容，Exec 中的路径按规范加引号转义。
func autostartEntry(exe string) string {
	escaped := strings.ReplaceAll(exe, `"`, `\"`)
	var b strings.Builder
	b.WriteString("[Desktop Entry]\n")
	b.WriteString("Type=Application\n")
	b.WriteString("Name=Mugshot 服务端\n")
	b.WriteString("Comment=托盘常驻，随时查看服务状态\n")
	b.WriteString("Exec=\"" + escaped + "\" --minimized\n")
	b.WriteString("Terminal=false\n")
	b.WriteString("X-GNOME-Autostart-enabled=true\n")
	return b.String()
}
