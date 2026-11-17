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

// WebSocket 握手超时：服务端不可达时部分网络会长时间停在 connecting，
// 没有超时就会一直显示"连接中"且不再重试。
const CONNECT_TIMEOUT_MS = 15000
// 广播缓存最长信任时长：WS 看似在线但实际已半开时，超过这个时间就回退 HTTP 拉一次
const CACHE_MAX_AGE_MS = 10 * 60 * 1000

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

// 局域网地址段：本机/内网服务绝大多数是明文 HTTP，公网域名默认 HTTPS。
const PRIVATE_HOST_RE = /^(10\.|127\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|169\.254\.|100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\.)/

// isLocalLike 判断主机名是否属于本机/内网：这类地址按 http 处理更符合实际部署
function isLocalLikeHost(host) {
  const h = String(host || "").trim().toLowerCase().replace(/^\[|\]$/g, "")
  if (!h) return false
  return h === "localhost" || h === "::1" || h === "0.0.0.0" || h.endsWith(".local") || PRIVATE_HOST_RE.test(h)
}

// normalizeScheme 保证 base 地址带 http(s):// scheme。
// 用户常填 `192.168.1.10:8080`、`mugshot.example.com` 这种无 scheme 的地址，
// 直接拼给 fetch / WebSocket 客户端会因不合法而连不上。
// 缺省规则：localhost/内网 IP 用 http（自建服务通常不开 TLS），公网域名用 https。
// 已带 http/https/ws/wss 的输入原样保留（不重复加前缀）。
export function normalizeScheme(base) {
  const trimmed = String(base || "").trim().replace(/\/+$/, "")
  if (!trimmed) return ""
  if (/^(https?|wss?):\/\//i.test(trimmed)) return trimmed
  const hostPart = trimmed.replace(/^[^@]*@/, "").replace(/^\/+/, "").split(/[/?#]/)[0]
  const hostOnly = hostPart.replace(/:\d+$/, "").replace(/^\[|\]$/g, "")
  return `${isLocalLikeHost(hostOnly) ? "http" : "https"}://${trimmed}`
}

// toWsUrl 把 base 地址派生成 ws(s):// URL。
//  https -> wss，http -> ws；已是 ws(s):// 的保持；裸地址先规范成 https 再转 wss。
function toWsUrl(base) {
  const normalized = normalizeScheme(base)
  if (normalized.startsWith("wss://")) return normalized
  if (normalized.startsWith("ws://")) return normalized
  if (normalized.startsWith("https://")) return normalized.replace("https://", "wss://")
  if (normalized.startsWith("http://")) return normalized.replace("http://", "ws://")
  return normalized
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
    this.connectTimer = null // 握手超时定时器
    this.wsGen = 0 // 连接代号：旧连接的异步回调靠它失效
    this.authWarned = false // 401 只提示一次，避免重连刷屏
    this.connectFails = 0 // 连续握手失败次数
  }

  // ---- 基础 ----

  get baseUrl() {
    return normalizeScheme(String(config.server_url || "").trim().replace(/\/+$/, ""))
  }

  // 统一 Token：优先使用 token，其次兼容旧配置的 plugin_token
  get token() {
    return String(config.token || config.plugin_token || "")
  }

  get wsUrl() {
    return toWsUrl(this.baseUrl) + "/ws/plugin?token=" + encodeURIComponent(this.token)
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
      // 服务端轮换过 Token：先从本机服务端同步新值，只有真的变了才重试
      const before = this.token
      const tok = await this.#syncTokenFromLocal()
      if (tok && tok !== before) res = await this.#fetch(path, { method, timeoutMs, body })
    }
    const data = await res.json().catch(() => null)
    if (!res.ok) {
      const msg = res.status === 401
        ? "Token 校验失败：插件与服务端（以及 App）必须使用同一个统一 Token，请到面板检查"
        : data?.error || `HTTP ${res.status}`
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

  async getDevices({ force = false } = {}) {
    // 优先取广播缓存的快照：WS 在线且缓存未过期时才信任；
    // 断线或缓存太旧（WS 半开时不会收到关闭事件）都回退 HTTP，避免列表一直停在旧状态
    if (!force) {
      const age = Date.now() - this.cacheTs
      const usable = (this.wsState === "open" && age < CACHE_MAX_AGE_MS) || age < 30_000
      if (usable && this.devicesCache.length) {
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
    if (fresh && device) logger.debug(`[Mugshot] 请求设备 ${device} 新鲜状态（fresh=1）`)
    const data = await this.request(`/status?${q}`, { timeoutMs: (config.fresh_timeout_ms || 8000) + 10000 })
    if (data?.device) this.#applyDeviceSnapshot(data.device)
    return data
  }

  // 注意：插件不提供任何"更新服务端"的调用——服务端更新由其自身的
  // 定时检查与 GUI"设置"页负责，插件侧只更新插件本体（见 model/updater.js）。

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
    const gen = ++this.wsGen
    // settled 保证同一条连接只触发一次收尾，避免 error/close 双触发重复排重连
    let settled = false
    try {
      const ws = new WebSocketImpl(this.wsUrl)
      // 兼容两种 WebSocket 实现：ws 包（EventEmitter 风格）与 Node>=22 全局 WebSocket
      if (typeof ws.on === "function") {
        ws.on("open", () => gen === this.wsGen && this.#onOpen())
        ws.on("message", raw => gen === this.wsGen && this.#onWsMessage(raw))
        ws.on("close", (code, reason) => {
          if (gen !== this.wsGen || settled) return
          settled = true
          const text = reason ? String(reason) : ""
          if (code === 1008 || code === 4001) logger.warn(`[Mugshot] 服务端拒绝订阅（${code}${text ? " " + text : ""}），请检查 Token`)
          else if (this.wsState !== "open") this.#noteConnectFailure()
          this.#scheduleReconnect()
        })
        ws.on("unexpected-response", (req, res) => {
          if (gen !== this.wsGen) return
          if (res?.statusCode === 401) this.#warnAuth()
          else logger.warn(`[Mugshot] WebSocket 握手被拒绝：HTTP ${res?.statusCode ?? "?"}`)
          // 握手被拒绝不会自动关闭，这里主动收尾，避免白等握手超时
          try { req?.destroy?.() } catch {}
          try { ws.terminate?.() } catch {}
        })
        ws.on("error", err => {
          logger.debug(`[Mugshot] WebSocket 错误: ${err.message}`)
          // 握手失败时部分实现只发 error 不发 close，这里主动收尾触发重连
          if (gen === this.wsGen && this.wsState !== "open" && !settled) {
            settled = true
            this.#noteConnectFailure()
            try { ws.terminate?.() } catch {}
            this.#scheduleReconnect()
          }
        })
      } else {
        ws.onopen = () => gen === this.wsGen && this.#onOpen()
        ws.onmessage = ev => gen === this.wsGen && this.#onWsMessage(ev.data)
        ws.onclose = ev => {
          if (gen !== this.wsGen || settled) return
          settled = true
          if (ev?.code === 1008 || ev?.code === 4001) logger.warn(`[Mugshot] 服务端拒绝订阅（${ev.code}），请检查 Token`)
          else if (this.wsState !== "open") this.#noteConnectFailure()
          this.#scheduleReconnect()
        }
        ws.onerror = () => {
          logger.debug("[Mugshot] WebSocket 错误")
          // 全局 WebSocket 在非 101 握手时只触发 error（无状态码），主动收尾触发重连。
          // 不能在 error 里再调 close()：undici 会同步再抛一次 error，形成递归爆栈。
          if (gen === this.wsGen && this.wsState !== "open" && !settled) {
            settled = true
            this.#noteConnectFailure()
            this.#scheduleReconnect()
          }
        }
      }
      this.ws = ws
      this.#armConnectTimeout(gen)
    } catch (err) {
      this.wsState = "off"
      logger.warn(`[Mugshot] WebSocket 连接失败: ${err.message}，稍后重试`)
      this.#scheduleReconnect()
    }
  }

  #armConnectTimeout(gen) {
    this.#clearConnectTimeout()
    this.connectTimer = setTimeout(() => {
      this.connectTimer = null
      if (gen !== this.wsGen || this.wsState === "open") return
      logger.warn(`[Mugshot] WebSocket 连接超时（${CONNECT_TIMEOUT_MS / 1000}s），稍后重试`)
      this.#noteConnectFailure()
      const ws = this.ws
      this.ws = null
      this.wsState = "off"
      this.wsGen++
      try { ws?.close?.() } catch {}
      this.#scheduleReconnect()
    }, CONNECT_TIMEOUT_MS)
  }

  #clearConnectTimeout() {
    if (this.connectTimer) {
      clearTimeout(this.connectTimer)
      this.connectTimer = null
    }
  }

  #warnAuth() {
    if (this.authWarned) return
    this.authWarned = true
    logger.warn("[Mugshot] WebSocket 订阅被拒绝（401）：插件 Token 与服务端不一致，请到面板填写统一 Token")
  }

  // #noteConnectFailure 记录握手前失败次数。
  // 全局 WebSocket 拿不到 HTTP 状态码，连续失败时也给出一次提示，
  // 避免地址/Token 配错只在 debug 日志里无声循环。
  #noteConnectFailure() {
    this.connectFails = (this.connectFails || 0) + 1
    if (this.connectFails === 3 && !this.authWarned) {
      this.authWarned = true
      logger.warn("[Mugshot] WebSocket 连续 3 次连不上：请检查面板中的服务端地址与 Token（HTTP 查询仍可用）")
    }
  }

  #onOpen() {
    this.#clearConnectTimeout()
    this.wsState = "open"
    this.reconnectDelay = 1000
    this.authWarned = false
    this.connectFails = 0
    logger.mark("[Mugshot] 已连接 mugshot-server WebSocket 订阅")
    // 服务端只在有设备时推 hello；主动拉一次列表，保证重连后列表与在线状态立即对齐
    this.getDevices({ force: true }).catch(err => logger.debug(`[Mugshot] 重连后拉取设备列表失败: ${err?.message || err}`))
  }

  #onWsMessage(raw) {
    // Node 全局 WebSocket 可能给 Blob；ws 包给 Buffer；字符串直接处理
    if (raw && typeof raw === "object" && typeof raw.text === "function") {
      raw.text().then(text => this.#handleWsText(text)).catch(() => {})
      return
    }
    if (raw instanceof ArrayBuffer) {
      this.#handleWsText(Buffer.from(raw).toString("utf8"))
      return
    }
    this.#handleWsText(Buffer.isBuffer(raw) ? raw.toString("utf8") : String(raw))
  }

  #handleWsText(text) {
    try {
      const msg = JSON.parse(text)
      if (!DEVICE_EVENTS.has(msg.type)) return
      const data = msg.data || {}
      if (msg.type === "hello") {
        this.#applySnapshotList(data.devices || [])
      } else if (msg.type === "device_online" && data.device) {
        // 上线事件：online 必然为 true（事件名即权威），避免广播对象缺 online 字段时缓存停留 false
        this.#applyDeviceSnapshot({ ...data.device, online: true })
      } else if (msg.type === "device_update" && data.device) {
        this.#applyDeviceSnapshot(data.device)
        logger.debug(`[Mugshot] 收到设备上报更新 ${data.device.device_name || data.device.device_id}`)
      } else if (msg.type === "device_offline") {
        if (data.device) {
          this.#applyDeviceSnapshot({ ...data.device, online: false })
        }
        if (data.device_id) {
          const d = this.devicesCache.find(i => i.device_id === data.device_id)
          if (d) d.online = false
        }
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
    this.#clearConnectTimeout()
    if (this.ws) {
      try { this.ws.removeAllListeners?.() } catch {}
      try { this.ws.onopen = null; this.ws.onmessage = null; this.ws.onclose = null; this.ws.onerror = null } catch {}
      try { this.ws.close() } catch {}
      this.ws = null
    }
    this.wsState = "off"
    if (this.closed || this.reconnectTimer || this.reconnectPending) return
    this.reconnectPending = true
    const gen = this.wsGen
    // 鉴权失败时服务端会直接拒绝握手，这里先同步一次 Token 再安排重连，
    // 避免旧 Token 让每次重连都失败
    this.#syncTokenFromLocal()
      .catch(() => "")
      .then(tok => {
        this.reconnectPending = false
        if (this.closed || this.reconnectTimer || gen !== this.wsGen) return
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
    // 先置 closed 并递增代号，屏蔽旧连接与旧异步回调触发的自动重连
    this.closed = true
    this.wsGen++
    this.#clearConnectTimeout()
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
    this.wsGen++
    this.#clearConnectTimeout()
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
