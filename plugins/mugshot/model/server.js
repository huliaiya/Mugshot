// Mugshot 插件与 mugshot-server 的通信层：
//  - HTTP 查询（/devices /status /admin/*），使用插件 token 鉴权
//  - WebSocket 订阅（/ws/plugin），接收设备上线/上报广播，断线指数退避重连
//  - Token 自愈：服务端轮换 Token 后旧值立即失效，这里在收到 401 时
//    从本机服务端免授权接口读取新 Token 并重试一次
// WebSocket 客户端使用 Yunzai 根目录自带的 ws 依赖（TRSS-Yunzai package.json
// 依赖 ws@8），通过动态导入加载，缺失时自动降级为纯 HTTP 模式。
import os from "node:os"
import { config, configSave } from "./config.js"

/* global logger, Bot */

const DEVICE_EVENTS = new Set(["hello", "device_update", "device_online", "device_offline"])

// isLocalHost 判断地址是否指向本机：回环地址、localhost 或本机网卡的 IP。
// 只有指向本机时才允许走免授权的 Token 同步，避免误读另一台机器的服务端。
function isLocalHost(host) {
  const h = String(host || "").trim().toLowerCase().replace(/^\[|\]$/g, "")
  if (!h || h === "localhost" || h === "::1") return true
  if (h.startsWith("127.")) return true
  const addrs = []
  for (const list of Object.values(os.networkInterfaces() || {})) {
    for (const item of list || []) addrs.push(item.address)
  }
  return addrs.includes(h)
}

// localBaseCandidates 返回读取本机 Token 的候选地址：
// 用户填的地址本身，以及把主机名换成 127.0.0.1 的同端口地址。
// 服务端只允许回环来源读取 Token，同机但填了局域网 IP 时走 127.0.0.1 才能读到。
function localBaseCandidates(base) {
  const trimmed = String(base || "").replace(/\/+$/, "")
  const out = trimmed ? [trimmed] : []
  let u
  try {
    u = new URL(trimmed)
  } catch {
    return out
  }
  if (!u.hostname) return out
  const port = u.port || (u.protocol === "https:" ? "443" : "80")
  const loop = `${u.protocol}//127.0.0.1:${port}`
  if (!out.includes(loop)) out.push(loop)
  return out
}

class ServerClient {
  constructor() {
    this.devicesCache = [] // 订阅广播维护的设备列表快照
    this.cacheTs = 0 // 快照最近一次更新时间；WS 断开且快照过期后回退 HTTP
    this.ws = null
    this.wsState = "off" // off | connecting | open
    this.reconnectDelay = 1000
    this.reconnectTimer = null
    this.reconnectPending = false
    this.closed = false
  }

  // ---- 基础 ----

  get baseUrl() {
    return String(config.server_url || "").replace(/\/+$/, "")
  }

  // 统一 Token：优先使用 token，其次兼容旧配置的 plugin_token
  get token() {
    return String(config.token || config.plugin_token || "")
  }

  get wsUrl() {
    return this.baseUrl.replace(/^http/, "ws") + "/ws/plugin?token=" + encodeURIComponent(this.token)
  }

  headers(extra = {}) {
    return {
      Authorization: `Bearer ${this.token}`,
      "content-type": "application/json",
      ...extra,
    }
  }

  async request(path, { method = "GET", timeoutMs = 15000, body } = {}) {
    let res = await this.#fetch(path, { method, timeoutMs, body })
    if (res.status === 401) {
      // 服务端轮换过 Token：先从本机服务端同步新值再重试一次
      const tok = await this.#syncTokenFromLocal()
      if (tok) res = await this.#fetch(path, { method, timeoutMs, body })
    }
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

  async #fetch(path, { method, timeoutMs, body }) {
    const opts = { method, headers: this.headers(), signal: AbortSignal.timeout(timeoutMs) }
    if (body !== undefined) opts.body = JSON.stringify(body)
    return await fetch(this.baseUrl + path, opts)
  }

