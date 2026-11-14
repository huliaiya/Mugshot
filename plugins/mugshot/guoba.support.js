// 锅巴面板（Guoba）适配层。
//
// 契约参考 guoba-plugin-next：
//   - 插件目录下的 guoba.support.js 导出 supportGuoba()
//   - pluginInfo：面板左侧菜单与插件列表展示的信息
//   - configInfo.schemas：可视化表单字段定义
//   - configInfo.getConfigData()：把当前配置读给面板
//   - configInfo.setConfigData(data, { Result })：面板保存后写回配置
//   - configInfo.actions：面板按钮触发的动作
//
// 配置仍然落在 config/mugshot.yaml（由 makeConfig 管理），
// 与手改 YAML、指令内修改共用同一份数据，保存后无需重启 Yunzai。
import { config, configSave } from "./model/config.js"
import server from "./model/server.js"

/* global logger */

// 面板表单字段与 config/mugshot.yaml 的键一一对应
const FIELD_SERVER_URL = "server_url"
const FIELD_TOKEN = "token"
const FIELD_PLUGIN_TOKEN = "plugin_token"
const FIELD_DETAIL_FRESH = "detail_fresh"
const FIELD_FRESH_TIMEOUT = "fresh_timeout_ms"
const FIELD_FRESH_COOLDOWN = "fresh_cooldown_ms"
const FIELD_BTN_PER_ROW = "device_btn_per_row"
const FIELD_GITHUB_REPO = "github_repo"
const FIELD_AUTO_UPDATE = "auto_update_check"

/** 数值字段：容错解析并夹在合法区间内 */
function num(value, def, min, max) {
  let n = Number(value)
  if (!Number.isFinite(n)) n = def
  if (typeof min === "number" && n < min) n = min
  if (typeof max === "number" && n > max) n = max
  return n
}

/** 地址规范化：去掉首尾空白与结尾斜杠 */
function normalizeUrl(value) {
  return String(value ?? "")
    .trim()
    .replace(/\/+$/, "")
}

/** 布尔字段：面板可能传来字符串 */
function bool(value, def) {
  if (typeof value === "boolean") return value
  if (value === "true" || value === 1 || value === "1") return true
  if (value === "false" || value === 0 || value === "0") return false
  return def
}

/** 读取当前配置，组装成面板表单的数据 */
function currentData() {
  return {
    [FIELD_SERVER_URL]: normalizeUrl(config.server_url),
    [FIELD_TOKEN]: String(config.token ?? ""),
    [FIELD_PLUGIN_TOKEN]: String(config.plugin_token ?? ""),
    [FIELD_DETAIL_FRESH]: config.detail_fresh !== false,
    [FIELD_FRESH_TIMEOUT]: num(config.fresh_timeout_ms, 8000, 1000, 60000),
    [FIELD_FRESH_COOLDOWN]: num(config.fresh_cooldown_ms, 10000, 0, 600000),
    [FIELD_BTN_PER_ROW]: num(config.device_btn_per_row, 5, 1, 10),
    [FIELD_GITHUB_REPO]: String(config.github_repo ?? "").trim(),
    [FIELD_AUTO_UPDATE]: config.auto_update_check !== false,
  }
}

