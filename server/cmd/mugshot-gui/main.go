// mugshot-gui 是 Mugshot 服务端的桌面管理程序：
// 展示运行状态与设备列表，管理 Token、启停服务、查看最近 24 小时运行日志。
package main

import (
	"context"
	_ "embed"
	"encoding/json"
	"errors"
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
	"fyne.io/fyne/v2/dialog"
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
	return c.do(http.MethodGet, path, needAuth)
}

func (c *client) post(path string, needAuth bool) ([]byte, error) {
	return c.do(http.MethodPost, path, needAuth)
}

func (c *client) do(method, path string, needAuth bool) ([]byte, error) {
	req, err := http.NewRequest(method, strings.TrimRight(c.base, "/")+path, nil)
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

const tabOverview = "概览"
const tabRunLog = "运行日志"
const tabAccess = "接入信息"

type ui struct {
	win        fyne.Window
	statusDot  *widget.RichText
	statusLn   *widget.Label
	devSummary *widget.Label
	devList    *fyne.Container
	urlEntry   *widget.Entry
	tokenDev   *widget.Entry
	ctlResult  *widget.Label
	verLabel   *widget.Label
	logView    *widget.Entry
	logHint    *widget.Label
	logModeBtn *widget.Button
	tabs       *container.AppTabs

	mu           sync.Mutex
	conf         guiConf
	cli          *client
	quit         chan struct{}
	once         sync.Once
	lastStatusTx string
	lastDevSig   string
	logMode      string // "restart" 只看本次运行（默认） | "24h" 最近 24 小时
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
	w.Resize(fyne.NewSize(620, 720))
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
					u.ctlResult.SetText("未获取到配置，请在\"接入信息\"页手动填写地址与 Token")
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
	// ---- 概览页 ----
	u.statusDot = widget.NewRichText(&widget.TextSegment{Text: "● 检查中", Style: widget.RichTextStyle{Inline: true}})
	u.statusLn = widget.NewLabel("—")
	u.statusLn.Wrapping = fyne.TextWrapWord

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

	statusCard := widget.NewCard("服务状态", "", container.NewVBox(
		container.NewVBox(u.statusDot, u.statusLn),
		container.NewHBox(svcBtn("重启服务", "restart"), svcBtn("停止服务", "stop"), svcBtn("启动服务", "start")),
	))

	u.devSummary = widget.NewLabel("设备：—")
	u.devSummary.Wrapping = fyne.TextWrapWord
	u.devList = container.NewVBox()
	devicesCard := widget.NewCard("设备", "", container.NewVBox(u.devSummary, u.devList))

	overview := container.NewVScroll(container.NewVBox(statusCard, devicesCard))

	// ---- 运行日志页（页内展示，切换到本页即加载最新） ----
	u.logHint = widget.NewLabel("只看本次运行：显示服务最近一次启动以来的日志；切换到本页时自动加载最新内容。")
	u.logHint.Wrapping = fyne.TextWrapWord
	u.logMode = "restart"
	u.logModeBtn = widget.NewButton("查看最近 24 小时", u.toggleLogMode)
	logRefresh := widget.NewButton("刷新", func() { u.refreshRunLog() })
	u.logView = widget.NewMultiLineEntry()
	u.logView.Wrapping = fyne.TextWrapBreak
	u.logView.TextStyle = fyne.TextStyle{Monospace: true}
	u.logView.SetText("切换到本页时自动加载")
	logPage := container.NewBorder(
		container.NewVBox(
			widget.NewCard("运行日志", "", container.NewVBox(u.logHint, container.NewHBox(u.logModeBtn, logRefresh))),
		),
		nil, nil, nil,
		container.NewScroll(u.logView),
	)

	// ---- 接入信息页 ----
	note := widget.NewLabel("设备与插件共用连接地址，Token 即插件配置中的 plugin_token。")
	note.Wrapping = fyne.TextWrapWord
	note.TextStyle = fyne.TextStyle{Italic: true}

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

	verBtn := widget.NewButton("检查更新", func() { u.checkUpdate() })
	updBtn := widget.NewButton("立即更新", func() {
		if strings.TrimSpace(u.tokenDev.Text) == "" {
			u.verLabel.SetText("未配置 Token，无法触发更新")
			return
		}
		dialog.NewConfirm("立即更新",
			"将从 GitHub 下载最新版本并自动重启服务，期间服务会短暂中断。\n要继续吗？",
			func(ok bool) {
				if ok {
					go u.doUpdate()
				}
			}, u.win).Show()
	})

	tokenCard := widget.NewCard("接入信息", "", container.NewVBox(
		note,
		container.NewHBox(widget.NewLabel("地址"), container.NewGridWithColumns(1), u.urlEntry),
		container.NewHBox(widget.NewLabel("Token"), u.tokenDev, copyToken),
		saveBtn,
	))
	maintCard := widget.NewCard("更新", "", container.NewVBox(
		container.NewHBox(verBtn, updBtn),
		u.verLabel,
		u.ctlResult,
	))
	tokenPage := container.NewVScroll(container.NewVBox(tokenCard, maintCard))

	u.tabs = container.NewAppTabs(
		container.NewTabItem(tabOverview, overview),
		container.NewTabItem(tabRunLog, logPage),
		container.NewTabItem(tabAccess, tokenPage),
	)
	// 每次切到运行日志页都重新加载最新日志
	u.tabs.OnSelected = func(t *container.TabItem) {
		if t.Text == tabRunLog {
			u.refreshRunLog()
		}
	}
	return u.tabs
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
		tx := fmt.Sprintf("无法连接 %s（%v）", cli.base, err)
		fyne.Do(func() {
			u.statusDot.Segments[0] = &widget.TextSegment{Text: "● 离线", Style: dotStyle(false)}
			u.statusDot.Refresh()
			// 内容不变不重绘，避免轮询导致的界面抖动
			if u.statusLn.Text != tx {
				u.statusLn.SetText(tx)
			}
		})
		return
	}
	var h healthResp
	if json.Unmarshal(data, &h) != nil || !h.OK {
		return
	}
	tx := fmt.Sprintf("版本 %s · 已运行 %s · 在线设备 %d / %d",
		h.Version, fmtUptime(h.UptimeSec), h.DevicesOnline, h.DevicesTotal)
	if tx == u.lastStatusTx {
		return
	}
	fyne.Do(func() {
		u.statusDot.Segments[0] = &widget.TextSegment{Text: "● 运行中", Style: dotStyle(true)}
		u.statusDot.Refresh()
		u.statusLn.SetText(tx)
	})
	u.lastStatusTx = tx
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
	// 数据无变化时跳过整块重绘
	sig := deviceSig(rows)
	if sig == u.lastDevSig {
		return
	}
	u.lastDevSig = sig
	fyne.Do(func() {
		u.devSummary.SetText(fmt.Sprintf("共 %d 台设备", len(rows)))
		u.devList.Objects = nil
		for _, r := range rows {
			name := r.DeviceName
			if r.Model != "" {
				name += "（" + r.Model + "）"
			}
			line := widget.NewRichText(
				&widget.TextSegment{Text: "● ", Style: dotStyle(r.Online)},
				&widget.TextSegment{Text: fmt.Sprintf("%s · 最近上报 %s · 累计 %d 次",
					name, relTime(r.LastReportAt), r.ReportCount), Style: widget.RichTextStyle{Inline: true}},
			)
			line.Wrapping = fyne.TextWrapWord
			u.devList.Add(line)
		}
		u.devList.Refresh()
	})
}

