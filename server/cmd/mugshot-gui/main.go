// mugshot-gui 是 Mugshot 服务端的桌面管理程序：
// 展示运行状态与设备列表，管理 Token、启停服务、查看最近 24 小时运行日志。
package main

import (
	"bytes"
	"context"
	"crypto/rand"
	_ "embed"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"strconv"
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
	hc   *http.Client
	// refresh 在服务端返回 401 时被调用：尝试从本机服务端重新取得统一 Token。
	// 服务端换过 Token 后，本地缓存的旧值会让所有鉴权请求失败，
	// 没有这条自愈路径时界面会永久卡在 401，连"生成新 Token"都点不动。
	refresh func() (string, error)

	mu  sync.Mutex
	tok string
}

func (c *client) token() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.tok
}

func (c *client) setToken(tok string) {
	c.mu.Lock()
	c.tok = tok
	c.mu.Unlock()
}

func (c *client) get(path string, needAuth bool) ([]byte, error) {
	return c.do(http.MethodGet, path, needAuth)
}

func (c *client) post(path string, needAuth bool) ([]byte, error) {
	return c.do(http.MethodPost, path, needAuth)
}

// postJSON 发送 JSON 请求体并读取响应（鉴权同 do）。
func (c *client) postJSON(path string, body any) ([]byte, error) {
	data, err := json.Marshal(body)
	if err != nil {
		return nil, err
	}
	return c.doWithBody(http.MethodPost, path, true, data)
}

func (c *client) doWithBody(method, path string, needAuth bool, body []byte) ([]byte, error) {
	out, code, err := c.raw(method, path, needAuth, body)
	if err == nil {
		return out, nil
	}
	newTok, rerr := c.recoverToken(needAuth, code)
	if newTok == "" {
		if rerr != nil {
			return nil, fmt.Errorf("%v（%v）", err, rerr)
		}
		return nil, err
	}
	c.setToken(newTok)
	out, _, err = c.raw(method, path, needAuth, body)
	if err != nil {
		return nil, err
	}
	return out, nil
}

// recoverToken 在鉴权失败时尝试重新取得 Token，成功返回新 Token。
func (c *client) recoverToken(needAuth bool, code int) (string, error) {
	if !needAuth || code != http.StatusUnauthorized || c.refresh == nil {
		return "", nil
	}
	tok, err := c.refresh()
	if err != nil || tok == "" {
		if err == nil {
			err = errors.New("未取得 Token")
		}
		return "", fmt.Errorf("Token 已失效，且无法自动同步：%w", err)
	}
	return tok, nil
}

// httpError 把服务端返回的 JSON 错误信息带出来，便于界面直接显示失败原因。
func httpError(code int, body []byte) error {
	msg := strings.TrimSpace(string(body))
	var payload struct {
		Error string `json:"error"`
	}
	if json.Unmarshal(body, &payload) == nil && payload.Error != "" {
		msg = payload.Error
	}
	if len(msg) > 160 {
		msg = msg[:160] + "…"
	}
	return fmt.Errorf("HTTP %d: %s", code, msg)
}

func (c *client) do(method, path string, needAuth bool) ([]byte, error) {
	return c.doWithBody(method, path, needAuth, nil)
}

func (c *client) raw(method, path string, needAuth bool, body []byte) ([]byte, int, error) {
	var reader io.Reader
	if body != nil {
		reader = bytes.NewReader(body)
	}
	req, err := http.NewRequest(method, strings.TrimRight(c.base, "/")+path, reader)
	if err != nil {
		return nil, 0, err
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if needAuth {
		if tok := c.token(); tok != "" {
			req.Header.Set("Authorization", "Bearer "+tok)
		}
	}
	resp, err := c.hc.Do(req)
	if err != nil {
		return nil, 0, err
	}
	defer resp.Body.Close()
	out, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, resp.StatusCode, err
	}
	if resp.StatusCode != http.StatusOK {
		return nil, resp.StatusCode, httpError(resp.StatusCode, out)
	}
	return out, resp.StatusCode, nil
}

const tabOverview = "概览"
const tabRunLog = "运行日志"
const tabAccess = "接入信息"
const tabSettings = "设置"

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

	// 设置页控件
	setListen    *widget.Entry
	setToken     *widget.Entry
	setRate      *widget.Entry
	setRetention *widget.Entry
	setOnline    *widget.Entry
	setMirror    *widget.Entry
	setAutoChk   *widget.Check
	setResult    *widget.Label
	setSaveBtn   *widget.Button
	setLoaded    bool

	mu           sync.Mutex
	conf         guiConf
	cli          *client
	tokSync      tokenSync
	quit         chan struct{}
	once         sync.Once
	lastStatusTx string
	lastDevSig   string
	logMode      string // "restart" 只看本次运行（默认） | "24h" 最近 24 小时
}

