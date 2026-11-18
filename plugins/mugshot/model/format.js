// Mugshot 上报数据 -> 渲染模板数据 的字段映射与格式化
import { config } from "./config.js"
import { cmd } from "./alias.js"

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

/** 电池温度，读不到时返回 null */
export function batteryTempC(payload) {
  const t = Number(payload?.battery_temp_c)
  return Number.isFinite(t) && t > 0 ? t : null
}

export function tempText(payload) {
  const t = batteryTempC(payload)
  return t === null ? "-" : `${t.toFixed(1)}°C`
}

/** 后台应用名称串（最近用过、当前不在前台），最多 5 个 */
export function backgroundApps(payload) {
  const list = Array.isArray(payload?.background_apps) ? payload.background_apps : []
  return list
    .slice(0, 5)
    .map(a => a?.app || a?.package || "")
    .filter(Boolean)
}

export function batteryLevel(payload) {
  const lvl = payload?.battery_level
  return typeof lvl === "number" && lvl >= 0 ? lvl : null
}

export function batteryClass(level) {
  if (level === null) return "unknown"
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
  // 分屏/小窗等多窗口：多个应用同时可见时全部列出（Android 端按焦点排序、最多 3 个）
  const multi = Array.isArray(p.foreground_apps)
    ? p.foreground_apps.map(a => (a && (a.app || a.package)) || "").filter(Boolean)
    : []
  if (multi.length > 1) return multi.join(" / ")
  if (p.foreground_app) return p.foreground_app
  if (p.foreground_package) return p.foreground_package
  // 区分“未授权/息屏拿不到”与“确实无应用”，避免笼统的“未知”
  if (p.screen_on === false) return "未获取（息屏中）"
  return "未获取（需授权使用情况访问 / 息屏）"
}

/** 最近上报时间戳：优先 last_report_at，兼容只存了 last_report 的旧服务端数据 */
export function reportTimestamp(d) {
  if (!d) return 0
  return Number(d.last_report_at) || Number(d.last_report?.timestamp) || 0
}

/** 设备 -> 列表/详情通用视图字段 */
export function deviceView(d) {
  if (!d) return {}
  const model = d.model || d.Model || ""
  const reportTs = reportTimestamp(d)
  return {
    device_id: d.device_id || "-",
    device_id_short: maskDeviceID(d.device_id),
    device_model: model || "未知型号",
    device_name: d.device_name || "未命名设备",
    online: !!d.online,
    last_heartbeat: formatTime(d.last_heartbeat),
    last_heartbeat_ago: formatAgo(d.last_heartbeat),
    last_report_at: formatTime(reportTs),
    last_report_ago: reportTs ? formatAgo(reportTs) : "从未上报",
    last_report_short: formatShortTime(reportTs),
    report_count: d.report_count || 0,
    fresh_count: d.fresh_count || 0,
    first_seen: formatTime(d.first_seen),
  }
}

/**
 * 手表 kcal 单位兜底：官方协议里卡路里字段是「卡」（8685 步回 129240），
 * 旧版 App 会原样上报。每步超过 0.5 视为卡，换算成千卡后再展示。
 */
function normalizeWatchKcal(steps, kcal) {
  if (!Number.isFinite(kcal) || kcal <= 0) return kcal
  if (Number.isFinite(steps) && steps > 0) {
    return kcal / steps > 0.5 ? Math.round(kcal / 1000) : kcal
  }
  return kcal >= 1000 ? Math.round(kcal / 1000) : kcal
}

