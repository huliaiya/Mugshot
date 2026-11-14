package main

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func reqWithPeerAndXff(peer, xff string) *http.Request {
	r := httptest.NewRequest(http.MethodGet, "http://x/health", nil)
	r.RemoteAddr = peer
	if xff != "" {
		r.Header.Set("X-Forwarded-For", xff)
	}
	return r
}

func TestRemoteIPXFFOnlyWhenLoopback(t *testing.T) {
	cases := []struct {
		name string
		peer string
		xff  string
		want string
	}{
		// 直连 peer 非回环（公网/代理跨机）：不信任 XFF，返回 peer
		{"peer public no xff", "203.0.113.7:51234", "", "203.0.113.7"},
		{"peer public spoofed xff", "203.0.113.7:51234", "127.0.0.1", "203.0.113.7"},
		// 直连 peer 回环（同机代理转发）：信任 XFF 最左端
		{"peer loopback xff chain", "127.0.0.1:41000", "10.1.2.3, 192.168.0.9", "10.1.2.3"},
		{"peer loopback no xff", "127.0.0.1:41000", "", "127.0.0.1"},
		{"peer ::1 xff", "[::1]:41000", "10.0.0.5", "10.0.0.5"},
		// 无端口
		{"peer no port public", "203.0.113.7", "", "203.0.113.7"},
	}
	for _, c := range cases {
		got := remoteIP(reqWithPeerAndXff(c.peer, c.xff))
		if got != c.want {
			t.Errorf("%s: got %q want %q", c.name, got, c.want)
		}
	}
}

func TestIsLoopbackHost(t *testing.T) {
	cases := map[string]bool{
		"127.0.0.1": true,
		"::1":       true,
		"127.5.6.7": true,
		"0.0.0.0":   false,
		"203.0.113.7": false,
		"":            false,
		"notanip":   false,
	}
	for host, want := range cases {
		if got := isLoopbackHost(host); got != want {
			t.Errorf("isLoopbackHost(%q)=%v want %v", host, got, want)
		}
	}
}