func main() {
	// 触控终端等设备的 EDID 常虚标 DPI，Fyne 自动缩放会把界面放得过大；
	// 默认固定 1 倍缩放，确有放大需求的用户可用 FYNE_SCALE 显式设置。
	if os.Getenv("FYNE_DISABLE_DPI_DETECTION") == "" {
		os.Setenv("FYNE_DISABLE_DPI_DETECTION", "true")
	}
	if os.Getenv("FYNE_SCALE") == "" {
		os.Setenv("FYNE_SCALE", "1")
	}
	a := app.NewWithID("com.mugshot.server")
	a.SetIcon(fyne.NewStaticResource("mugshot.svg", iconSVG))

	u := &ui{quit: make(chan struct{})}
	if c, ok := loadConf(); ok {
		u.conf = c
	}
	if u.conf.ServerURL == "" {
		// 无配置时默认连接本机服务，保证首次启动界面可用
		u.conf.ServerURL = "http://127.0.0.1:8080"
	}
	u.cli = u.newClient()

	w := a.NewWindow("Mugshot 服务端")
	w.Resize(fyne.NewSize(620, 720))
	u.win = w
	w.SetContent(u.build())
	// 已有连接配置时回填显示，方便查看与复制
	u.urlEntry.SetText(u.conf.ServerURL)
	if u.conf.PluginToken != "" {
		u.tokenDev.SetText(u.conf.PluginToken)
	}

	// 与本机服务端同机时自动读取统一 Token。
	// 服务端换过 Token 后本地缓存的旧值会让所有鉴权请求失败，
	// 启动时重读一次即可自动恢复，不必再让用户手工复制。
	go func() {
		_, err := u.refreshTokenForClient()
		fyne.Do(func() {
			if err != nil {
				if u.pluginToken() == "" {
					u.ctlResult.SetText("未自动获取到 Token（仅限与本机服务端同机），请在\"接入信息\"页手动填写")
				}
				return
			}
			u.ctlResult.SetText("已自动读取本机服务 Token")
		})
	}()

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
	return &client{
		base:    u.conf.ServerURL,
		tok:     u.conf.PluginToken,
		hc:      &http.Client{Timeout: 4 * time.Second},
		refresh: u.refreshTokenForClient,
	}
}

// client 读取当前客户端。cli 会被轮换（Token 同步、配置变更），
// 读取时统一加锁，避免轮询线程与界面线程并发读写。
func (u *ui) client() *client {
	u.mu.Lock()
	defer u.mu.Unlock()
	return u.cli
}

// pluginToken 读取当前保存的 Token，供界面线程判断是否已拿到令牌。
func (u *ui) pluginToken() string {
	u.mu.Lock()
	defer u.mu.Unlock()
	return u.conf.PluginToken
}

// serverURL 读取当前保存的服务端地址。
func (u *ui) serverURL() string {
	u.mu.Lock()
	defer u.mu.Unlock()
	return u.conf.ServerURL
}

func (u *ui) setClient(c *client) {
	u.mu.Lock()
	u.cli = c
	u.mu.Unlock()
}

func (u *ui) applyConf(c guiConf) {
	u.mu.Lock()
	u.conf = c
	u.mu.Unlock()
	u.setClient(u.newClient())
	u.urlEntry.SetText(c.ServerURL)
	u.tokenDev.SetText(c.PluginToken)
	_ = saveConf(c)
}

// applyToken 保存新 Token、切换客户端并落盘。
// 这一步放在调用线程完成：界面渲染在无 GPU 的设备上可能耗时数秒，
// Token 自愈的落盘不能等界面线程，否则重启后又会拿旧 Token 回来。
func (u *ui) applyToken(tok string) {
	u.mu.Lock()
	u.conf.PluginToken = tok
	c := u.conf
	u.mu.Unlock()
	u.setClient(u.newClient())
	_ = saveConf(c)
}

// updateToken 服务端 Token 变化（轮换/修改）后同步本地状态与连接，只能在界面线程调用。
func (u *ui) updateToken(tok string) {
	u.applyToken(tok)
	u.tokenDev.SetText(tok)
	if u.setToken != nil {
		u.setToken.SetText(tok)
	}
}

