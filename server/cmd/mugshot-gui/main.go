// mugshot-gui 是 Mugshot 服务端的桌面管理程序：
// 展示运行状态与设备列表，管理 Token、启停服务、查看日志。
package main

import (
	_ "embed"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os/exec"
	"strings"
	"sync"
	"time"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/app"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/theme"
	"fyne.io/fyne/v2/widget"
)

//go:embed icon.svg
var iconSVG []byte

type healthResp struct {
	OK            bool   `json:"ok"`
	Version       string `json:"version"`
	Commit        string `json:"commit"`
	UptimeSec     int64  `json:"uptime_sec"`
	DevicesTotal  int    `json:"devices_total"`
	DevicesOnline int    `json:"devices_online"`
}

type deviceRow struct {
	DeviceID     string `json:"device_id"`
	DeviceName   string `json:"device_name"`
	Model        string `json:"model"`
	Online       bool   `json:"online"`
	LastReportAt int64  `json:"last_report_at"`
	ReportCount  int64  `json:"report_count"`
}

type devicesResp struct {
	OK      bool        `json:"ok"`
	Devices []deviceRow `json:"devices"`
}

type versionResp struct {
	Version string `json:"version"`
	Latest  string `json:"latest"`
	Update  bool   `json:"update"`
}

type client struct {
	base string
	tok  string
	hc   *http.Client
}

func (c *client) get(path string, needAuth bool) ([]byte, error) {
	req, err := http.NewRequest(http.MethodGet, strings.TrimRight(c.base, "/")+path, nil)
	if err != nil {
		return nil, err
	}
	if needAuth && c.tok != "" {
		req.Header.Set("Authorization", "Bearer "+c.tok)
	}
	resp, err := c.hc.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	return io.ReadAll(resp.Body)
}

type ui struct {
	win      fyne.Window
	statusDot *widget.RichText
	statusLn  *widget.Label
	devLine   *widget.Label
	devList   *fyne.Container
	tokenDev  *widget.Entry
	tokenPlug *widget.Entry
	urlEntry  *widget.Entry
	ctlResult *widget.Label
	verLabel  *widget.Label

	mu   sync.Mutex
	conf guiConf
	cli  *client
	quit chan struct{}
	once sync.Once
}

func main() {
	a := app.NewWithID("com.mugshot.server")
	a.SetIcon(fyne.NewStaticResource("mugshot.svg", iconSVG))

	u := &ui{quit: make(chan struct{})}
	if c, ok := loadConf(); ok {
		u.conf = c
	}
	u.cli = u.newClient()

	w := a.NewWindow("Mugshot 服务端")
	w.Resize(fyne.NewSize(580, 700))
	u.win = w
	w.SetContent(u.build())

	// 首次启动无配置时，尝试通过 polkit 授权读取服务端配置。
	if u.conf.ServerURL == "" {
		go func() {
			c, err := fetchSystemConf()
			fyne.Do(func() {
				if err == nil {
					u.applyConf(c)
					u.ctlResult.SetText("已从系统配置读取连接信息")
				} else {
					u.ctlResult.SetText("未获取到配置，请在下方手动填写地址与 Token")
				}
			})
		}()
	}

	u.startPolling()
	w.SetCloseIntercept(func() {
		u.once.Do(func() { close(u.quit) })
		w.Close()
	})
	w.ShowAndRun()
}

func (u *ui) newClient() *client {
	u.mu.Lock()
	defer u.mu.Unlock()
	return &client{base: u.conf.ServerURL, tok: u.conf.PluginToken, hc: &http.Client{Timeout: 4 * time.Second}}
}

func (u *ui) applyConf(c guiConf) {
	u.mu.Lock()
	u.conf = c
	u.cli = &client{base: c.ServerURL, tok: c.PluginToken, hc: &http.Client{Timeout: 4 * time.Second}}
	u.mu.Unlock()
	u.urlEntry.SetText(c.ServerURL)
	u.tokenDev.SetText(c.PluginToken)
	_ = saveConf(c)
}

