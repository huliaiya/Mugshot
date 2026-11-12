// Package main implements mugshot-server, the Linux server component of the
// Mugshot device status system. It receives status reports from Mugshot
// Android devices over WebSocket (or HTTP fallback), keeps a multi-device
// online state table, exposes a query API for the Mugshot TRSS-Yunzai plugin,
// and pushes `request_report` commands to devices for on-demand reporting.
package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"runtime"
	"strings"
	"sync/atomic"
	"syscall"
	"time"
)

// build info, overridable via -ldflags "-X main.version=... -X main.releaseTag=... -X main.commit=..."
var (
	version    = "dev"   // 软件版本号 = 构建时的 Git 短哈希
	releaseTag = "0.0.0" // 更新检查用的发布版本号（tag 构建注入，如 1.0.8）
	commit     = "unknown"
)

var startTime = time.Now()

func main() {
	configPath := flag.String("config", "", "path to JSON config file (default: ./config.json or $MUGSHOT_CONFIG)")
	checkUpdate := flag.Bool("check-update", false, "check GitHub Releases for a newer version and exit")
	doUpdate := flag.Bool("update", false, "download and apply the latest release, then exit (systemd Restart=always will bring the new binary up)")
	showVersion := flag.Bool("version", false, "print version and exit")
	flag.Parse()

	log.SetFlags(log.Ldate | log.Ltime | log.Lmicroseconds)
	log.Printf("[mugshot-server] version=%s release=%s commit=%s go=%s", version, releaseTag, commit, goVersion())

	if *showVersion {
		return
	}

	cfg, err := LoadConfig(*configPath)
	if err != nil {
		log.Fatalf("[config] 加载失败: %v", err)
	}
	log.Printf("[config] 监听=%s 数据目录=%s 上报限流=%d次/分钟 按需上报冷却=%dms 按需等待=%dms",
		cfg.GetListen(), cfg.GetDataDir(), cfg.GetReportRatePerMin(), cfg.GetFreshCooldownMs(), cfg.GetFreshTimeoutMs())

	if *checkUpdate || *doUpdate {
		runUpdateCLI(cfg, *doUpdate)
		return
	}

	store, err := OpenStore(cfg)
	if err != nil {
		log.Fatalf("[store] 初始化失败: %v", err)
	}

	hub := NewHub(cfg, store)
	api := NewAPI(cfg, store, hub)

	mux := http.NewServeMux()
	api.Register(mux)
	hub.RegisterRoutes(mux)

	// 监听器支持运行期换端口：设置页改监听地址后热生效，无需重启进程。
	rt := newHTTPRuntime(mux)
	if err := rt.Start(cfg.GetListen()); err != nil {
		log.Fatalf("[http] 监听 %s 失败: %v", cfg.GetListen(), err)
	}
	api.SetRelisten(rt.Rebind)

	// background jobs
	go store.FlushLoop()
	go store.PruneLoop()
	go hub.HeartbeatLoop()
	go UpdateCheckLoop(cfg)
	go sdNotifyWatchdogLoop()

	// sd_notify READY (no-op when not running under systemd with NotifyAccess)
	sdNotify("READY=1")

	log.Printf("[http] 开始监听 %s（设备 WS: /ws/device，插件 WS: /ws/plugin，API: /health /devices /status）", cfg.GetListen())

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)

	sig := <-sigCh
	log.Printf("[main] 收到信号 %s，开始优雅退出", sig)

	sdNotify("STOPPING=1")
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = rt.Shutdown(ctx)
	hub.Close()
	store.Close()
	log.Printf("[main] 已退出（systemd Restart=always 会自动拉起）")
}

func runUpdateCLI(cfg *Config, apply bool) {
	rel, err := CheckLatestReleaseForce(cfg)
	if err != nil {
		log.Fatalf("[update] 检查更新失败: %v", err)
	}
	if rel.Tag == "" {
		log.Fatalf("[update] 未获取到最新版本号")
	}
	log.Printf("[update] 当前发布版本 %s，最新版本 %s", releaseTag, rel.Tag)
	if !NewerThan(rel.Tag, releaseTag) {
		log.Printf("[update] 已是最新版本")
		return
	}
	if !apply {
		log.Printf("[update] 可运行 `mugshot-server --update` 下载并应用新版本")
		return
	}
	if err := SelfUpdate(cfg, rel); err != nil {
		log.Fatalf("[update] 自更新失败: %v", err)
	}
	log.Printf("[update] 已更新到 %s，进程即将退出，由 systemd Restart=always 拉起新版本", rel.Tag)
	time.Sleep(500 * time.Millisecond)
	os.Exit(0)
}

func goVersion() string {
	return strings.TrimPrefix(runtime.Version(), "go")
}

// uptimeSec returns server uptime in seconds (used by /health).
func uptimeSec() int64 { return int64(time.Since(startTime) / time.Second) }

var onlineDevices atomic.Int64

func formatBytes(n int64) string {
	const unit = 1024
	if n < unit {
		return fmt.Sprintf("%d B", n)
	}
	div, e := int64(unit), 0
	for m := n / unit; m >= unit; m /= unit {
		div *= unit
		e++
	}
	return fmt.Sprintf("%.2f %cB", float64(n)/float64(div), "KMGTPE"[e])
}