// tokenSync 记录最近一次 Token 自动同步的结果。
// 服务端不可达时每轮轮询都会触发 401 自愈，冷却几秒可避免重复请求。
type tokenSync struct {
	mu      sync.Mutex
	lastTry time.Time
	lastErr error
}

// refreshTokenFromLocal 从本机服务端免授权读取统一 Token。
// 服务端换过 Token 后，GUI 与插件手里的旧值会失效，这条路径负责自动恢复。
func (u *ui) refreshTokenFromLocal() (string, error) {
	u.tokSync.mu.Lock()
	defer u.tokSync.mu.Unlock()
	if u.tokSync.lastErr != nil && time.Since(u.tokSync.lastTry) < 5*time.Second {
		return "", u.tokSync.lastErr
	}
	u.tokSync.lastTry = time.Now()
	u.tokSync.lastErr = nil

	u.mu.Lock()
	base := u.conf.ServerURL
	u.mu.Unlock()
	host := ""
	if pu, err := url.Parse(base); err == nil {
		host = pu.Hostname()
	}
	if !isLocalHost(host) {
		u.tokSync.lastErr = errors.New("服务端不在本机，无法自动读取 Token")
		return "", u.tokSync.lastErr
	}
	var lastErr error
	for _, b := range localBaseCandidates(base) {
		tok, err := fetchLocalToken(b)
		if err == nil && tok != "" {
			return tok, nil
		}
		if err != nil {
			lastErr = err
		}
	}
	if lastErr == nil {
		lastErr = errors.New("未取得 Token")
	}
	u.tokSync.lastErr = lastErr
	return "", lastErr
}

// refreshTokenForClient 是客户端 401 时的自愈回调：后台线程里取回新 Token。
// Token 先在当前线程落盘并切到新客户端，界面上的输入框再回主线程更新。
func (u *ui) refreshTokenForClient() (string, error) {
	tok, err := u.refreshTokenFromLocal()
	if err != nil {
		return "", err
	}
	u.applyToken(tok)
	fyne.Do(func() { u.updateToken(tok) })
	return tok, nil
}

// randomToken 生成 48 位十六进制 Token（与服务端默认格式一致）。
func randomToken() string {
	buf := make([]byte, 24)
	if _, err := rand.Read(buf); err != nil {
		return ""
	}
	return hex.EncodeToString(buf)
}

// adminSettingsResp 对应服务端 GET /admin/config 的返回。
type adminSettingsResp struct {
	OK               bool   `json:"ok"`
	Listen           string `json:"listen"`
	Token            string `json:"token"`
	ReportRatePerMin int    `json:"report_rate_per_min"`
	RetentionDays    int    `json:"retention_days"`
	OnlineTimeoutSec int    `json:"online_timeout_sec"`
	GithubMirror     string `json:"github_mirror"`
	UpdateAutoCheck  bool   `json:"update_auto_check"`
}

// refreshSettings 首次切到设置页时读取服务端当前设置回填表单。
func (u *ui) refreshSettings() {
	if u.setLoaded {
		return
	}
	u.setResult.SetText("正在读取服务端设置……")
	cli := u.client()
	go func() {
		data, err := cli.get("/admin/config", true)
		fyne.Do(func() {
			if err != nil {
				u.setLoaded = false
				u.setSaveBtn.Disable()
				u.setResult.SetText("读取服务端设置失败：" + err.Error() + "。请点\"重新读取\"重试（GUI 与服务端同机时会自动同步 Token）")
				return
			}
			var s adminSettingsResp
			if json.Unmarshal(data, &s) != nil || !s.OK {
				u.setSaveBtn.Disable()
				u.setResult.SetText("读取服务端设置失败：响应格式异常")
				return
			}
			u.setListen.SetText(s.Listen)
			u.setToken.SetText(s.Token)
			u.setRate.SetText(strconv.Itoa(s.ReportRatePerMin))
			u.setRetention.SetText(strconv.Itoa(s.RetentionDays))
			u.setOnline.SetText(strconv.Itoa(s.OnlineTimeoutSec))
			u.setMirror.SetText(s.GithubMirror)
			u.setAutoChk.SetChecked(s.UpdateAutoCheck)
			u.setLoaded = true
			u.setSaveBtn.Enable()
			u.setResult.SetText("已读取服务端当前配置")
		})
	}()
}

