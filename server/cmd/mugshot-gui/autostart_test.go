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
