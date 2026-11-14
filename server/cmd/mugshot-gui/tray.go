package main

import (
	"runtime"

	"github.com/godbus/dbus/v5"
)

// trayAvailable 报告当前环境能否安全使用系统托盘。
//
// Linux 上的托盘走 DBus（StatusNotifier）。会话总线不可用时，
// fyne.io/systray 会在 nativeStart 里连不上总线而提前返回，
// 内部连接保持为 nil；随后应用退出时 systray 的 nativeEnd 仍会对这个
// 空连接调用 Close，直接触发空指针 panic。
//
// 这里在启用托盘前先真实连一次会话总线：连接成功时 godbus 会缓存该连接，
// systray 后续取到的是同一条已连通的连接，退出路径便不会 panic；
// 连不上则干脆不启用托盘，关闭窗口退回"关窗即退出"，窗口不会失联。
func trayAvailable() bool {
	if runtime.GOOS != "linux" {
		// 其他平台由系统原生实现托盘，不经过 DBus。
		return true
	}
	return dbusReachable()
}

// dbusReachable 判断会话总线是否真的可用。
//
// 先用私有连接探测：它不写入 godbus 的共享连接缓存，
// 因此不受其它代码是否已经连过总线的影响，结果稳定可预期。
// 探测通过后再取一次共享连接，把"已连通的连接"缓存下来，
// systray 稍后取到的就是它，退出时不会因为连接为空而崩溃。
func dbusReachable() bool {
	probe, err := dbus.SessionBusPrivate()
	if err != nil || probe == nil {
		return false
	}
	usable := probe.Connected()
	probe.Close()
	if !usable {
		return false
	}

	shared, err := dbus.SessionBus()
	if err != nil || shared == nil {
		return false
	}
	return shared.Connected()
}
