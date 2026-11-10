// Mugshot 插件配置：通过框架的 makeConfig 落盘到 config/mugshot.yaml
// 依据：lib/plugins/config.js（makeConfig(name, default, keep, opts)）
import makeConfig from "../../../lib/plugins/config.js"

export const PLUGIN_NAME = "mugshot"
export const PLUGIN_VERSION = "1.0.0"
export const GITHUB_REPO_DEFAULT = "your-github-username/Mugshot"

const DEFAULT = {
  // mugshot-server 地址（含端口），例如 http://192.168.1.10:8080 或 https://mugshot.example.com
  server_url: "http://127.0.0.1:8080",
  // 服务端 config.json 中的 plugin_token
  plugin_token: "",
  // fresh 查询等待设备按需上报的超时（毫秒），与服务端 fresh_timeout_ms 保持一致或略大
  fresh_timeout_ms: 8000,
  // 同一用户触发 fresh 查询的最小间隔（毫秒），冷却期内自动降级为缓存查询
  fresh_cooldown_ms: 10000,
  // #mugshot详情 是否默认触发按需上报
  detail_fresh: true,
  // 插件/服务端更新检查使用的 GitHub 仓库
  github_repo: GITHUB_REPO_DEFAULT,
  // 启动时与每日定时检查更新（仅日志提示，不自动更新）
  auto_update_check: true,
  // 设备选择按钮每行个数（每行最多 10，总行数最多 5）
  device_btn_per_row: 5,
}

const { config, configSave, configFile } = await makeConfig(PLUGIN_NAME, DEFAULT, {}, { watch: true })

export { config, configSave, configFile }