const schemas = [
  {
    label: "服务端连接",
    component: "SOFT_GROUP_BEGIN",
  },
  {
    field: FIELD_SERVER_URL,
    label: "服务端地址",
    required: true,
    bottomHelpMessage: "mugshot-server 的访问地址，含端口。例：http://192.168.1.10:8080",
    component: "Input",
    componentProps: {
      placeholder: "http://127.0.0.1:8080",
    },
  },
  {
    field: FIELD_TOKEN,
    label: "统一 Token",
    bottomHelpMessage: "服务端 config.json 里的 token，手机 App 与插件共用同一个",
    component: "InputPassword",
    componentProps: {
      placeholder: "请输入服务端 Token",
    },
  },
  {
    field: FIELD_PLUGIN_TOKEN,
    label: "旧版插件 Token",
    bottomHelpMessage: "兼容旧配置的 plugin_token，填写后作为 token 的后备值",
    component: "InputPassword",
    componentProps: {
      placeholder: "留空表示不使用",
    },
  },
  {
    label: "操作",
    component: "SOFT_GROUP_BEGIN",
  },
  {
    component: "GButtons",
    componentProps: {
      spaceSize: 8,
      buttons: [
        {
          label: "测试连接",
          action: "testConnection",
          type: "primary",
          icon: "mdi:lan-connect",
          tooltip: { title: "用表单里填写的地址与 Token 请求一次 /health", placement: "top" },
          args: [`#{${FIELD_SERVER_URL}}`, `#{${FIELD_TOKEN}}`, `#{${FIELD_PLUGIN_TOKEN}}`],
        },
      ],
    },
  },
  {
    label: "查询行为",
    component: "SOFT_GROUP_BEGIN",
  },
  {
    field: FIELD_DETAIL_FRESH,
    label: "详情默认实时上报",
    bottomHelpMessage: "开启后 #群主详情 会先唤醒设备上报一次",
    component: "Switch",
  },
  {
    field: FIELD_FRESH_TIMEOUT,
    label: "实时上报等待时间（毫秒）",
    bottomHelpMessage: "等待设备按需上报的时长，与服务端 fresh_timeout_ms 保持一致或略大",
    component: "InputNumber",
    componentProps: {
      min: 1000,
      max: 60000,
      placeholder: "8000",
    },
  },
  {
    field: FIELD_FRESH_COOLDOWN,
    label: "实时上报冷却（毫秒）",
    bottomHelpMessage: "同一用户重复触发的最小间隔，冷却期内自动展示缓存数据",
    component: "InputNumber",
    componentProps: {
      min: 0,
      max: 600000,
      placeholder: "10000",
    },
  },
  {
    field: FIELD_BTN_PER_ROW,
    label: "设备按钮每行个数",
    bottomHelpMessage: "设备选择卡片每行放几个按钮，最多 10 个",
    component: "InputNumber",
    componentProps: {
      min: 1,
      max: 10,
      placeholder: "5",
    },
  },
  {
    label: "更新",
    component: "SOFT_GROUP_BEGIN",
  },
  {
    field: FIELD_GITHUB_REPO,
    label: "更新回退仓库",
    bottomHelpMessage: "插件更新时若插件目录的 git 没有 origin，则回退到此仓库拉取 main 分支，格式 owner/repo",
    component: "Input",
    componentProps: {
      placeholder: "owner/repo",
    },
  },
  {
    field: FIELD_AUTO_UPDATE,
    label: "自动检查更新",
    bottomHelpMessage: "启动后与每天定时检查插件更新，仅写日志提示",
    component: "Switch",
  },
]

/**
 * 测试连接：用表单当前值请求服务端 /health，验证地址与 Token 是否可用。
 * @param {Array} args [server_url, token, plugin_token]
 */
async function testConnection(args, { Result }) {
  const [rawUrl = "", rawToken = "", rawPluginToken = ""] = Array.isArray(args) ? args : []
  const base = normalizeUrl(rawUrl || config.server_url)
  if (!base) return Result.error("请先填写服务端地址")
  let url
  try {
    url = new URL(base)
  } catch {
    return Result.error(`服务端地址格式不正确：${base}`)
  }
  const token = String(rawToken || rawPluginToken || config.token || config.plugin_token || "").trim()
  const headers = { "content-type": "application/json" }
  if (token) headers.Authorization = `Bearer ${token}`
  try {
    const res = await fetch(url.origin + "/health", {
      headers,
      signal: AbortSignal.timeout(8000),
    })
    const data = await res.json().catch(() => null)
    if (!res.ok) {
      return Result.error(`连接失败：HTTP ${res.status}${data?.error ? `（${data.error}）` : ""}`)
    }
    const online = data?.devices_online ?? 0
    const total = data?.devices_total ?? 0
    return Result.ok(
      { version: data?.version, devices_online: online, devices_total: total },
      `连接成功：服务端 ${data?.version || "-"}，在线设备 ${online}/${total}`,
    )
  } catch (err) {
    return Result.error(`连接失败：${err.message}`)
  }
}