/** 手表 payload -> 卡片字段；未开启/未连接时字段留空，由模板决定是否展示 */
export function watchView(p) {
  const w = p?.watch
  if (!w) return { watch_on: false, watch_connected: false, watch_name: "", watch_line: "" }
  const connected = !!w.connected
  const battery = Number(w.battery)
  const hasBattery = Number.isFinite(battery) && battery >= 0
  const num = v => {
    const n = Number(v)
    return Number.isFinite(n) && n >= 0 ? n : null
  }
  const steps = num(w.steps)
  const kcalRaw = num(w.kcal)
  const kcal = kcalRaw === null ? null : normalizeWatchKcal(steps, kcalRaw)
  const dist = num(w.distance_m)
  const hr = num(w.heart_rate)
  const spo2 = num(w.spo2)
  const sleep = num(w.sleep_min)
  const bp = String(w.blood_pressure || "").trim()
  const heartText = hr ? `${hr} 次/分` : "-"
  const sleepText = sleep ? formatMinutes(sleep) : "-"
  const bpText = bp || "-"
  const spo2Text = spo2 ? `${spo2}%` : "-"
  // 摘要行：全部健康项都带中文标签，避免只看到"82% · 5820 步 · 72 bpm"这种缺少语义的拼接
  const line = [
    hasBattery ? `电量 ${Math.round(battery)}%` : "",
    steps !== null ? `步数 ${steps}` : "",
    hr ? `心率 ${hr}` : "",
    bp ? `血压 ${bp}` : "",
    spo2 ? `血氧 ${spo2}%` : "",
    sleep ? `睡眠 ${sleepText}` : "",
    dist !== null ? `距离 ${dist >= 1000 ? `${(dist / 1000).toFixed(1)} 公里` : `${dist} 米`}` : "",
  ].filter(Boolean).join(" · ")
  return {
    watch_on: true,
    watch_connected: connected,
    watch_name: w.name || "HryFine 手表",
    watch_state_text: connected ? "已连接" : "未连接",
    watch_battery_text: hasBattery ? `${Math.round(battery)}%` : "-",
    watch_battery_class: hasBattery ? batteryClass(battery) : "unknown",
    watch_battery_width: hasBattery ? Math.min(100, Math.max(4, Math.round(battery))) : 100,
    watch_steps_text: steps !== null ? `${steps} 步` : "-",
    watch_kcal_text: kcal !== null ? `${kcal} 千卡` : "-",
    watch_distance_text: dist !== null ? (dist >= 1000 ? `${(dist / 1000).toFixed(1)} 公里` : `${dist} 米`) : "-",
    watch_heart_text: heartText,
    watch_sleep_text: sleepText,
    watch_bp_text: bpText,
    watch_spo2_text: spo2Text,
    watch_firmware_text: [w.firmware, w.project].filter(Boolean).join(" / ") || "-",
    watch_features_text: w.features || "-",
    watch_sync_text: w.updated_at ? formatShortTime(w.updated_at) : "-",
    watch_line: line || (connected ? "已连接" : "未连接"),
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
    battery_text: level === null ? "无数据（本机读不到电量）" : `${level}%`,
    battery_class: batteryClass(level),
    battery_width: level === null ? 100 : Math.min(100, Math.max(4, level)),
    charging: batteryStatusText(payload),
    temp_text: tempText(payload),
    background_text: backgroundApps(payload).join("、"),
    foreground_pkg: payload.foreground_package || "",
    activity_text: payload.foreground_activity || "-",
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
    ...watchView(r?.payload),
    report_time: formatShortTime(Number(r?.timestamp) || Number(r?.at) || reportTimestamp(d)),
    source_text: meta.text,
    source_class: meta.cls,
    request_id: maskRequestID(resp.request_id),
    note: resp.note || "",
    note_class: resp.stale ? "warn" : "",
    stale: !!resp.stale,
  }
  // 详情模式：最近上报记录（服务端 recent 为正序，倒序展示最新在前）
  // 服务端 recent 条目是扁平的 {at,app,battery,screen}；旧数据可能带 payload，两种都兼容
  const recent = Array.isArray(d.recent) ? d.recent.slice(-10).reverse() : []
  view.recent = recent.map(h => {
    const p = h.payload || {}
    const screenOn = typeof h.screen === "boolean" ? h.screen : p.screen_on
    const battery = typeof h.battery === "number" ? h.battery : p.battery_level
    return {
      time: formatShortTime(h.at || p.timestamp),
      app: h.app || p.foreground_app || p.foreground_package || "未知应用",
      screen: typeof screenOn === "boolean" ? screenText(screenOn) : "-",
      battery: typeof battery === "number" && battery >= 0 ? `${battery}%` : "未知",
    }
  })
  return view
}

export function commandFor(name) {
  return `${cmd("查看")} ${name}`
}

/** 全部设备状态卡片：每台设备的状态 + 使用情况（前台应用/电池/屏幕/网络/充电/最近上报） */
export function allStatusView(d) {
  const p = d?.last_report?.payload || {}
  const level = batteryLevel(p)
  const usage = Array.isArray(p.usage_24h) ? p.usage_24h : []
  const usageMax = usage.reduce((m, u) => Math.max(m, Number(u?.min) || 0), 1)
  return {
    ...deviceView(d),
    ...watchView(p),
    appText: foregroundText(p),
    batteryText: level === null ? "无数据（本机读不到电量）" : `${level}%`,
    battery_class: batteryClass(level),
    battery_width: level === null ? 100 : Math.min(100, Math.max(4, level)),
    tempText: tempText(p),
    backgroundText: backgroundApps(p).join("、"),
    screenText: p.screen_on === undefined ? "无数据" : screenText(p.screen_on),
    networkText: p.network_type ? networkText(p) : "无数据",
    chargingText: p.charging || p.battery_status ? batteryStatusText(p) : "无数据",
    report_time_text: reportTimestamp(d) ? formatAgo(reportTimestamp(d)) : "从未上报",
    usage_total: usage.length ? formatMinutes(usage.reduce((sum, u) => sum + (Number(u?.min) || 0), 0)) : "",
    usage_count: usage.length ? `，共 ${usage.length} 个应用` : "",
    usage: [...usage].sort((a, b) => (Number(b?.min) || 0) - (Number(a?.min) || 0)).map(u => ({
      app: u?.app || u?.pkg || "未知应用",
      time: formatMinutes(u?.min),
      count_text: typeof u?.count === "number" && u.count > 0 ? `${u.count}次` : "",
      width: Math.min(100, Math.max(6, Math.round(((Number(u?.min) || 0) / usageMax) * 100))),
    })),
  }
}

/** 分钟数 -> "x小时y分" / "y分钟" */
export function formatMinutes(min) {
  const m = Math.max(0, Math.round(Number(min) || 0))
  if (m < 1) return "不足1分钟"
  const h = Math.floor(m / 60)
  const r = m % 60
  if (!h) return `${r}分钟`
  return r ? `${h}小时${r}分` : `${h}小时`
}

export function pluginFooter() {
  // 卡片会被转发到群聊，页脚只放品牌名，不暴露服务端地址
  return "Mugshot"
}
