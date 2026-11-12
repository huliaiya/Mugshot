# Mugshot 部署指南

Mugshot 由三部分组成：

| 组件 | 位置 | 说明 |
|------|------|------|
| mugshot-server | `server/` | Go 服务端，运行在 Ubuntu 26.04 ARM64 服务器 |
| Mugshot 插件 | `plugins/mugshot/` | TRSS-Yunzai ESM 插件，与 Yunzai 同机部署 |
| Mugshot App | `android/` | Android 上报端，安装在你的手机上 |

数据流：手机 App --(WebSocket / HTTP)--> mugshot-server <--(WebSocket / HTTP)-- Yunzai 插件。

## 1. 部署 mugshot-server（Ubuntu ARM64）

### 1.1 方式一：.deb 包一键安装（推荐，Ubuntu 26.04 ARM64）

从 GitHub Release 下载 `mugshot-server_<版本>_arm64.deb`，然后：

```bash
sudo apt install ./mugshot-server_<构建哈希>_arm64.deb
# 或: sudo dpkg -i mugshot-server_<构建哈希>_arm64.deb
# 构建哈希 = Release 对应提交的 Git 短哈希，见 Release 页资产名
```

注意：mugshot-server 是后台服务，由 systemd 自动启动并开机自启；桌面操作通过图形管理面板完成。

包会自动完成：

- 安装服务端 `/usr/bin/mugshot-server`、图形面板 `mugshot-gui`、终端工具 `mugshot-ctl`
- 应用菜单只注册一个入口：
  - **"Mugshot 服务端"**（图形界面）：四个页签——概览（运行状态 / 在线设备 / 启停重启）、运行日志（默认"只看本次运行"，可一键切换"最近 24 小时"完整历史；重启只影响显示范围，日志文件/journal 原样保留，切到该页自动加载最新，最多显示最近 500 行，无需另开窗口）、接入信息（地址与 Token 查看复制 / 检查更新与一键更新）、设置（监听地址、Token、上报上限、数据保留天数、离线判定、下载镜像、自动检查更新；支持保存设置与一键生成新 Token）。设置全部即时生效，改监听端口不用重启服务：服务端直接换到新端口，"接入信息"页里的地址自动同步成新端口（主机名保持不变），App 与插件按新地址连接即可。GUI 与服务端同机时自动免授权读取服务端 Token 并显示出来，可直接复制给 App 和插件，不需要输入密码或授权弹窗；查看日志如遇权限提示，可 `sudo usermod -aG systemd-journal $USER` 后重新登录，一劳永逸
  - `mugshot-ctl` 终端菜单保留在系统里（无菜单图标），无桌面环境或 SSH 场景在终端直接运行
- 注册 systemd 服务 `mugshot-server`（`Restart=always`，`RestartSec=5`）并立即启动
- 创建 `mugshot` 系统用户与数据目录 `/var/lib/mugshot`
- 生成配置 `/etc/mugshot/config.json`（首次启动自动生成随机 token 并写回，App、插件与 GUI 共用同一个 Token）

安装完成后获取手机 App 与插件需要的 Token（App、插件、GUI 共用同一个）：

```bash
sudo cat /etc/mugshot/config.json        # 直接查看 token 字段
# 或打开应用菜单"Mugshot 服务端"，在"接入信息"页或"设置"页直接复制 Token
curl http://127.0.0.1:8080/health        # 验证服务在跑
```

> 旧版本配置文件里的 `device_token` / `plugin_token` 仍然可用，服务端启动时会自动迁移为统一的 `token` 字段（优先取 plugin_token，其次 device_token），无需手工修改。

> v1.0.19 起，服务端轮换 Token 后旧 Token 立即失效（含配置文件里的兼容字段），GUI 与插件收到 401 时会自动从本机服务端（`/local/config`，仅回环可达）同步新 Token 并重试一次，无需手工复制。指向远程服务端时自动同步不可用，请在"接入信息"页复制 Token 手动填写。

