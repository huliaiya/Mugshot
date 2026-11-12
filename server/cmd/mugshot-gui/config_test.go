package main

import "testing"

// TestReplaceURLPort 验证设置页改端口后，接入信息页地址能跟着换端口。
func TestReplaceURLPort(t *testing.T) {
	cases := []struct {
		name   string
		raw    string
		listen string
		want   string
	}{
		{"本机回环", "http://127.0.0.1:8080", ":9090", "http://127.0.0.1:9090"},
		{"局域网地址", "http://192.168.1.10:8080", ":9090", "http://192.168.1.10:9090"},
		{"带具体主机", "http://127.0.0.1:8080", "127.0.0.1:9090", "http://127.0.0.1:9090"},
		{"https 保留协议", "https://mugshot.local:8443", ":9090", "https://mugshot.local:9090"},
		{"端口不变", "http://127.0.0.1:8080", ":8080", "http://127.0.0.1:8080"},
		{"监听地址非法", "http://127.0.0.1:8080", "not-a-host-port", "http://127.0.0.1:8080"},
		{"监听地址为空", "http://127.0.0.1:8080", "", "http://127.0.0.1:8080"},
		{"地址为空", "", ":9090", ""},
		{"地址非法", "::::", ":9090", "::::"},
	}
	for _, c := range cases {
		if got := replaceURLPort(c.raw, c.listen); got != c.want {
			t.Fatalf("%s: replaceURLPort(%q, %q) = %q，期望 %q", c.name, c.raw, c.listen, got, c.want)
		}
	}
}
