# Mugshot

基于 TRSS-Yunzai 的设备状态远程查看系统。群成员在 QQ 群内发送指令，即可查看群主 Android 设备的当前使用状态：正在使用什么 App、屏幕是否亮着、电量、充电状态、网络类型、设备名称等。支持多台 Android 设备同时接入，每台设备可在 App 内自定义名称，群内指令按设备名查询。

## 三个组件

| 组件 | 目录 | 说明 |
| --- | --- | --- |
| Mugshot（Android 上报端） | `android/` | Kotlin 应用，采集前台应用、屏幕状态、电量、充电、网络类型，通过 WebSocket 定时上报，并响应服务端按需上报指令 |
| mugshot-server（Linux 服务端） | `server/` | Go 编写，运行在 Ubuntu 26.04 ARM64（也兼容 x86_64），接收多设备上报、维护设备在线状态表、提供 HTTP 查询 API 与 WebSocket 长连接、支持向设备下发 `request_report` 按需上报指令 |
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
| `#mugshot帮助` | 查看指令列表 | 所有人 |
| `#mugshot列表` | 渲染所有设备列表（在线状态、最后上报时间），附选择按钮 | 所有人 |
| `#mugshot查看 [设备名]` | 查询设备当前状态，默认触发一次按需上报（fresh），返回 live 数据；设备离线或超时降级返回 cache 数据 | 所有人 |
| `#mugshot在干嘛 [设备名]` | 同 `#mugshot查看` | 所有人 |
| `#mugshot详情 [设备名]` | 返回完整字段状态卡片 | 所有人 |
| `#mugshot更新` | 触发服务端更新检查（可附带“应用”执行自更新） | Master 或群主 |
| `#mugshot插件更新` | 更新 Mugshot 插件自身 | Master |

不带设备名且存在多台设备时，插件渲染设备选择图片并附按钮，不随意挑选某一台。

## 快速开始

1. 部署 `mugshot-server`（Ubuntu ARM64，systemd 常驻）：见 `docs/DEPLOY.md` 或 `server/install.sh`
2. 安装 `plugins/mugshot` 到 TRSS-Yunzai 的 `plugins/` 目录，配置 `config/mugshot.yaml`（服务端地址与插件 token）
3. 安装 Mugshot APK，同意隐私政策，授予使用情况访问权限，填写服务端地址与设备 token，开启上报

详细部署与三端联调配置见 [docs/DEPLOY.md](docs/DEPLOY.md)。

## 构建

| 产物 | 方式 |
| --- | --- |
| Android APK | GitHub Actions `build-android.yml`，或本地 `cd android && ./gradlew assembleRelease` |
| mugshot-server linux-arm64 | GitHub Actions `build-linux-arm64.yml`（`ubuntu-24.04-arm` 原生构建），或本地 `cd server && CGO_ENABLED=0 go build` |
| 插件 | 纯源码，无构建步骤；Release 附带 `mugshot-plugin_<版本>.tar.gz` |

三端均内置更新检查（启动时与每日一次，GitHub Releases，主源失败自动尝试备用源）与手动更新入口。

## 安全与隐私

- Android 端仅采集前台应用名称、屏幕状态、电池、网络类型等状态字段，不上传输入内容、屏幕文本、剪贴板等敏感数据；权限用途逐条见 `docs/PRIVACY.md`
- 首次启动展示隐私政策，用户同意前不采集、不上报任何数据（见 `docs/PRIVACY.md`）
- 设备端与插件端使用独立 token 鉴权，服务端校验 device_id 与 token 绑定关系
- 服务端默认仅保留最近 7 天（可配置）数据；生产环境建议通过反向代理启用 TLS

## License

MIT