> 设置页保存后全部即时生效，无需重启服务：监听地址会直接切换到新端口（端口被占用时保留原端口并给出提示），上报上限、数据保留天数、离线判定、下载镜像、自动检查更新等立即按新值执行。改端口后 App 与插件需要改用新地址连接。

升级：下载新 .deb 再次 `apt install ./...` 即可，配置与数据自动保留。彻底卸载：`sudo apt purge mugshot-server`（配置/数据需按提示手动删除）。

> v1.0.18 修复了 GUI"设置"页保存提示 HTTP 500 的问题（systemd 单元未放行 `/etc/mugshot`，且目录归属不是服务用户）。升级后 postinst 会自动修正权限并重启服务；如仍失败，执行 `sudo systemctl daemon-reload && sudo systemctl restart mugshot-server`。

### 1.2 方式二：install.sh 脚本安装

从 GitHub Release 下载 `mugshot-server_linux_arm64_<版本>.tar.gz` 并解压，然后：

```bash
sudo ./install.sh
```

脚本会：创建 `mugshot` 系统用户、安装到 `/opt/mugshot`、生成 `config.json`（含自动生成的 token，App 与插件共用同一个）、注册并启动 systemd 服务。

### 1.3 方式三：手动部署

```bash
sudo useradd -r -s /usr/sbin/nologin mugshot
sudo mkdir -p /opt/mugshot/data
sudo cp mugshot-server /opt/mugshot/
sudo cp mugshot-server.service /etc/systemd/system/
```

生成配置 `/opt/mugshot/config.json`：

```json
{
  "listen": "0.0.0.0:8080",
  "token": "换成长随机串",
  "data_dir": "/opt/mugshot/data",
  "retention_days": 7,
  "github_repo": "你的GitHub用户名/Mugshot",
  "github_mirror": ""
}
```

> 令牌生成：`openssl rand -hex 24`。App 与插件填写同一个 token，服务端校验 token 与 device_id 的绑定关系。

启动：

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now mugshot-server
systemctl status mugshot-server
```

### 1.4 验证

```bash
curl -s http://127.0.0.1:8080/health
curl -s -H "Authorization: Bearer <token>" http://127.0.0.1:8080/devices
```

### 1.5 systemd 说明

内置 `mugshot-server.service` 特性：

- `Restart=always` + `RestartSec=5`：崩溃/自更新退出后 5 秒自动拉起
- `Type=notify` + sd_notify 集成：就绪/看门狗心跳上报 systemd
- 看门狗（可选）：取消注释 `WatchdogSec=30`，进程每半周期自动喂狗
- 安全加固：`NoNewPrivileges` / `ProtectSystem=strict` / `PrivateTmp`（数据目录通过 `ReadWritePaths` 放行）
- 日志：`journalctl -u mugshot-server -f`（日志中 token 只显示前 4 位掩码）

> 自更新说明：`#群主更新` 触发后，服务端会从 GitHub Release（可配 `github_mirror` 前缀加速）下载新二进制替换自身，然后主动退出，由 systemd `Restart=always` 拉起新版本，约 5~10 秒恢复。

### 1.6 公网暴露与 TLS

仅在内网使用时无需 TLS。公网部署强烈建议前置 Nginx / Caddy 做 HTTPS + WebSocket 透传：

```
# Caddy 示例（自动 HTTPS）
mug.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

App 与插件中的服务器地址相应改为 `https://mug.example.com`。

> 注意：`/local/config` 只允许本机回环访问，用于 GUI 免授权读取 Token；前面挂了反向代理时，该路径的请求来源会变成回环地址，请自行在代理层屏蔽 `/local/config`，避免 Token 被外部读取。

## 2. 部署 Yunzai 插件

前提：TRSS-Yunzai（Miao-Yunzai/TRSS-Yunzai 均可，需支持 ESM 插件加载）、已配置 puppeteer 渲染器（图片渲染必需）。

### 2.1 方式一：克隆插件专用分支（推荐）

仓库的 `main` 分支由 CI 自动同步，内容就是插件根目录：

```bash
cd <Yunzai根目录>/plugins
git clone -b main https://github.com/huliaiya/Mugshot.git mugshot
```

### 2.2 方式二：Release 包

