# Mugshot

基于 TRSS-Yunzai 的设备状态远程查看系统。群成员在 QQ 群内发送指令，即可查看群主 Android 设备的当前使用状态：正在使用什么 App、屏幕是否亮着、电量、电池温度、充电状态、网络类型、设备名称等，还能看到同时可见的分屏/小窗应用、后台运行中的应用，以及最近 24 小时各应用的使用时长。支持多台 Android 设备同时接入，每台设备可在 App 内自定义名称，群内指令按设备名查询。

## 三个组件

| 组件 | 目录 | 说明 |
| --- | --- | --- |
| Mugshot（Android 上报端） | `android/` | Kotlin 应用，采集前台应用、分屏/小窗可见应用、后台应用、屏幕状态、电量与电池温度、充电、网络类型与最近 24 小时应用使用时长，通过 WebSocket 定时上报，并响应服务端按需上报指令；内置"运行日志"页，默认只看本次运行，可切换最近 24 小时历史 |
| mugshot-server（Linux 服务端） | `server/` | Go 编写，Ubuntu ARM64 与 x86_64 均有预编译包，接收多设备上报、维护设备在线状态表、提供 HTTP 查询 API 与 WebSocket 长连接、支持向设备下发 `request_report` 按需上报指令 |
| Mugshot（TRSS-Yunzai 插件） | `plugins/mugshot/` | ESM 插件，连接服务端，接收 QQ 群指令，渲染状态卡片图片并通过 QQBot 按钮交互 |

## 架构

```
┌─────────────────┐   WebSocket(定时上报/heartbeat)      ┌──────────────────┐
│ Mugshot Android │ ──────────────────────────────────▶ │                  │
│  设备A/设备B/...  │ ◀──────── request_report ────────── │  mugshot-server  │
└─────────────────┘                                      │  (systemd 常驻)   │
                                                         │                  │
┌─────────────────┐   HTTP 查询(/status /devices)        │                  │
│ Mugshot 插件     │ ──────────────────────────────────▶ │                  │
│ (TRSS-Yunzai)   │ ◀──────── live/cache 数据 ────────── │                  │
└─────────────────┘                                      └──────────────────┘
        │
        ▼  e.runtime.render 渲染图片 + QQBot 按钮
   QQ 群消息
```

## 群内指令

| 指令 | 说明 | 权限 |
| --- | --- | --- |
| `#群主帮助` | 查看指令列表 | 所有人 |
| `#群主列表` | 渲染所有设备列表（在线状态、最后上报时间），附选择按钮 | 所有人 |
| `#群主查看 [设备名]` | 查询设备当前状态，默认触发一次按需上报（fresh），返回 live 数据；设备离线或超时降级返回 cache 数据 | 所有人 |
| `#群主在干嘛 [设备名]` | 同 `#群主查看`，卡片附带每台设备近 24 小时使用最多的 3 个应用（时长 + 打开次数） | 所有人 |
| `#群主详情 [设备名]` | 返回完整字段状态卡片 | 所有人 |
| `#群主用过什么` | 渲染各设备最近 24 小时的软件使用时长与打开次数（按前台时长排序，含占比条） | 所有人 |
| `#群主更新` | 检查并更新插件自身（宿主更新机制支持时自动装依赖并重启，失败回退 git 直拉）；服务端更新走其自身检查或 GUI | Master |
| `#群主更新日志` | 查看插件最近提交记录 | 所有人 |

`#群主查看` / `#群主在干嘛` 不带设备名时渲染全部设备状态卡片，可再加设备名查看单台。

### 后台保活与 Shizuku（可选）

- 上报服务使用 `specialUse` 前台服务类型，避免 Android 15 对 `dataSync` 的每日约 6 小时配额；配合 START_STICKY、任务划掉自动重启与 15 分钟看门狗闹钟，降低"滑到后台就断连"的概率。
- 安装并运行 Shizuku（`https://shizuku.rikka.app/`）后，首页「Shizuku 增强」面板可一键完成：代为授予使用情况访问权限（拿到前台应用、24 小时使用时长与打开次数）、加入电池/Doze 白名单、把待机桶设为 active、放宽后台运行限制。未安装或未授权时全部能力自动降级，不影响上报。

## 快速开始

1. 部署 `mugshot-server`（Ubuntu ARM64 / x86_64，systemd 常驻）：`uname -m` 确认架构后，下载 Release 对应架构的 `.deb` 直接 `sudo apt install ./xxx.deb`，装完即自动运行；有桌面时应用菜单点"Mugshot 服务端"打开图形管理面板，纯 SSH/无桌面用 `sudo mugshot-ctl status|devices|show|token|config|set|logs|restart` 完成查看与设置，详见 `docs/DEPLOY.md`
2. 安装 `plugins/mugshot` 到 TRSS-Yunzai 的 `plugins/` 目录：`git clone -b main https://github.com/huliaiya/Mugshot.git mugshot`，配置 `config/mugshot.yaml`（服务端地址与 Token，与 App 共用同一个）
3. 安装 Mugshot APK（Release 下载，已签名，包名 `com.Mugshot.huli`，可直接安装），同意隐私政策，授予使用情况访问权限，填写服务端地址与 Token，开启上报

详细部署与三端联调配置见 [docs/DEPLOY.md](docs/DEPLOY.md)。

## 构建

| 产物 | 方式 |
| --- | --- |
| Android APK | GitHub Actions `build-android.yml`，或本地 `cd android && ./gradlew assembleRelease` |
| mugshot-server Linux | GitHub Actions `build-linux`（ARM64 与 x86_64 矩阵构建），每种架构产出 `.deb`（`apt install` 即装）、`.tar.gz`、裸二进制与 GUI；本地 `cd server && CGO_ENABLED=0 go build` |
| 插件 | 纯源码，无构建步骤；Release 附带 `mugshot-plugin_<版本>.tar.gz`，插件内容同时自动同步到 `main` 分支（可直接 `git clone -b main` 安装） |

三端均内置更新检查（启动时与每日一次，GitHub Releases，主源失败自动尝试备用源）与手动更新入口。

## 安全与隐私

- Android 端仅采集前台应用名称、屏幕状态、电池、网络类型、各应用前台使用时长等状态字段，不上传输入内容、屏幕文本、剪贴板等敏感数据；权限用途逐条见 `docs/PRIVACY.md`
- 首次启动展示隐私政策，用户同意前不采集、不上报任何数据（见 `docs/PRIVACY.md`）
- App、插件与 GUI 共用同一个 Token 鉴权，服务端校验 device_id 与 token 绑定关系
- 服务端轮换 Token 后旧 Token 立即失效；GUI 与插件在收到 401 时会自动从本机服务端同步新 Token 并重试（远程服务端需手动填写）
- 服务端默认仅保留最近 7 天（可配置）数据；生产环境建议通过反向代理启用 TLS

## License

MIT
