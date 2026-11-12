package main

import (
	"sync"
	"time"
)

// authFailLimiter 对鉴权失败日志做限流。
// 客户端持有过期 Token 时会持续重试（例如插件与 GUI 每几秒轮询一次 /devices），
// 不限流会持续刷屏，把 journal 与 GUI 运行日志页冲垮。
type authFailLimiter struct {
	mu   sync.Mutex
	last map[string]*failEntry
}

// failEntry 记录一个统计窗口：窗口起点与被抑制的失败次数。
type failEntry struct {
	windowStart time.Time
	suppressed  int
}

func newAuthFailLimiter() *authFailLimiter {
	return &authFailLimiter{last: map[string]*failEntry{}}
}

// authFailLog 是全进程共用的鉴权失败日志限流器。
// HTTP 接口与 WebSocket 握手共用同一个计数器，
// 同一个客户端在两个入口上的失败会汇总成一条日志，不会互相绕过限流。
var authFailLog = newAuthFailLimiter()

// shouldLog 返回 (是否记录, 本次统计窗口内的失败次数)。
// 同一 key 的首次失败立即记录，之后一分钟内的重复失败只计数，
// 窗口结束后的下一次失败会带着这段时间的失败总数一起输出。
func (l *authFailLimiter) shouldLog(key string) (bool, int) {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := time.Now()
	e, ok := l.last[key]
	if !ok {
		l.last[key] = &failEntry{windowStart: now}
		return true, 1
	}
	if now.Sub(e.windowStart) < time.Minute {
		e.suppressed++
		return false, e.suppressed + 1
	}
	count := e.suppressed + 1
	e.windowStart = now
	e.suppressed = 0
	l.cleanup(now)
	return true, count
}

// cleanup 清理长时间没有再出现的 key，避免内存里堆积无效条目。
func (l *authFailLimiter) cleanup(now time.Time) {
	for k, v := range l.last {
		if now.Sub(v.windowStart) > 10*time.Minute {
			delete(l.last, k)
		}
	}
}
