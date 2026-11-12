package main

import (
	"context"
	"errors"
	"log"
	"net"
	"net/http"
	"sync"
	"time"
)

// httpRuntime 管理 HTTP 监听器，支持运行期换端口。
// 设置页改"监听地址"时先绑定新端口，成功后再关旧端口，
// 老连接不受影响，端口切换不需要重启进程。
type httpRuntime struct {
	srv *http.Server

	mu      sync.Mutex
	ln      net.Listener
	addr    string // 实际生效的监听地址（端口为 0 时是内核分配的真实端口）
	cfgAddr string // 配置里写的监听地址
	stopped bool
}

func newHTTPRuntime(handler http.Handler) *httpRuntime {
	return &httpRuntime{
		srv: &http.Server{
			Handler:           handler,
			ReadHeaderTimeout: 10 * time.Second,
		},
	}
}

// Start 绑定首个监听地址并开始服务。
func (r *httpRuntime) Start(addr string) error {
	return r.rebind(addr)
}

// Rebind 换到新的监听地址：新端口绑定失败时保留旧端口继续服务。
func (r *httpRuntime) Rebind(addr string) error {
	return r.rebind(addr)
}

func (r *httpRuntime) rebind(addr string) error {
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return err
	}

	r.mu.Lock()
	if r.stopped {
		r.mu.Unlock()
		_ = ln.Close()
		return errors.New("服务已停止，无法切换监听地址")
	}
	old := r.ln
	r.ln = ln
	r.addr = ln.Addr().String()
	r.cfgAddr = addr
	r.mu.Unlock()

	go func() {
		if err := r.srv.Serve(ln); err != nil &&
			!errors.Is(err, http.ErrServerClosed) && !errors.Is(err, net.ErrClosed) {
			log.Printf("[http] 监听 %s 异常退出: %v", addr, err)
		}
	}()

	if old != nil {
		_ = old.Close()
	}
	log.Printf("[http] 监听地址已切换到 %s", addr)
	return nil
}

// Addr 返回当前生效的监听地址。
func (r *httpRuntime) Addr() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.addr
}

// ConfigAddr 返回配置里写的监听地址。
func (r *httpRuntime) ConfigAddr() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.cfgAddr
}

// Shutdown 优雅关闭所有监听器与在途请求。
func (r *httpRuntime) Shutdown(ctx context.Context) error {
	r.mu.Lock()
	r.stopped = true
	r.mu.Unlock()
	return r.srv.Shutdown(ctx)
}