func deviceSig(rows []deviceRow) string {
	var b strings.Builder
	fmt.Fprintf(&b, "n=%d;", len(rows))
	for _, r := range rows {
		fmt.Fprintf(&b, "%s|%s|%t|%d|%d;", r.DeviceID, r.DeviceName, r.Online, r.LastReportAt, r.ReportCount)
	}
	return b.String()
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
		u.verLabel.SetText(fmt.Sprintf("当前 %s，最新发布 %s，点击\"立即更新\"升级", v.Version, v.Latest))
	} else {
		u.verLabel.SetText(fmt.Sprintf("当前 %s 已是最新", v.Version))
	}
}

// doUpdate 走服务端 POST /admin/update 自更新链路：
// 服务端后台下载新版本替换自身二进制后退出，systemd Restart=always 拉起新版本。
func (u *ui) doUpdate() {
	cli := u.cli
	fyne.Do(func() { u.verLabel.SetText("正在请求更新……") })

	data, err := cli.post("/admin/update", true)
	if err != nil {
		fyne.Do(func() { u.verLabel.SetText("更新请求失败：" + err.Error()) })
		return
	}
	var resp struct {
		OK      bool   `json:"ok"`
		Updated bool   `json:"updated"`
		Current string `json:"current"`
		Note    string `json:"note"`
	}
	if json.Unmarshal(data, &resp) != nil || !resp.OK {
		note := resp.Note
		if note == "" {
			note = "响应格式异常"
		}
		fyne.Do(func() { u.verLabel.SetText("更新失败：" + note) })
		return
	}
	if !resp.Updated {
		fyne.Do(func() { u.verLabel.SetText(resp.Note) })
		return
	}
	fyne.Do(func() { u.verLabel.SetText("服务端正在后台下载新版本并重启，请稍候……") })

	// 轮询 /health：服务短暂离线（重启中）后应以新版本回来
	for i := 0; i < 30; i++ {
		time.Sleep(2 * time.Second)
		data, err := cli.get("/health", false)
		if err != nil {
			continue
		}
		var h healthResp
		if json.Unmarshal(data, &h) != nil || !h.OK {
			continue
		}
		// 版本变化即成功；连续 8 秒仍是旧版本则视为已是该版本（如重新构建）
		if h.Version != resp.Current || i >= 4 {
			ver := h.Version
			fyne.Do(func() {
				u.verLabel.SetText("更新完成，服务已恢复，当前版本 " + ver)
				u.refreshHealth()
			})
			return
		}
	}
	fyne.Do(func() { u.verLabel.SetText("等待服务重启超时，请稍后点击\"检查更新\"确认") })
}

