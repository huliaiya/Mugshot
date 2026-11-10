// Mugshot 插件与 mugshot-server 的通信层：
//  - HTTP 查询（/devices /status /admin/*），使用插件 token 鉴权
//  - WebSocket 订阅（/ws/plugin），接收设备上线/上报广播，断线指数退避重连
// WebSocket 客户端使用 Yunzai 根目录自带的 ws 依赖（TRSS-Yunzai package.json
// 依赖 ws@8），通过动态导入加载，缺失时自动降级为纯 HTTP 模式。
import { config } from "./config.js"

/* global logger, Bot */

const DEVICE_EVENTS = new Set(["hello", "device_update", "device_online", "device_offline"])

class ServerClient {
  constructor() {
    this.devicesCache = [] // 订阅广播维护的设备列表快照
    this.ws = null
    this.wsState = "off" // off | connecting | open
    this.reconnectDelay = 1000
    this.reconnectTimer = null
    this.closed = false
  }

  // ---- 基础 ----

  get baseUrl() {
    return String(config.server_url || "").replace(/\/+$/, "")
  }

  get wsUrl() {
    return this.baseUrl.replace(/^http/, "ws") + "/ws/plugin?token=" + encodeURIComponent(config.plugin_token || "")
  }

  headers(extra = {}) {
    return {
      Authorization: `Bearer ${config.plugin_token || ""}`,
      "content-type": "application/json",
      ...extra,
    }
  }

  async request(path, { method = "GET", timeoutMs = 15000, body } = {}) {
    const url = this.baseUrl + path
    const opts = { method, headers: this.headers(), signal: AbortSignal.timeout(timeoutMs) }
    if (body !== undefined) opts.body = JSON.stringify(body)
    const res = await fetch(url, opts)
    const data = await res.json().catch(() => null)
    if (!res.ok) {
      const msg = data?.error || `HTTP ${res.status}`
      const err = new Error(msg)
      err.status = res.status
      err.data = data
      throw err
    }
    return data
  }

  // ---- 查询 API ----

  getHealth() {
    return this.request("/health")
  }

  async getDevices() {
    // 优先取广播缓存的快照，取不到再走 HTTP
    if (this.devicesCache.length) return { devices: this.devicesCache, count: this.devicesCache.length, cached: true }
    const data = await this.request("/devices")
    this.devicesCache = data.devices || []
    return data
  }

  async getStatus(device, fresh = true) {
    const q = new URLSearchParams({ device: String(device ?? ""), fresh: fresh ? "1" : "0" })
    if (fresh) q.set("timeout_ms", String(config.fresh_timeout_ms || 8000))
    const data = await this.request(`/status?${q}`, { timeoutMs: (config.fresh_timeout_ms || 8000) + 10000 })
    if (data?.device) this.#applyDeviceSnapshot(data.device)
    return data
  }

  checkServerUpdate() {
    return this.request("/admin/update_check", { method: "POST", timeoutMs: 30000 })
  }

  applyServerUpdate() {
    return this.request("/admin/update", { method: "POST", timeoutMs: 30000 })
  }

  // ---- WebSocket 订阅 ----

  async startWs() {
    if (this.closed || this.wsState !== "off") return
    if (!config.plugin_token) {
      logger.warn("[Mugshot] 未配置 plugin_token，跳过 WebSocket 订阅（HTTP 查询仍可用）")
      return
    }
    let WebSocketImpl = globalThis.WebSocket
    if (!WebSocketImpl) {
      try {
        WebSocketImpl = (await import("ws")).default
      } catch {
        logger.warn("[Mugshot] 未找到可用的 WebSocket 客户端（Node>=22 内置或 ws 包），插件将仅使用 HTTP 查询")
        return
      }
    }
    this.wsState = "connecting"
    try {
      const ws = new WebSocketImpl(this.wsUrl)
      // 兼容两种 WebSocket 实现：ws 包（EventEmitter 风格）与 Node>=22 全局 WebSocket
      if (typeof ws.on === "function") {
        ws.on("open", () => this.#onOpen())
        ws.on("message", raw => this.#onWsMessage(raw))
        ws.on("close", () => this.#scheduleReconnect())
        ws.on("error", err => logger.debug(`[Mugshot] WebSocket 错误: ${err.message}`))
      } else {
        ws.onopen = () => this.#onOpen()
        ws.onmessage = ev => this.#onWsMessage(ev.data)
        ws.onclose = () => this.#scheduleReconnect()
        ws.onerror = err => logger.debug(`[Mugshot] WebSocket 错误: ${err?.message || "unknown"}`)
      }
      this.ws = ws
    } catch (err) {
      this.wsState = "off"
      logger.warn(`[Mugshot] WebSocket 连接失败: ${err.message}，10 秒后重试`)
      this.#scheduleReconnect()
    }
  }

  #onOpen() {
    this.wsState = "open"
    this.reconnectDelay = 1000
    logger.mark("[Mugshot] 已连接 mugshot-server WebSocket 订阅")
  }

  #onWsMessage(raw) {
    try {
      const msg = JSON.parse(String(raw))
      if (!DEVICE_EVENTS.has(msg.type)) return
      const data = msg.data || {}
      if (msg.type === "hello") {
        this.devicesCache = data.devices || []
      } else if ((msg.type === "device_update" || msg.type === "device_online") && data.device) {
        this.#applyDeviceSnapshot(data.device)
      } else if (msg.type === "device_offline" && data.device_id) {
        const d = this.devicesCache.find(i => i.device_id === data.device_id)
        if (d) d.online = false
      }
    } catch (err) {
      logger.debug(`[Mugshot] WebSocket 消息解析失败: ${err.message}`)
    }
  }

  #applyDeviceSnapshot(device) {
    const list = this.devicesCache
    const idx = list.findIndex(i => i.device_id === device.device_id)
    if (idx >= 0) list[idx] = { ...list[idx], ...device }
    else list.push(device)
  }

  #scheduleReconnect() {
    if (this.ws) {
      try { this.ws.removeAllListeners?.() } catch {}
      try { this.ws.onopen = null; this.ws.onmessage = null; this.ws.onclose = null; this.ws.onerror = null } catch {}
      try { this.ws.close() } catch {}
      this.ws = null
    }
    this.wsState = "off"
    if (this.closed || this.reconnectTimer) return
    const delay = Math.min(this.reconnectDelay, 5 * 60 * 1000)
    this.reconnectDelay = Math.min(this.reconnectDelay * 2, 5 * 60 * 1000)
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null
      this.startWs().catch(() => {})
    }, delay)
  }

  close() {
    this.closed = true
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer)
    if (this.ws) {
      try { this.ws.close() } catch {}
      this.ws = null
    }
    this.wsState = "off"
  }
}

// 模块级单例：热重载时旧模块实例的 close 会被 addCleanup 调用
const client = new ServerClient()
export default client

export function pickServerClient() {
  return client
}