func dotStyle(on bool) widget.RichTextStyle {
	if on {
		return widget.RichTextStyle{ColorName: theme.ColorNameSuccess, Inline: true}
	}
	return widget.RichTextStyle{ColorName: theme.ColorNameError, Inline: true}
}

func (u *ui) build() fyne.CanvasObject {
	u.statusDot = widget.NewRichText(&widget.TextSegment{Text: "● 检查中", Style: widget.RichTextStyle{Inline: true}})
	u.statusLn = widget.NewLabel("—")
	u.statusLn.Wrapping = fyne.TextWrapWord
	u.devLine = widget.NewLabel("—")
	u.devList = container.NewVBox()

	tokenNote := widget.NewLabel("设备与插件共用连接地址，Token 即插件配置中的 plugin_token。")
	tokenNote.Wrapping = fyne.TextWrapWord
	tokenNote.TextStyle = fyne.TextStyle{Italic: true}

	u.urlEntry = widget.NewEntry()
	u.urlEntry.SetPlaceHolder("http://127.0.0.1:8080")
	u.tokenDev = widget.NewEntry()
	u.tokenDev.SetPlaceHolder("粘贴服务端配置里的 plugin_token")

	saveBtn := widget.NewButton("保存连接信息", func() {
		c := guiConf{ServerURL: strings.TrimSpace(u.urlEntry.Text), PluginToken: strings.TrimSpace(u.tokenDev.Text)}
		if c.ServerURL == "" {
			u.ctlResult.SetText("地址不能为空")
			return
		}
		u.applyConf(c)
		u.ctlResult.SetText("连接信息已保存")
	})

	copyToken := widget.NewButton("复制 Token", func() {
		fyne.CurrentApp().Clipboard().SetContent(strings.TrimSpace(u.tokenDev.Text))
	})

	u.ctlResult = widget.NewLabel("")
	u.ctlResult.Wrapping = fyne.TextWrapWord
	u.verLabel = widget.NewLabel("")

	svcBtn := func(text, action string) *widget.Button {
		return widget.NewButton(text, func() {
			u.ctlResult.SetText("正在" + text + "……（如弹出授权窗口请确认）")
			go func() {
				out, err := exec.Command("pkexec", "systemctl", action, "mugshot-server").CombinedOutput()
				msg := text + "完成"
				if err != nil {
					msg = text + "失败：" + strings.TrimSpace(string(out))
				}
				fyne.Do(func() { u.ctlResult.SetText(msg) })
			}()
		})
	}

	logBtn := widget.NewButton("查看服务日志", func() { u.showLogs() })
	verBtn := widget.NewButton("检查更新", func() { u.checkUpdate() })

	header := widget.NewCard("服务状态", "", container.NewVBox(
		container.NewHBox(u.statusDot, u.statusLn),
		u.devLine,
		container.NewHBox(svcBtn("重启服务", "restart"), svcBtn("停止服务", "stop"), svcBtn("启动服务", "start")),
	))

	tokens := widget.NewCard("接入信息", "", container.NewVBox(
		tokenNote,
		container.NewHBox(widget.NewLabel("地址"), u.urlEntry),
		container.NewHBox(widget.NewLabel("Token"), u.tokenDev, copyToken),
		saveBtn,
	))

	devices := widget.NewCard("设备", "", container.NewVBox(u.devLine, u.devList))

	ctl := widget.NewCard("维护", "", container.NewVBox(
		container.NewHBox(logBtn, verBtn),
		u.verLabel,
		u.ctlResult,
	))

	return container.NewVScroll(container.NewVBox(header, devices, tokens, ctl))
}

func (u *ui) startPolling() {
	go pollLoop(u.quit, 2*time.Second, u.refreshHealth)
	go pollLoop(u.quit, 3*time.Second, u.refreshDevices)
}

func pollLoop(stop <-chan struct{}, interval time.Duration, fn func()) {
	fn()
	t := time.NewTicker(interval)
	defer t.Stop()
	for {
		select {
		case <-stop:
			return
		case <-t.C:
			fn()
		}
	}
}