// toggleLogMode 在"只看本次运行"与"最近 24 小时"两种视图间切换。
func (u *ui) toggleLogMode() {
	if u.logMode == "restart" {
		u.logMode = "24h"
		u.logModeBtn.SetText("只看本次运行")
		u.logHint.SetText("最近 24 小时：跨重启查看历史日志（按日志文件原样展示，重启不会清除文件）。")
	} else {
		u.logMode = "restart"
		u.logModeBtn.SetText("查看最近 24 小时")
		u.logHint.SetText("只看本次运行：显示服务最近一次启动以来的日志；切换到本页时自动加载最新内容。")
	}
	u.refreshRunLog()
}

// refreshRunLog 重新加载服务日志（journalctl）。
func (u *ui) refreshRunLog() {
	mode := u.logMode
	u.logView.SetText("正在加载日志……")
	go func() {
		text, err := serverLogs(mode)
		fyne.Do(func() {
			if err != nil {
				u.logView.SetText("读取日志失败：" + err.Error() +
					"\n\n提示：把当前用户加入日志组后无需授权即可查看：\n  sudo usermod -aG systemd-journal $USER\n然后注销重新登录。")
			} else if strings.TrimSpace(text) == "" {
				u.logView.SetText("暂无日志")
			} else {
				u.logView.SetText(text)
			}
		})
	}()
}

// serviceStartSince 返回服务最近一次启动的时间字符串（供 journalctl --since）。
func serviceStartSince() (string, bool) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	out, err := exec.CommandContext(ctx, "systemctl", "show", "-p", "ActiveEnterTimestamp", "--value", "mugshot-server").Output()
	s := strings.TrimSpace(string(out))
	if err != nil || s == "" || s == "n/a" {
		return "", false
	}
	return s, true
}

// serverLogs 优先直接读 journal（用户在 systemd-journal/adm 组内即可），
// 无权限时回退 pkexec 授权读取；两者都带超时，避免界面卡在授权等待。
// mode = "restart" 只取服务最近一次启动之后的日志；"24h" 取最近 24 小时。
func serverLogs(mode string) (string, error) {
	args := []string{"-u", "mugshot-server", "--no-pager", "-n", "3000"}
	if mode == "restart" {
		since, ok := serviceStartSince()
		if !ok {
			return "", errors.New("无法获取服务启动时间（systemctl 不可用？），可切换到\"最近 24 小时\"视图")
		}
		args = append(args, "--since", since)
	} else {
		args = append(args, "--since", "-24 hours")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	if out, err := exec.CommandContext(ctx, "journalctl", args...).Output(); err == nil {
		return string(out), nil
	}
	ctx2, cancel2 := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel2()
	out, err := exec.CommandContext(ctx2, "pkexec", append([]string{"journalctl"}, args...)...).Output()
	if err != nil {
		return "", fmt.Errorf("journalctl 不可用（%v）", err)
	}
	return string(out), nil
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