// saveSettingsInt 从表单解析数值项，解析失败返回错误提示。
func saveSettingsInt(text, name string, lo, hi int) (int, string, bool) {
	v, err := strconv.Atoi(strings.TrimSpace(text))
	if err != nil || v < lo || v > hi {
		return 0, fmt.Sprintf("%s 需为 %d-%d 的数字", name, lo, hi), false
	}
	return v, "", true
}

// saveSettings 把表单提交到 POST /admin/config。
func (u *ui) saveSettings() {
	rate, msg, ok := saveSettingsInt(u.setRate.Text, "上报上限", 1, 100000)
	if !ok {
		u.setResult.SetText(msg)
		return
	}
	retention, msg, ok := saveSettingsInt(u.setRetention.Text, "数据保留天数", 1, 3650)
	if !ok {
		u.setResult.SetText(msg)
		return
	}
	online, msg, ok := saveSettingsInt(u.setOnline.Text, "离线判定秒数", 5, 86400)
	if !ok {
		u.setResult.SetText(msg)
		return
	}
	tok := strings.TrimSpace(u.setToken.Text)
	if len(tok) < 6 || len(tok) > 128 || strings.ContainsAny(tok, " \t\r\n") {
		u.setResult.SetText("Token 需为 6-128 位且不含空白字符")
		return
	}
	u.setResult.SetText("正在保存……")
	cli := u.client()
	req := map[string]any{
		"listen":              strings.TrimSpace(u.setListen.Text),
		"token":               tok,
		"report_rate_per_min": rate,
		"retention_days":      retention,
		"online_timeout_sec":  online,
		"github_mirror":       strings.TrimSpace(u.setMirror.Text),
		"update_auto_check":   u.setAutoChk.Checked,
	}
	go func() {
		data, err := cli.postJSON("/admin/config", req)
		fyne.Do(func() {
			if err != nil {
				u.setResult.SetText("保存失败：" + err.Error())
				return
			}
			var resp struct {
				OK              bool   `json:"ok"`
				RestartRequired bool   `json:"restart_required"`
				Listen          string `json:"listen"`
				ListenChanged   bool   `json:"listen_changed"`
				Note            string `json:"note"`
			}
			if json.Unmarshal(data, &resp) != nil || !resp.OK {
				note := resp.Note
				if note == "" {
					note = "响应格式异常"
				}
				u.setResult.SetText("保存失败：" + note)
				return
			}
			if tok != cli.token() {
				u.updateToken(tok)
			}
			// 端口改了以后接入信息页的地址自动跟着变
			if resp.ListenChanged && resp.Listen != "" {
				u.syncServerURL(resp.Listen)
			}
			txt := resp.Note
			if txt == "" {
				txt = "已保存并即时生效"
			}
			if resp.RestartRequired {
				txt += "，请到概览页点击\"重启服务\""
			}
			u.setResult.SetText(txt)
		})
	}()
}

// syncServerURL 端口热切换后同步"接入信息"页里的服务端地址。
// 只换端口，主机名保持用户填写的那个（例如 127.0.0.1 或局域网 IP）。
func (u *ui) syncServerURL(listen string) {
	newURL := replaceURLPort(u.serverURL(), listen)
	if newURL == "" || newURL == u.serverURL() {
		return
	}
	u.mu.Lock()
	u.conf.ServerURL = newURL
	c := u.conf
	u.mu.Unlock()
	u.setClient(u.newClient())
	if u.urlEntry != nil {
		u.urlEntry.SetText(newURL)
	}
	_ = saveConf(c)
}

// rotateToken 生成新 Token 并立即在服务端启用：旧 Token 立刻失效，
// App 与插件需要同步填入新 Token。
func (u *ui) rotateToken() {
	tok := randomToken()
	if tok == "" {
		u.setResult.SetText("生成 Token 失败，请重试")
		return
	}
	u.setResult.SetText("正在生成新 Token……")
	cli := u.client()
	go func() {
		data, err := cli.postJSON("/admin/config", map[string]any{"token": tok})
		fyne.Do(func() {
			if err != nil {
				u.setResult.SetText("生成失败：" + err.Error())
				return
			}
			var resp struct {
				OK   bool   `json:"ok"`
				Note string `json:"note"`
			}
			if json.Unmarshal(data, &resp) != nil || !resp.OK {
				note := resp.Note
				if note == "" {
					note = "响应格式异常"
				}
				u.setResult.SetText("生成失败：" + note)
				return
			}
			u.setToken.SetText(tok)
			u.updateToken(tok)
			u.setResult.SetText("新 Token 已启用，旧 Token 立即失效，请把新 Token 同步到 App 与插件")
		})
	}()
}