下载 Release 中的 `mugshot-plugin_<版本>.tar.gz`，解压到 `<Yunzai根目录>/plugins/mugshot`：

```bash
mkdir -p <Yunzai根目录>/plugins/mugshot
tar -xzf mugshot-plugin_1.0.1.tar.gz -C <Yunzai根目录>/plugins/mugshot --strip-components=1
```

### 2.3 方式三：完整仓库

```bash
cd <Yunzai根目录>/plugins
git clone https://github.com/huliaiya/Mugshot.git
ln -s "$(pwd)/Mugshot/plugins/mugshot" ./mugshot
```

首次运行后编辑 `<Yunzai根目录>/config/mugshot.yaml`：

```yaml
server_url: "http://服务器IP:8080"
token: "与服务端同一个 Token（GUI 接入信息页可直接复制）"
plugin_token: ""        # 兼容旧配置的键名，填了同样生效
fresh_timeout_ms: 8000      # 实时查询等待设备上报的超时
fresh_cooldown_ms: 10000    # 同一用户实时查询冷却
detail_fresh: true          # #群主详情 是否默认触发实时
github_repo: "你的GitHub用户名/Mugshot"
auto_update_check: true
device_btn_per_row: 5       # 设备选择按钮每行个数（最多 10）
```

重启 Yunzai 生效。日志出现 `已连接 mugshot-server WebSocket 订阅` 即成功。

> 注意：`#群主列表` 等所有回复均为图片，依赖 Yunzai 渲染器可用（`config/renderer.yaml`）。

## 3. 安装 Android App

1. 从 Release 下载 APK 安装（release APK 已用仓库内置签名密钥签名，包名 `com.Mugshot.huli`，可直接安装；升级版本可覆盖安装，签名一致）。安装时系统会提示"未知来源应用"，允许即可。
2. 首启阅读隐私政策并点击"同意并继续"（同意前 App 不发起任何网络请求）。
3. 底部导航切换到"设置"页，填写：
   - 服务器地址：`http://服务器IP:8080`
   - Token：服务端 GUI 的"接入信息"页或"设置"页可直接复制（与插件共用同一个）
   - 设备名称：群聊中显示的名字
4. 回到"首页"按提示完成权限引导：
   - **使用情况访问权限**（前台应用采集必需，系统特殊权限，需手动授予）
   - **通知权限**（Android 13+）
   - **电池优化白名单**（保持后台稳定）
   - **厂商自启动引导**（小米/华为/OPPO/vivo/三星等，App 会尝试直接跳转对应管理页）
5. 首页显示连接状态与实时设备信息（电池/网络/屏幕/前台应用等）；"设置"页可切换主题外观（跟随系统/浅色/深色，默认跟随系统）；"关于"页的版本号即构建时的 Git 短哈希（系统设置-应用信息中显示一致），并可检查更新（按发布版本比较）。状态显示"已连接服务器"即成功。

### Android 15 dataSync 限制

Android 15 对 `dataSync` 类型前台服务有每日约 6 小时运行配额。超时后系统会停止前台服务，此时：

- App 内的 WorkManager 会继续以 15 分钟为周期通过 HTTP（`POST /api/v1/report`）兜底上报
- 实时查询（request_report）在该时段可能降级为缓存数据
- 重新打开 App 即可重新拉起前台服务

### 应用内更新

App 启动与每日会检查 GitHub Releases（主源 api.github.com，失败自动切换备用源）。发现新版本时下载 APK 并拉起安装器，首次需要授予"安装未知应用"权限。

## 4. 使用（群聊/私聊发给 Bot）

| 指令 | 说明 |
|------|------|
| `#群主帮助` | 指令帮助（图片） |
| `#群主列表` | 所有设备概览 |
| `#群主查看 [设备名]` | 实时查看（默认 fresh，10 秒冷却） |
| `#群主在干嘛 [设备名]` | 同上（别名） |
| `#群主详情 [设备名]` | 完整字段 + 最近 10 条上报记录 |
| `#群主状态` | 服务端健康状态与版本 |
| `#群主更新` | 一条指令检查并直接更新服务端与插件，无需二次确认（仅 Master） |

