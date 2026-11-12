// Mugshot 上报数据 -> 渲染模板数据 的字段映射与格式化
import { config } from "./config.js"

const UNITS = [
  { limit: 60, div: 1, unit: "秒" },
  { limit: 3600, div: 60, unit: "分钟" },
  { limit: 86400, div: 3600, unit: "小时" },
]

export function pad(n) {
  return String(n).padStart(2, "0")
}

export function formatTime(ms) {
  if (!ms) return "从未上报"
  const d = new Date(ms)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

export function formatShortTime(ms) {
  if (!ms) return "从未"
  const d = new Date(ms)
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

export function formatAgo(ms) {
  if (!ms) return "无记录"
  const diff = Math.max(0, Date.now() - ms) / 1000
  if (diff < 10) return "刚刚"
  for (const { limit, div, unit } of UNITS) {
    if (diff < limit) return `${Math.floor(diff / div)} ${unit}前`
  }
  return `${Math.floor(diff / 86400)} 天前`
}

export function screenText(screenOn) {
  return screenOn ? "亮屏中" : "息屏"
}

export function batteryStatusText(payload) {
  const p = payload || {}
  if (p.battery_status === "full") return "已充满"
  if (p.charging) {
    switch (p.charging_source) {
      case "ac": return "充电中（交流电）"
      case "usb": return "充电中（USB）"
      case "wireless": return "充电中（无线）"
      default: return "充电中"
    }
  }
  if (p.battery_status === "charging") return "充电中"
  return "未充电"
}

export function networkText(p) {
  switch (p?.network_type) {
    case "wifi": return "Wi-Fi"
    case "mobile": return p.network_detail ? `移动网络（${p.network_detail}）` : "移动网络"
    case "none": return "无网络"
    default: return "未知"
  }
}

export function batteryLevel(payload) {
  const lvl = payload?.battery_level
  return typeof lvl === "number" && lvl >= 0 ? lvl : null
}

export function batteryClass(level) {
  if (level === null) return "mid"
  if (level <= 20) return "lo"
  if (level <= 50) return "mid"
  return "hi"
}

export function dataSourceMeta(source) {
  if (source === "live") return { text: "实时数据 LIVE", cls: "live" }
  if (source === "none") return { text: "暂无数据", cls: "none" }
  return { text: "缓存数据 CACHE", cls: "cache" }
}

export function maskDeviceID(id) {
  if (!id) return "-"
  if (id.length <= 12) return id
  return id.slice(0, 8) + "****" + id.slice(-4)
}

export function maskRequestID(id) {
  if (!id) return ""
  if (id.length <= 10) return id
  return id.slice(0, 6) + "…" + id.slice(-4)
}

export function foregroundText(payload) {
  const p = payload || {}
  return p.foreground_app || p.foreground_package || "未知（可能息屏或刚解锁）"
}

/** 设备 -> 列表/详情通用视图字段 */
export function deviceView(d) {
  if (!d) return {}
  const model = d.model || d.Model || ""
  return {
    device_id: d.device_id || "-",
    device_id_short: maskDeviceID(d.device_id),
    device_model: model || "未知型号",
    device_name: d.device_name || "未命名设备",
    online: !!d.online,
    last_heartbeat: formatTime(d.last_heartbeat),
    last_heartbeat_ago: formatAgo(d.last_heartbeat),
    last_report_at: formatTime(d.last_report_at),
    last_report_ago: formatAgo(d.last_report_at),
    last_report_short: formatShortTime(d.last_report_at),
    report_count: d.report_count || 0,
    fresh_count: d.fresh_count || 0,
    first_seen: formatTime(d.first_seen),
  }
}

/** 上报 payload -> 状态卡字段 */
export function basicView(p) {
  const payload = p || {}
  const level = batteryLevel(payload)
  return {
    has_report: Object.keys(payload).length > 0,
    foreground_app: foregroundText(payload),
    screen: screenText(payload.screen_on),
    network: networkText(payload),
    battery: level === null ? "?" : level,
    battery_class: batteryClass(level),
    battery_width: level === null ? 0 : Math.min(100, Math.max(4, level)),
    charging: batteryStatusText(payload),
  }
}

/** /status 响应 -> status.html / detail.html 渲染数据 */
export function statusView(resp) {
  const d = resp.device || {}
  const r = resp.report || d.last_report || null
  const meta = dataSourceMeta(resp.data_source)
  const view = {
    ...deviceView(d),
    ...basicView(r?.payload),
    report_time: formatShortTime(r?.at),
    source_text: meta.text,
    source_class: meta.cls,
    request_id: maskRequestID(resp.request_id),
    note: resp.note || "",
    stale: !!resp.stale,
  }
  // 详情模式：最近上报记录（服务端 recent 为正序，倒序展示最新在前）
  const recent = Array.isArray(d.recent) ? d.recent.slice(-10).reverse() : []
  view.recent = recent.map(h => ({
    time: formatShortTime(h.at),
    app: h.payload?.foreground_app || h.payload?.foreground_package || "-",
    screen: screenText(h.payload?.screen_on),
    battery: typeof h.payload?.battery_level === "number" ? `${h.payload.battery_level}%` : "-",
  }))
  return view
}

export function commandFor(name) {
  return `#群主查看 ${name}`
}

export function pluginFooter() {
  const host = config.server_url ? config.server_url.replace(/^https?:\/\//, "") : ""
  return host ? `server ${host}` : "Mugshot"
}