func dotStyle(on bool) widget.RichTextStyle {
	if on {
		return widget.RichTextStyle{ColorName: theme.ColorNameSuccess, Inline: true}
	}
	return widget.RichTextStyle{ColorName: theme.ColorNameError, Inline: true}
}

// setDot 更新状态圆点；内容不变时跳过重绘（低端设备上重绘代价高）。
func (u *ui) setDot(run bool) {
	txt, st := "● 离线", dotStyle(false)
	if run {
		txt, st = "● 运行中", dotStyle(true)
	}
	if seg, ok := u.statusDot.Segments[0].(*widget.TextSegment); ok && seg.Text == txt {
		return
	}
	u.statusDot.Segments[0] = &widget.TextSegment{Text: txt, Style: st}
	u.statusDot.Refresh()
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

	overview := newDragScroll(container.NewVBox(statusCard, devicesCard))

	// ---- 运行日志页（页内展示，切换到本页即加载最新） ----
	u.logHint = widget.NewLabel("只看本次运行：显示服务最近一次启动以来的日志（最多 500 行，完整历史可用 journalctl 查看）；切换到本页时自动加载最新内容。")
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
		newDragScroll(u.logView),
	)

	// ---- 接入信息页 ----
	note := widget.NewLabel("设备、插件与本管理界面共用同一个 Token；GUI 与服务端同机时会自动读取，可直接复制给 App 和插件。在\"设置\"页改过监听端口后，这里的地址会自动同步成新端口。")
	note.Wrapping = fyne.TextWrapWord
	note.TextStyle = fyne.TextStyle{Italic: true}

	u.urlEntry = widget.NewEntry()
	u.urlEntry.SetPlaceHolder("http://127.0.0.1:8080")
	u.tokenDev = widget.NewEntry()
	u.tokenDev.SetPlaceHolder("与本机服务端同机时自动读取")

	saveBtn := widget.NewButton("保存连接信息", func() {
		c := guiConf{ServerURL: strings.TrimSpace(u.urlEntry.Text), PluginToken: strings.TrimSpace(u.tokenDev.Text)}
		if c.ServerURL == "" {
			u.ctlResult.SetText("地址不能为空")
			return
		}
		u.applyConf(c)
		u.setLoaded = false
		if c.PluginToken != "" {
			u.ctlResult.SetText("连接信息已保存")
			return
		}
		// 未填 Token 时尝试从本机服务端免授权读取
		go func() {
			tok, err := u.refreshTokenFromLocal()
			fyne.Do(func() {
				if err != nil {
					u.ctlResult.SetText("连接信息已保存；未自动获取到 Token（仅限与本机服务端同机），请在\"接入信息\"页手动填写")
					return
				}
				u.updateToken(tok)
				u.ctlResult.SetText("连接信息已保存，已自动读取本机服务 Token")
			})
		}()
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
		container.NewBorder(nil, nil, widget.NewLabel("地址"), nil, u.urlEntry),
		container.NewBorder(nil, nil, widget.NewLabel("Token"), copyToken, u.tokenDev),
		saveBtn,
	))
	maintCard := widget.NewCard("更新", "", container.NewVBox(
		container.NewHBox(verBtn, updBtn),
		u.verLabel,
		u.ctlResult,
	))
	tokenPage := newDragScroll(container.NewVBox(tokenCard, maintCard))

	// ---- 设置页（服务端参数，保存在服务端配置文件中） ----
	setHint := widget.NewLabel("以下设置写入服务端配置文件并即时生效，无需重启服务：\"监听地址\"改端口后服务立即切换到新端口，\"接入信息\"页地址会自动同步。\"生成新 Token\"会立即换发新令牌，旧令牌立刻失效，App 与插件需同步更新。数据目录、GitHub 仓库等仍需在 /etc/mugshot/config.json 中修改。")
	setHint.Wrapping = fyne.TextWrapWord
	setHint.TextStyle = fyne.TextStyle{Italic: true}

	u.setListen = widget.NewEntry()
	u.setListen.SetPlaceHolder(":8080")
	u.setToken = widget.NewEntry()
	u.setRate = widget.NewEntry()
	u.setRate.SetPlaceHolder("30")
	u.setRetention = widget.NewEntry()
	u.setRetention.SetPlaceHolder("7")
	u.setOnline = widget.NewEntry()
	u.setOnline.SetPlaceHolder("120")
	u.setMirror = widget.NewEntry()
	u.setMirror.SetPlaceHolder("https://ghfast.top（可留空）")
	u.setAutoChk = widget.NewCheck("启动时自动检查更新", nil)

	form := widget.NewForm()
	form.Append("监听地址", u.setListen)
	form.Append("Token", u.setToken)
	form.Append("上报上限（次/分钟）", u.setRate)
	form.Append("数据保留（天）", u.setRetention)
	form.Append("离线判定（秒）", u.setOnline)
	form.Append("下载镜像", u.setMirror)
	form.Append("", u.setAutoChk)

	genTokBtn := widget.NewButton("生成新 Token", func() {
		u.rotateToken()
	})
	u.setSaveBtn = widget.NewButton("保存设置", func() { u.saveSettings() })
	u.setSaveBtn.Disable()
	setReloadBtn := widget.NewButton("重新读取", func() {
		u.setLoaded = false
		u.refreshSettings()
	})

	u.setResult = widget.NewLabel("")
	u.setResult.Wrapping = fyne.TextWrapWord

	settingsPage := newDragScroll(container.NewVBox(
		widget.NewCard("服务端设置", "", container.NewVBox(
			setHint,
			form,
			container.NewHBox(u.setSaveBtn, genTokBtn, setReloadBtn),
			u.setResult,
		)),
	))

	u.tabs = container.NewAppTabs(
		container.NewTabItem(tabOverview, overview),
		container.NewTabItem(tabRunLog, logPage),
		container.NewTabItem(tabAccess, tokenPage),
		container.NewTabItem(tabSettings, settingsPage),
	)
	// 每次切到运行日志页都重新加载最新日志；首次切到设置页时读取当前值
	u.tabs.OnSelected = func(t *container.TabItem) {
		if t.Text == tabRunLog {
			u.refreshRunLog()
		}
		if t.Text == tabSettings {
			u.refreshSettings()
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
	cli := u.client()
	data, err := cli.get("/health", false)
	if err != nil {
		tx := fmt.Sprintf("无法连接 %s（%v）", cli.base, err)
		fyne.Do(func() {
			u.setDot(false)
			// 内容不变不重绘，避免轮询导致的界面抖动
			// 控件只能在界面线程读取与修改，这里整体放进 fyne.Do
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
	// lastStatusTx 会被轮询线程和更新后的界面线程同时写入，比较与写入都要加锁
	u.mu.Lock()
	same := tx == u.lastStatusTx
	if !same {
		u.lastStatusTx = tx
	}
	u.mu.Unlock()
	if same {
		return
	}
	fyne.Do(func() {
		u.setDot(true)
		u.statusLn.SetText(tx)
	})
}

func (u *ui) refreshDevices() {
	cli := u.client()
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
	u.mu.Lock()
	same := sig == u.lastDevSig
	if !same {
		u.lastDevSig = sig
	}
	u.mu.Unlock()
	if same {
		return
	}
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
	// 分钟粒度：设备每次上报（秒级）都会变的数据不触发整块重绘，低端软件渲染设备扛不住
	for _, r := range rows {
		fmt.Fprintf(&b, "%s|%s|%t|%d;", r.DeviceID, r.DeviceName, r.Online, r.LastReportAt/60000)
	}
	return b.String()
}

func (u *ui) checkUpdate() {
	u.verLabel.SetText("正在检查更新……")
	data, err := u.client().get("/version", false)
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
	cli := u.client()
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
		u.logHint.SetText("最近 24 小时：跨重启查看历史日志（最多 500 行，完整历史可用 journalctl 查看；重启不清除日志文件）。")
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

// maxLogLines 日志页最多加载的行数。Fyne 多行文本控件渲染上千行在
// 无 GPU（软件渲染）设备上会造成明显卡顿，超长日志请用 journalctl 查看。
const maxLogLines = 500

// serverLogs 优先直接读 journal（用户在 systemd-journal/adm 组内即可），
// 无权限时回退 pkexec 授权读取；两者都带超时，避免界面卡在授权等待。
// mode = "restart" 只取服务最近一次启动之后的日志；"24h" 取最近 24 小时。
func serverLogs(mode string) (string, error) {
	args := []string{"-u", "mugshot-server", "--no-pager", "-n", fmt.Sprint(maxLogLines)}
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
		return "刚刚"
	case d < time.Hour:
		return fmt.Sprintf("%d 分钟前", int(d.Minutes()))
	case d < 24*time.Hour:
		return fmt.Sprintf("%d 小时前", int(d.Hours()))
	default:
		return fmt.Sprintf("%d 天前", int(d.Hours()/24))
	}
}
