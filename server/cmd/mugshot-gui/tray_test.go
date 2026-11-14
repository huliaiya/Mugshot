package main

import (
	"testing"

	"github.com/godbus/dbus/v5"
)

func TestDBusReachableWithBrokenAddress(t *testing.T) {
	// 指向一个不存在的 socket，模拟 DBus 会话总线失效。
	t.Setenv("DBUS_SESSION_BUS_ADDRESS", "unix:path=/tmp/opencode/definitely-missing-dbus-socket")

	if dbusReachable() {
		t.Fatal("会话总线不可达时 dbusReachable 应返回 false")
	}
}

func TestDBusReachableWithRealBus(t *testing.T) {
	conn, err := dbus.SessionBusPrivate()
	if err != nil || conn == nil {
		t.Skip("当前环境没有 DBus 会话总线，跳过")
	}
	conn.Close()

	if !dbusReachable() {
		t.Fatal("会话总线可达时 dbusReachable 应返回 true")
	}
}

func TestTrayAvailableNeverPanics(t *testing.T) {
	// 无论环境如何，trayAvailable 都只返回布尔值，不允许 panic。
	t.Setenv("DBUS_SESSION_BUS_ADDRESS", "unix:path=/tmp/opencode/definitely-missing-dbus-socket")
	if trayAvailable() {
		t.Fatal("会话总线不可达时 trayAvailable 应返回 false")
	}
}
