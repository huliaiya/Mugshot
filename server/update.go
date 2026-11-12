package main

import (
	"archive/tar"
	"compress/gzip"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Release describes a GitHub release relevant to self-update.
type Release struct {
	Tag         string   `json:"tag_name"`
	Notes       string   `json:"body"`
	PublishedAt string   `json:"published_at"`
	Assets      []Asset  `json:"assets"`
	downloadURL string   // fallback asset URL when the API is unavailable
	assetNames  []string // fallback asset names
}

type Asset struct {
	Name               string `json:"name"`
	Size               int64  `json:"size"`
	BrowserDownloadURL string `json:"browser_download_url"`
}

var httpClient = &http.Client{Timeout: 20 * time.Second}

// 更新检查缓存：/version、/admin/*、GUI、插件请求可能频繁触发，
// 统一走 10 分钟 TTL 缓存；显式刷新（CLI --check-update）强制绕过。
var (
	relCacheMu     sync.Mutex
	relCache       *Release
	relCacheErr    error
	relCacheAt     time.Time
	relCacheOK     bool
	relCacheTTL    = 10 * time.Minute
	relCacheErrTTL = 60 * time.Second
)

// CheckLatestRelease queries GitHub Releases with failover: primary source is
// the REST API; when it fails (rate limit / network), a redirect scrape of
// /releases/latest is used so update checks keep working.
// 结果带 TTL 缓存，成功 10 分钟、失败 60 秒。
func CheckLatestRelease(cfg *Config) (*Release, error) {
	return checkLatestRelease(cfg, false)
}

// CheckLatestReleaseForce 强制刷新（绕过缓存）。
func CheckLatestReleaseForce(cfg *Config) (*Release, error) {
	return checkLatestRelease(cfg, true)
}

func checkLatestRelease(cfg *Config, force bool) (*Release, error) {
	repo := cfg.GetGithubRepo()
	if repo == "" {
		return nil, errors.New("未配置 github_repo，无法检查更新（可在 config.json 设置 github_repo 或环境变量 MUGSHOT_GITHUB_REPO）")
	}

	relCacheMu.Lock()
	defer relCacheMu.Unlock()
	if !force {
		ttl := relCacheErrTTL
		if relCacheOK {
			ttl = relCacheTTL
		}
		if relCache != nil && time.Since(relCacheAt) < ttl {
			return relCache, relCacheErr
		}
	}

	rel, apiErr := fetchViaAPI(repo)
	if apiErr == nil {
		relCache, relCacheErr, relCacheAt, relCacheOK = rel, nil, time.Now(), true
		return rel, nil
	}
	log.Printf("[update] GitHub API 检查失败（%v），尝试备用源", apiErr)

	rel, err := fetchViaRedirect(repo)
	if err != nil {
		relCache, relCacheErr, relCacheAt, relCacheOK = nil, fmt.Errorf("主源与备用源均失败: api=%v fallback=%v", apiErr, err), time.Now(), false
		return nil, relCacheErr
	}
	relCache, relCacheErr, relCacheAt, relCacheOK = rel, nil, time.Now(), true
	return rel, nil
}

func fetchViaAPI(repo string) (*Release, error) {
	req, err := http.NewRequest(http.MethodGet, "https://api.github.com/repos/"+repo+"/releases/latest", nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/vnd.github+json")
	req.Header.Set("User-Agent", "mugshot-server/"+version)
	resp, err := httpClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("GitHub API 返回 %s", resp.Status)
	}
	var rel Release
	if err := json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(&rel); err != nil {
		return nil, err
	}
	if rel.Tag == "" {
		return nil, errors.New("响应中缺少 tag_name")
	}
	return &rel, nil
}

func fetchViaRedirect(repo string) (*Release, error) {
	url := "https://github.com/" + repo + "/releases/latest"
	req, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", "mugshot-server/"+version)
	resp, err := httpClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	finalURL := resp.Request.URL.String()
	idx := strings.LastIndex(finalURL, "/tag/")
	if idx < 0 {
		return nil, fmt.Errorf("无法从 %s 解析版本号", finalURL)
	}
	tag := finalURL[idx+len("/tag/"):]
	if tag == "" {
		return nil, errors.New("备用源未返回版本号")
	}
	arch := "arm64"
	if runtime.GOARCH == "amd64" {
		arch = "amd64"
	}
	binName := "mugshot-server_linux_" + arch
	dl := "https://github.com/" + repo + "/releases/download/" + tag + "/" + binName
	return &Release{
		Tag:         tag,
		Notes:       "",
		downloadURL: dl,
		assetNames:  []string{binName},
	}, nil
}

// NewerThan compares version tags like v1.2.3 / 1.2.3 numerically.
func NewerThan(tag, current string) bool {
	a, aok := parseVersion(tag)
	b, bok := parseVersion(current)
	if !aok || !bok {
		return false
	}
	for i := 0; i < 3; i++ {
		if a[i] != b[i] {
			return a[i] > b[i]
		}
	}
	return false
}

func parseVersion(v string) ([3]int, bool) {
	var out [3]int
	v = strings.TrimSpace(v)
	v = strings.TrimPrefix(v, "v")
	v = strings.TrimPrefix(v, "V")
	if i := strings.IndexAny(v, "-+"); i >= 0 {
		v = v[:i]
	}
	parts := strings.Split(v, ".")
	if len(parts) == 0 || parts[0] == "" {
		return out, false
	}
	for i := 0; i < 3 && i < len(parts); i++ {
		n, err := strconv.Atoi(strings.TrimSpace(parts[i]))
		if err != nil {
			return out, false
		}
		out[i] = n
	}
	return out, true
}

// pickAsset selects the release asset matching the current platform.
func pickAsset(rel *Release) (name, url string, size int64) {
	arch := "arm64"
	if runtime.GOARCH == "amd64" {
		arch = "amd64"
	}
	// prefer the raw single-file binary asset
	for _, a := range rel.Assets {
		if strings.Contains(a.Name, "linux_"+arch) && !strings.HasSuffix(a.Name, ".tar.gz") &&
			!strings.HasSuffix(a.Name, ".zip") && strings.HasPrefix(a.Name, "mugshot-server") {
			return a.Name, a.BrowserDownloadURL, a.Size
		}
	}
	// then the tar.gz bundle
	for _, a := range rel.Assets {
		if strings.Contains(a.Name, "linux_"+arch) && strings.HasSuffix(a.Name, ".tar.gz") {
			return a.Name, a.BrowserDownloadURL, a.Size
		}
	}
	// fallback: URL constructed without asset list (redirect source)
	if rel.downloadURL != "" {
		return rel.assetNames[0], rel.downloadURL, 0
	}
	return "", "", 0
}

func applyMirror(cfg *Config, url string) string {
	mirror := strings.TrimRight(cfg.GetGithubMirror(), "/")
	if mirror == "" {
		return url
	}
	return mirror + "/" + url
}

// SelfUpdate downloads the newest release, validates it and atomically
// replaces the running binary. The caller is expected to exit afterwards and
// let systemd Restart=always bring the new version up.
func SelfUpdate(cfg *Config, rel *Release) error {
	name, url, size := pickAsset(rel)
	if url == "" {
		return errors.New("该 Release 没有匹配当前平台的资产（需要 linux_arm64/linux_amd64）")
	}
	url = applyMirror(cfg, url)
	log.Printf("[update] 开始下载 %s (%s)", name, url)

	exePath, err := os.Executable()
	if err != nil {
		return err
	}
	exePath, err = filepath.Abs(exePath)
	if err != nil {
		return err
	}
	tmp := exePath + ".update.tmp"

	if strings.HasSuffix(name, ".tar.gz") {
		err = downloadTarGzBinary(url, tmp)
	} else {
		err = downloadFile(url, tmp)
	}
	if err != nil {
		return fmt.Errorf("下载失败: %w", err)
	}
	st, err := os.Stat(tmp)
	if err != nil || st.Size() < 1024 {
		osRemove(tmp)
		return fmt.Errorf("下载内容异常（size=%d）", fileSizeOrZero(tmp))
	}
	if size > 0 && st.Size() != size {
		log.Printf("[update] 提示：下载大小 %d 与 Release 声明 %d 不一致，继续应用（可能是镜像差异）", st.Size(), size)
	}
	if err := os.Chmod(tmp, 0755); err != nil {
		osRemove(tmp)
		return err
	}
	if err := os.Rename(tmp, exePath); err != nil {
		osRemove(tmp)
		return err
	}
	log.Printf("[update] 已替换二进制 %s → 版本 %s", exePath, rel.Tag)
	return nil
}

func downloadFile(url, dest string) error {
	resp, err := httpClient.Get(url)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("下载返回 %s", resp.Status)
	}
	f, err := os.Create(dest)
	if err != nil {
		return err
	}
	defer f.Close()
	_, err = io.Copy(f, io.LimitReader(resp.Body, 512<<20))
	return err
}