func (u *ui) refreshHealth() {
	cli := u.cli
	data, err := cli.get("/health", false)
	if err != nil {
		fyne.Do(func() {
			u.statusDot.Segments[0] = &widget.TextSegment{Text: "● 离线", Style: dotStyle(false)}
			u.statusDot.Refresh()
			u.statusLn.SetText(fmt.Sprintf("无法连接 %s（%v）", cli.base, err))
		})
		return
	}
	var h healthResp
	if json.Unmarshal(data, &h) != nil || !h.OK {
		return
	}
	fyne.Do(func() {
		u.statusDot.Segments[0] = &widget.TextSegment{Text: "● 运行中", Style: dotStyle(true)}
		u.statusDot.Refresh()
		u.statusLn.SetText(fmt.Sprintf("版本 %s · 已运行 %s · 在线设备 %d / %d",
			h.Version, fmtUptime(h.UptimeSec), h.DevicesOnline, h.DevicesTotal))
	})
}

func (u *ui) refreshDevices() {
	cli := u.cli
	data, err := cli.get("/devices", true)
	if err != nil {
		return
	}
	var d devicesResp
	if json.Unmarshal(data, &d) != nil || !d.OK {
		return
	}
	rows := d.Devices
	fyne.Do(func() {
		u.devList.Objects = nil
		for _, r := range rows {
			name := r.DeviceName
			if r.Model != "" {
				name += "（" + r.Model + "）"
			}
			u.devList.Add(container.NewHBox(
				widget.NewRichText(&widget.TextSegment{Text: "●", Style: dotStyle(r.Online)}),
				widget.NewLabel(fmt.Sprintf("%s · 最近上报 %s · 累计 %d 次",
					name, relTime(r.LastReportAt), r.ReportCount)),
			))
		}
		u.devList.Refresh()
	})
}

func (u *ui) checkUpdate() {
	u.verLabel.SetText("正在检查更新……")
	data, err := u.cli.get("/version", false)
	if err != nil {
		u.verLabel.SetText("检查失败：" + err.Error())
		return
	}
	var v versionResp
	if json.Unmarshal(data, &v) != nil {
		u.verLabel.SetText("检查失败：响应格式异常")
		return
	}
	if v.Update {
		u.verLabel.SetText(fmt.Sprintf("当前 %s，最新发布 %s，可执行 mugshot-server --update", v.Version, v.Latest))
	} else {
		u.verLabel.SetText(fmt.Sprintf("当前 %s 已是最新", v.Version))
	}
}

func (u *ui) showLogs() {
	entry := widget.NewMultiLineEntry()
	entry.Wrapping = fyne.TextWrapBreak
	logWin := fyne.CurrentApp().NewWindow("mugshot-server 日志")
	logWin.Resize(fyne.NewSize(720, 520))
	logWin.SetContent(container.NewScroll(entry))
	logWin.Show()

	go func() {
		out, err := exec.Command("journalctl", "-u", "mugshot-server", "-n", "300", "--no-pager").Output()
		if err != nil || len(out) == 0 {
			out, err = exec.Command("pkexec", "journalctl", "-u", "mugshot-server", "-n", "300", "--no-pager").Output()
		}
		text := strings.TrimRight(string(out), "\n")
		if text == "" {
			if err != nil {
				text = "无法读取日志：" + err.Error()
			} else {
				text = "暂无日志"
			}
		}
		fyne.Do(func() { entry.SetText(text) })
	}()
}

func fmtUptime(sec int64) string {
	d := sec / 86400
	h := sec % 86400 / 3600
	m := sec % 3600 / 60
	switch {
	case d > 0:
		return fmt.Sprintf("%d天%d小时", d, h)
	case h > 0:
		return fmt.Sprintf("%d小时%d分", h, m)
	default:
		return fmt.Sprintf("%d分钟", m)
	}
}

func relTime(ms int64) string {
	if ms <= 0 {
		return "从未"
	}
	d := time.Duration(time.Now().UnixMilli()-ms) * time.Millisecond
	switch {
	case d < time.Minute:
		return fmt.Sprintf("%d 秒前", int(d.Seconds()))
	case d < time.Hour:
		return fmt.Sprintf("%d 分钟前", int(d.Minutes()))
	default:
		return fmt.Sprintf("%d 小时前", int(d.Hours()))
	}
}
