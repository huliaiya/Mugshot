package main

import (
	"log"
	"net"
	"os"
	"strconv"
	"strings"
	"time"
)

// sdNotify implements the tiny subset of the systemd sd_notify protocol that
// mugshot-server needs (READY=1, WATCHDOG=1, STOPPING=1) with zero external
// dependencies. When not running under systemd (no NOTIFY_SOCKET) it is a
// no-op, so the process can also run standalone.
func sdNotify(state string) {
	sock := os.Getenv("NOTIFY_SOCKET")
	if sock == "" {
		return
	}
	// abstract namespace socket
	if strings.HasPrefix(sock, "@") {
		sock = "\x00" + sock[1:]
	}
	conn, err := net.Dial("unixgram", sock)
	if err != nil {
		log.Printf("[sd_notify] 连接失败（忽略）: %v", err)
		return
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(2 * time.Second))
	_, _ = conn.Write([]byte(state))
}

// sdNotifyWatchdogLoop sends WATCHDOG=1 at half the watchdog interval
// configured via WATCHDOG_USEC (systemd WatchdogSec=). This lets systemd
// detect a hung (not just crashed) server and restart it.
func sdNotifyWatchdogLoop() {
	usec := os.Getenv("WATCHDOG_USEC")
	if usec == "" {
		return
	}
	n, err := strconv.ParseInt(usec, 10, 64)
	if err != nil || n <= 0 {
		return
	}
	interval := time.Duration(n) * time.Microsecond / 2
	if interval < time.Second {
		interval = time.Second
	}
	log.Printf("[sd_notify] watchdog 已启用，间隔 %s", interval)
	t := time.NewTicker(interval)
	defer t.Stop()
	for range t.C {
		sdNotify("WATCHDOG=1")
	}
}