export function supportGuoba() {
  return {
    pluginInfo: {
      name: "mugshot",
      title: "Mugshot 设备状态",
      description: "查看 Android 设备状态、触发实时上报，并统一管理服务端与插件更新",
      author: ["huliaiya"],
      authorLink: ["https://github.com/huliaiya"],
      link: "https://github.com/huliaiya/Mugshot",
      isV3: true,
      isV2: false,
      showInMenu: true,
      icon: "mdi:monitor-cellphone",
      iconColor: "#2f80ed",
    },
    configInfo: {
      schemas,
      getConfigData() {
        return currentData()
      },
      async setConfigData(data, { Result }) {
        const patch = {}
        if (FIELD_SERVER_URL in data) patch[FIELD_SERVER_URL] = normalizeUrl(data[FIELD_SERVER_URL])
        if (FIELD_TOKEN in data) patch[FIELD_TOKEN] = String(data[FIELD_TOKEN] ?? "").trim()
        if (FIELD_PLUGIN_TOKEN in data) {
          patch[FIELD_PLUGIN_TOKEN] = String(data[FIELD_PLUGIN_TOKEN] ?? "").trim()
        }
        if (FIELD_DETAIL_FRESH in data) {
          patch[FIELD_DETAIL_FRESH] = bool(data[FIELD_DETAIL_FRESH], config.detail_fresh !== false)
        }
        if (FIELD_FRESH_TIMEOUT in data) {
          patch[FIELD_FRESH_TIMEOUT] = num(data[FIELD_FRESH_TIMEOUT], 8000, 1000, 60000)
        }
        if (FIELD_FRESH_COOLDOWN in data) {
          patch[FIELD_FRESH_COOLDOWN] = num(data[FIELD_FRESH_COOLDOWN], 10000, 0, 600000)
        }
        if (FIELD_BTN_PER_ROW in data) {
          patch[FIELD_BTN_PER_ROW] = num(data[FIELD_BTN_PER_ROW], 5, 1, 10)
        }
        if (FIELD_GITHUB_REPO in data) {
          patch[FIELD_GITHUB_REPO] = String(data[FIELD_GITHUB_REPO] ?? "").trim()
        }
        if (FIELD_AUTO_UPDATE in data) {
          patch[FIELD_AUTO_UPDATE] = bool(data[FIELD_AUTO_UPDATE], config.auto_update_check !== false)
        }

        const before = currentData()
        Object.assign(config, patch)
        try {
          await configSave()
        } catch (err) {
          // 落盘失败时回滚内存里的值，避免面板显示已保存而实际未生效
          Object.assign(config, before)
          return Result.error(`保存失败：${err.message}`)
        }

        const connChanged =
          patch[FIELD_SERVER_URL] !== undefined &&
          patch[FIELD_SERVER_URL] !== before[FIELD_SERVER_URL]
        const tokenChanged =
          (patch[FIELD_TOKEN] !== undefined && patch[FIELD_TOKEN] !== before[FIELD_TOKEN]) ||
          (patch[FIELD_PLUGIN_TOKEN] !== undefined &&
            patch[FIELD_PLUGIN_TOKEN] !== before[FIELD_PLUGIN_TOKEN])
        if (connChanged || tokenChanged) {
          // 地址或 Token 变了，重连订阅，避免旧连接一直挂着
          server
            .restartWs()
            .catch(err => logger.debug(`[Mugshot] 面板保存后重连订阅失败: ${err.message}`))
        }
        return Result.ok({}, "保存成功~")
      },
      actions: {
        testConnection,
      },
    },
  }
}