// downloadTarGzBinary extracts the first executable-looking entry (the
// mugshot-server binary) from a tar.gz into dest.
func downloadTarGzBinary(url, dest string) error {
	resp, err := httpClient.Get(url)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("下载返回 %s", resp.Status)
	}
	gz, err := gzip.NewReader(io.LimitReader(resp.Body, 512<<20))
	if err != nil {
		return err
	}
	defer gz.Close()
	tr := tar.NewReader(gz)
	for {
		hdr, err := tr.Next()
		if err == io.EOF {
			return errors.New("压缩包中没有找到 mugshot-server 二进制")
		}
		if err != nil {
			return err
		}
		if hdr.Typeflag == tar.TypeReg && (strings.HasSuffix(hdr.Name, "mugshot-server") || hdr.Name == "mugshot-server") {
			f, err := os.Create(dest)
			if err != nil {
				return err
			}
			if _, err := io.Copy(f, io.LimitReader(tr, 512<<20)); err != nil {
				f.Close()
				return err
			}
			return f.Close()
		}
	}
}

func osRemove(path string) { _ = os.Remove(path) }

func fileSizeOrZero(path string) int64 {
	st, err := os.Stat(path)
	if err != nil {
		return 0
	}
	return st.Size()
}

// UpdateCheckLoop checks for updates at startup and once per day; it only
// logs unless update_auto_apply is explicitly enabled in the config.
// 开关（update_auto_check）每轮都会重新读取，设置页改完立即生效，无需重启。
func UpdateCheckLoop(cfg *Config) {
	time.Sleep(15 * time.Second)
	var lastCheck time.Time
	check := func() {
		if !cfg.GetUpdateAutoCheck() {
			return
		}
		lastCheck = time.Now()
		rel, err := CheckLatestRelease(cfg)
		if err != nil {
			log.Printf("[update] 定期检查更新失败: %v", err)
			return
		}
		if !NewerThan(rel.Tag, releaseTag) {
			log.Printf("[update] 定期检查：当前发布版本 %s 已是最新", releaseTag)
			return
		}
		log.Printf("[update] 发现新版本 %s（当前 %s），可执行 `mugshot-server --update` 或发送 #mugshot更新", rel.Tag, version)
		if cfg.GetUpdateAutoApply() {
			if err := SelfUpdate(cfg, rel); err == nil {
				log.Printf("[update] update_auto_apply 已启用，即将重启到 %s", rel.Tag)
				time.Sleep(2 * time.Second)
				osExit(0)
			}
		}
	}
	check()
	// 每小时醒来一次：开关打开后最多一小时生效，关闭后立即停止检查。
	t := time.NewTicker(time.Hour)
	defer t.Stop()
	for range t.C {
		if lastCheck.IsZero() || time.Since(lastCheck) >= 24*time.Hour {
			check()
		}
	}
}