- 多台设备时省略设备名会返回设备选择卡片（QQBot 适配器下带按钮）
- 实时查询触发 `request_report`，设备在几秒内回报 `live` 数据；超时/离线/冷却自动降级缓存并标注 `CACHE`
- 按钮仅 QQBot 适配器（如 QQBot.yaml 协议端）可用，其他协议端自动降级为纯图片

## 5. CI 产出

| Workflow | 触发 | 产物 |
|----------|------|------|
| build-android | push android/** 或 tag `v*` | 已签名 release APK（包名 `com.Mugshot.huli`） |
| build-linux-arm64 | push server/** / plugins/** 或 tag `v*` | `mugshot-server_<构建哈希>_arm64.deb`（内含 GUI 面板）、`mugshot-server_linux_arm64_<构建哈希>.tar.gz`、裸二进制（固定名，供服务端自更新）、`mugshot-gui_linux_arm64`（GUI 面板）、`mugshot-plugin_<ver>.tar.gz` |

服务端软件版本号 = 构建时的 Git 短哈希（`mugshot-server --version`、`/version` 接口可见）；更新检查按发布 tag 比较，与产物名中的哈希互不影响。

- tag 构建会自动创建 GitHub Release（下载页）并附加全部产物
- 插件内容会自动同步到仓库 `main` 分支（插件根目录），供 `git clone -b main` 安装

Android 发布签名使用仓库内置的 `android/app/mugshot-release.keystore`（PKCS12，别名 `com.Mugshot.huli`，密码见 app/build.gradle.kts），CI 无需额外 Secrets；CI 会用 apksigner verify 校验签名。如需更换签名，替换该 keystore 文件并同步修改 build.gradle.kts（注意：换签名后无法覆盖安装旧版本，需先卸载）。

每次发版都要递增 `android/app/build.gradle.kts` 里的 `versionCode`：新包版本号与已安装版本相同时，系统会以「降级安装」为由拒绝覆盖安装，签名一致也无法直接升级。

打 tag 发布：`git tag v1.0.0 && git push origin v1.0.0`，三个 workflow 会自动构建并附到 Release。

## 6. 故障排查

| 现象 | 排查 |
|------|------|
| App 一直"连接失败" | 检查 server_url 可达（`curl /health`）、App 内 Token 与服务端一致、服务器防火墙放行端口 |
| 插件日志无 WS 订阅 | `config/mugshot.yaml` 的 plugin_token 与服务端 token 不一致；或 Node < 22 且无 ws 包（`npm ls ws`） |
| 服务端日志刷「插件鉴权失败」 | 插件与服务端 Token 不一致。v1.0.19 起同机部署会自动同步新 Token；远程服务端请在"接入信息"页复制 Token 填到 `config/mugshot.yaml` 的 `token` 字段。同一 IP+路径的失败日志每分钟只记一条并汇总次数 |
| GUI 报「HTTP 401: unauthorized」 | GUI 缓存的 Token 已过期。v1.0.19 起同机部署会自动同步新 Token 并重试；远程服务端请在"接入信息"页手动填写后点"生成新 Token"或保存 |
| 查询永远显示 CACHE | 设备未在线/未授予使用情况权限/被厂商杀后台（看 App 权限面板与通知栏） |
| 回复无图片 | Yunzai 渲染器未配置（puppeteer），检查 `config/renderer.yaml` |
| 按钮不显示 | 仅 QQBot 适配器支持按钮，其他协议端正常降级 |
| 更新检查失败 | `github_repo` 未改成实际仓库，或服务器无法访问 GitHub（配置 `github_mirror`） |
| GUI"设置"页保存失败（HTTP 500） | 服务用户没有写 `/etc/mugshot` 的权限：先升级到 v1.0.18+（单元增加 `ReadWritePaths=/etc/mugshot`，postinst 会把目录归属改为 `mugshot`）再 `sudo systemctl restart mugshot-server`；v1.0.18 起目录不可写时自动回退为原地覆写，日志会给出具体原因（`journalctl -u mugshot-server | grep 保存设置失败`） |