  // syncTokenFromLocal 从本机服务端免授权读取统一 Token。
  // 服务端只接受回环来源，远程服务端的 Token 只能由用户手动配置。
  async #syncTokenFromLocal() {
    const base = this.baseUrl
    if (!base) return ""
    let host = ""
    try {
      host = new URL(base).hostname
    } catch {
      return ""
    }
    if (!isLocalHost(host)) return ""
    for (const cand of localBaseCandidates(base)) {
      let data = null
      try {
        const res = await fetch(cand + "/local/config", { signal: AbortSignal.timeout(5000) })
        if (!res.ok) continue
        data = await res.json().catch(() => null)
      } catch {
        continue
      }
      const tok = String(data?.token || "")
      if (!tok) continue
      if (tok !== this.token) {
        config.token = tok
        config.plugin_token = ""
        try {
          await configSave()
        } catch (err) {
          logger.warn(`[Mugshot] Token 已同步但写入配置文件失败: ${err.message}`)
        }
        logger.info(`[Mugshot] Token 已失效，已从本机服务端自动同步新 Token（${cand}）`)
      }
      return tok
    }
    return ""
  }

  // ---- 查询 API ----

  getHealth() {
    return this.request("/health")
  }

  async getDevices() {
    // 优先取广播缓存的快照：仅当 WS 在线或快照足够新（30 秒内）才信任，
    // 避免长时间断线后列表里停留着过期的在线状态
    const fresh = Date.now() - this.cacheTs < 30_000
    if (this.wsState === "open" || fresh) {
      if (this.devicesCache.length) {
        return { devices: this.devicesCache, count: this.devicesCache.length, cached: true }
      }
    }
    const data = await this.request("/devices")
    this.#applySnapshotList(data.devices || [])
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
    if (!this.token) {
      logger.warn("[Mugshot] 未配置 token，跳过 WebSocket 订阅（HTTP 查询仍可用）")
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
        this.#applySnapshotList(data.devices || [])
      } else if ((msg.type === "device_update" || msg.type === "device_online") && data.device) {
        this.#applyDeviceSnapshot(data.device)
      } else if (msg.type === "device_offline" && data.device_id) {
        const d = this.devicesCache.find(i => i.device_id === data.device_id)
        if (d) d.online = false
      }
      this.cacheTs = Date.now()
    } catch (err) {
      logger.debug(`[Mugshot] WebSocket 消息解析失败: ${err.message}`)
    }
  }

  #applySnapshotList(list) {
    this.devicesCache = list
    this.cacheTs = Date.now()
  }

  #applyDeviceSnapshot(device) {
    const list = this.devicesCache
    const idx = list.findIndex(i => i.device_id === device.device_id)
    if (idx >= 0) list[idx] = { ...list[idx], ...device }
    else list.push(device)
    this.cacheTs = Date.now()
  }

  #scheduleReconnect() {
    if (this.ws) {
      try { this.ws.removeAllListeners?.() } catch {}
      try { this.ws.onopen = null; this.ws.onmessage = null; this.ws.onclose = null; this.ws.onerror = null } catch {}
      try { this.ws.close() } catch {}
      this.ws = null
    }
    this.wsState = "off"
    if (this.closed || this.reconnectTimer || this.reconnectPending) return
    this.reconnectPending = true
    // 鉴权失败时服务端会直接拒绝握手，这里先同步一次 Token 再安排重连，
    // 避免旧 Token 让每次重连都失败
    this.#syncTokenFromLocal()
      .catch(() => "")
      .then(tok => {
        this.reconnectPending = false
        if (this.closed || this.reconnectTimer) return
        if (tok) this.reconnectDelay = 1000
        const delay = Math.min(this.reconnectDelay, 5 * 60 * 1000)
        this.reconnectDelay = Math.min(this.reconnectDelay * 2, 5 * 60 * 1000)
        this.reconnectTimer = setTimeout(() => {
          this.reconnectTimer = null
          this.startWs().catch(() => {})
        }, delay)
      })
  }

  /**
   * 断开当前订阅并重新建立连接。
   * 面板里改了服务端地址或 Token 后调用，避免旧连接一直挂着。
   */
  async restartWs() {
    // 先置 closed，屏蔽旧连接 close 事件触发的自动重连
    this.closed = true
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    this.reconnectPending = false
    if (this.ws) {
      const ws = this.ws
      try { ws.removeAllListeners?.() } catch {}
      try { ws.onopen = null; ws.onmessage = null; ws.onclose = null; ws.onerror = null } catch {}
      try { ws.close() } catch {}
      this.ws = null
    }
    this.wsState = "off"
    this.reconnectDelay = 1000
    this.closed = false
    await this.startWs()
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
