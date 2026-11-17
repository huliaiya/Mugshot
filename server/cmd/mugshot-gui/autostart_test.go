package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestAutostartEntryUsesMinimizedFlag(t *testing.T) {
	entry := autostartEntry("/opt/mugshot/mugshot-gui")
	if !strings.Contains(entry, `Exec="/opt/mugshot/mugshot-gui" --minimized`) {
		t.Fatalf("自启动项应带 --minimized：%s", entry)
	}
	if !strings.HasPrefix(entry, "[Desktop Entry]\n") {
		t.Fatal("自启动项缺少 [Desktop Entry] 头")
	}
}

func TestEnsureAutostartWritesFile(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("XDG_CONFIG_HOME", dir)
	ensureAutostart()
	path := filepath.Join(dir, "autostart", "mugshot-gui.desktop")
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("未写入自启动项：%v", err)
	}
	if !strings.Contains(string(data), "--minimized") {
		t.Fatalf("自启动项内容不正确：%s", data)
	}
}

func TestLauncherPathPrefersExecutableNeighbour(t *testing.T) {
	dir := t.TempDir()
	exe := filepath.Join(dir, "mugshot-gui")
	launcher := filepath.Join(dir, "mugshot-gui-launcher")
	if err := os.WriteFile(exe, []byte("#!/bin/sh\n"), 0o755); err != nil {
		t.Fatal(err)
	}
	if got := launcherPath(exe); got != "" {
		t.Fatalf("启动器不存在时应返回空串，实际 %q", got)
	}
	if err := os.WriteFile(launcher, []byte("#!/bin/sh\n"), 0o755); err != nil {
		t.Fatal(err)
	}
	if got := launcherPath(exe); got != launcher {
		t.Fatalf("应返回同目录启动器，实际 %q", got)
	}
	// 不可执行的同名文件不能当作启动器
	if err := os.Chmod(launcher, 0o644); err != nil {
		t.Fatal(err)
	}
	if got := launcherPath(exe); got != "" {
		t.Fatalf("不可执行的启动器应被忽略，实际 %q", got)
	}
}

func TestDisplayProblemDetectsMissingSession(t *testing.T) {
	t.Setenv("DISPLAY", "")
	t.Setenv("WAYLAND_DISPLAY", "")
	if msg := displayProblem(); msg == "" {
		t.Fatal("无 DISPLAY/WAYLAND_DISPLAY 时应返回原因")
	}
	t.Setenv("DISPLAY", ":0")
	if msg := displayProblem(); msg != "" {
		t.Fatalf("有 DISPLAY 时不应报错，实际 %q", msg)
	}
	t.Setenv("DISPLAY", "")
	t.Setenv("WAYLAND_DISPLAY", "wayland-0")
	if msg := displayProblem(); msg != "" {
		t.Fatalf("有 WAYLAND_DISPLAY 时不应报错，实际 %q", msg)
	}
}
