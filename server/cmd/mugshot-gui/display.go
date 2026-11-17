package main

import (
	"os"
	"runtime"
	"strings"
)

// displayProblem 检测当前会话是否具备显示 GUI 的基本条件。
// 返回空串表示可以继续启动；否则返回可直接展示给用户的中文原因。
// 只做环境变量层面的快速判断：驱动/GL 库层面的失败交给启动器重试软件渲染并写日志。
func displayProblem() string {
	if runtime.GOOS != "linux" {
		return ""
	}
	wayland := strings.TrimSpace(os.Getenv("WAYLAND_DISPLAY"))
	display := strings.TrimSpace(os.Getenv("DISPLAY"))
	if wayland != "" || display != "" {
		return ""
	}
	// 以 root 通过 su 运行、或 SSH 登录后直接执行时，环境里没有 DISPLAY，
	// 这是最常见的"双击图标无反应/终端一跑就退"原因。
	return "未检测到图形会话（DISPLAY 与 WAYLAND_DISPLAY 均为空）。\n" +
		"请在桌面环境中运行，或使用 mugshot-ctl 在终端管理服务端。"
}
