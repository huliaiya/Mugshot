// Mugshot —— 基于 TRSS-Yunzai 的设备状态远程查看插件
//
// 规范依据（TRSS-Yunzai ESM 插件规范）：
//  - 插件目录 plugins/mugshot，入口 index.js，导出 class Mugshot extends plugin
//  - rule[].fnc 写方法名字符串；全部使用 import/export，无 require
//  - 所有指令回复均通过 e.runtime.render 渲染为图片后发送（配合 QQBot 按钮）
//  - 按钮仅 QQBot 适配器可用（见 model/buttons.js），其他协议端自动降级为纯图片
//  - 定时任务 cron 为 6 位（秒 分 时 日 月 星期）
//  - 权限：#群主更新 仅 Master（一条指令直接检查并更新服务端与插件）
import plugin from "../../lib/plugins/plugin.js"

import { config } from "./model/config.js"
import server from "./model/server.js"
import { renderCard, fallbackText } from "./model/render.js"
import { isQQBot, buildReply, commandButton, linkButton } from "./model/buttons.js"
import {
  statusView,
  deviceView,
  allStatusView,
  formatTime,
  formatAgo,
  maskDeviceID,
  pluginFooter,
} from "./model/format.js"
import { getBackdrop } from "./model/backdrop.js"
import { checkForUpdate, updatePlugin, silentCheck } from "./model/updater.js"

/* global logger, Bot */

// 更新防重入：服务端自更新会让进程重启、插件 git 拉取可能耗时较长，
// 连发 #群主更新 会重复触发。模块级标志（Mugshot 为单例）保证同一时刻只有一个更新在跑。
let updating = false

// fresh 冷却表（模块级）：TRSS 每条消息都会 new 一个插件实例，
// 实例字段撑不过单条消息，冷却必须放模块级才能跨消息生效。
const freshCooldown = new Map() // user_id -> last fresh query ts

export class Mugshot extends plugin {
  constructor() {
    super({
      name: "Mugshot",
      dsc: "设备状态远程查看（Android 设备使用状态卡片）",
      event: "message",
      priority: 500,
      task: [
        {
          name: "Mugshot 每日更新检查",
          cron: "0 12 9 * * *",
          fnc: "dailyUpdateCheck",
          log: false,
        },
      ],
      rule: [
        { reg: "^#群主帮助$", fnc: "help", permission: "all" },
        { reg: "^#群主列表$", fnc: "deviceList", permission: "all" },
        { reg: "^#群主在干嘛\\s*(.*)$", fnc: "allDoing", permission: "all" },
        { reg: "^#群主查看\\s*(.*)$", fnc: "viewDevice", permission: "all" },
        { reg: "^#群主详情\\s*(.*)$", fnc: "deviceDetail", permission: "all" },
        { reg: "^#群主状态$", fnc: "serverStatus", permission: "all" },
        { reg: "^#群主更新$", fnc: "serverUpdate", permission: "master" },
      ],
    })
  }

  /** 插件初始化：启动 WebSocket 订阅、登记热重载资源回收、延迟做一次更新检查 */
  async init() {
    this.addCleanup(() => server.close())
    server.startWs().catch(err => logger.debug(`[Mugshot] WS 启动失败: ${err?.message}`))
    if (config.auto_update_check) {
      setTimeout(() => silentCheck(), 20 * 1000)
    }
  }

  async dailyUpdateCheck() {
    if (!config.auto_update_check) return
    await silentCheck()
    // 服务端自身也有每日检查；这里顺带触达一次，确保服务端可达性问题能及时暴露在日志里
    try {
      await server.getHealth()
    } catch (err) {
      logger.warn(`[Mugshot] 定时检查：mugshot-server 不可达（${err.message}）`)
    }
  }

  // ---------- 指令实现 ----------

  /** #群主帮助 */
  async help() {
    const data = {
      title: "Mugshot 指令帮助",
      server: pluginFooter(),
      backdrop: getBackdrop(),
      groups: [
        {
          name: "设备查询",
          list: [
            { cmd: "#群主列表", desc: "查看所有已接入设备（紧凑列表，在线状态/最后上报）" },
            { cmd: "#群主在干嘛", desc: "全部设备此刻在干嘛：实时刷新每台设备的状态、正在使用的软件等" },
            { cmd: "#群主查看 [设备名]", desc: "带设备名看单台实时状态；不带设备名看全部设备快照" },
            { cmd: "#群主详情 [设备名]", desc: "查看设备完整字段与最近上报记录" },
          ],
        },
        {
          name: "系统维护",
          list: [
            { cmd: "#群主状态", desc: "服务端健康状态与版本" },
            { cmd: "#群主更新", desc: "检查并直接更新服务端与插件（仅 Master）" },
          ],
        },
      ],
    }
    const img = await renderCard(this.e, "help", data)
    return this.#send(img, null, [
      ["Mugshot 帮助渲染失败", `${data.title}\n${data.groups.map(g => `${g.name}：\n` + g.list.map(i => `${i.cmd}  ${i.desc}`).join("\n")).join("\n\n")}`],
    ])
  }

  /** #群主列表 */
  async deviceList() {
    let resp
    try {
      resp = await server.getDevices()
    } catch (err) {
      return this.#renderError("设备列表获取失败", err.message)
    }
    const devices = (resp.devices || []).map(listView)
    if (!devices.length) {
      return this.#renderError("暂无设备", "还没有任何 Android 设备接入。请先在手机上安装 Mugshot App 并完成配置。")
    }
    const data = {
      title: "Mugshot 设备列表",
      count: devices.length,
      onlineCount: devices.filter(d => d.online).length,
      devices,
      server: pluginFooter(),
      backdrop: getBackdrop(),
    }
    const img = await renderCard(this.e, "devices", data)
    const rows = this.#deviceButtonRows(devices.map(d => ({ name: d.device_name })), "查看")
    return this.#send(img, rows, [
      ["设备列表渲染失败", `共 ${devices.length} 台设备：\n` + devices.map(d => `${d.online ? "[在线]" : "[离线]"} ${d.device_name}（最后上报 ${d.last_report_short}）`).join("\n")],
    ])
  }

  /**
   * #群主在干嘛：始终渲染全部设备此刻的状态与正在使用的软件。
   * 并发触发每台在线设备的一次实时上报（共用单台查询的用户冷却），
   * 拿到新快照后再渲染，保证卡片展示的是"现在"而不是上次定时上报。
   */
  async allDoing() {
    return this.#renderAllDevices({ live: true })
  }

  /** #群主查看 [设备名]：无设备名看全部设备快照，带设备名看单台实时状态 */
  async viewDevice() {
    const name = (this.e.msg.replace(/^#群主查看/, "") || "").trim()
    if (!name) return this.#renderAllDevices({ live: false })
    return this.#queryAndRender(name, true, "status", "设备当前状态")
  }

  /** #群主详情 [设备名]：无设备名看全部设备，带设备名看单台完整详情 */
  async deviceDetail() {
    const name = (this.e.msg.replace(/^#群主详情/, "") || "").trim()
    if (!name) return this.#renderAllDevices({ live: !!config.detail_fresh })
    return this.#queryAndRender(name, !!config.detail_fresh, "detail", "设备详情")
  }

  /** #群主状态 */
  async serverStatus() {
    let health
    try {
      health = await server.getHealth()
    } catch (err) {
      return this.#renderError("服务端不可达", `${err.message}\n请检查 config/mugshot.yaml 的 server_url 配置。`)
    }
    let versionInfo = null
    try {
      versionInfo = await checkForUpdate()
    } catch {}
    const data = {
      title: "Mugshot 服务端状态",
      rows: [
        { label: "服务端地址", value: config.server_url },
        { label: "服务端版本", value: `${health.version}（go ${health.go || "-"}）` },
        { label: "运行时长", value: formatUptime(health.uptime_sec) },
        { label: "在线设备", value: `${health.devices_online} / ${health.devices_total}` },
        { label: "插件提交", value: versionInfo ? versionInfo.current : "-" },
        {
          label: "可用更新",
          value: versionInfo ? (versionInfo.updateAvailable ? `${versionInfo.latest}（可更新）` : "已是最新") : "检查失败",
          cls: versionInfo?.updateAvailable ? "warn" : "",
        },
      ],
      server: pluginFooter(),
      backdrop: getBackdrop(),
    }
    const img = await renderCard(this.e, "update", data)
    return this.#send(img, null, [
      ["服务端状态渲染失败", `mugshot-server ${health.version}，在线设备 ${health.devices_online}/${health.devices_total}`],
    ])
  }

  /**
   * #群主更新（仅 Master）：一条指令直接更新双端。
   * 并行检查服务端与插件，发现有更新就直接执行，无需二次确认。
   */
    async serverUpdate() {
      if (updating) {
        const img = await renderCard(this.e, "update", updateCardData(
          "Mugshot 更新中",
          "正在更新中（服务端自更新或插件拉取需数秒），请稍候再试 #群主更新。",
        ))
        return this.#send(img, null, [["Mugshot 更新", "正在更新中，请稍候再试 #群主更新。"]])
      }
      updating = true
      try {
        return await this.#doServerUpdate()
     } finally {
       updating = false
     }
   }

   /** #群主更新 实际逻辑（外层 serverUpdate 负责防重入锁） */
   async #doServerUpdate() {
     const [serverCheck, pluginCheck] = await Promise.allSettled([
      server.checkServerUpdate(),
      checkForUpdate(),
    ])
    const s = serverCheck.status === "fulfilled" ? serverCheck.value : null
    const p = pluginCheck.status === "fulfilled" ? pluginCheck.value : null
    if (!s && !p) {
      const detail = [
        serverCheck.reason ? `服务端：${serverCheck.reason.message}` : "",
        pluginCheck.reason ? `插件：${pluginCheck.reason.message}` : "",
      ].filter(Boolean).join("\n")
      return this.#renderError("更新检查失败", detail)
    }

    const sAvail = s?.update_available === true
    const pAvail = p?.updateAvailable === true
    if (!sAvail && !pAvail) {
      const data = updateCardData("双端均已是最新版本", [
        `服务端：${s ? `${s.current}（远端 ${s.latest}）` : "检查失败"}`,
        `插件：${p ? `${p.current}（远端 ${p.latest}）` : "检查失败"}`,
      ].join("\n"))
      const img = await renderCard(this.e, "update", data)
      return this.#send(img, null, [["更新提示渲染失败", `服务端 ${s?.current || "-"} / 插件 ${p?.current || "-"} 均已是最新`]])
    }

    // 有可用更新：直接执行（单端检查失败不影响另一端更新）
    const lines = [
      `检查结果：服务端${sAvail ? ` ${s.current} → ${s.latest}` : s ? "已是最新" : "检查失败"}，插件${pAvail ? ` ${p.current} → ${p.latest}` : p ? "已是最新" : "检查失败"}`,
      "",
    ]

    if (sAvail) {
      try {
        const resp = await server.applyServerUpdate()
        lines.push(
          resp.updated
            ? `服务端：已开始更新 ${resp.from} → ${resp.to}，进程将自动重启（约 5~10 秒恢复）`
            : `服务端：${resp.note || "已是最新"}`,
        )
      } catch (err) {
        lines.push(`服务端：更新失败（${err.message}）`)
      }
    } else {
      lines.push(s ? `服务端：已是最新（${s.current}）` : "服务端：检查失败，可发送 #群主状态 排查")
    }

    if (pAvail) {
      try {
        const result = await updatePlugin()
        lines.push(`插件：${result.message}`)
      } catch (err) {
        lines.push(`插件：更新失败（${err.message}）`)
      }
    } else {
      lines.push(p ? `插件：已是最新（${p.current}）` : "插件：检查失败，可发送 #群主状态 排查")
    }

    const data = updateCardData("双端更新结果", lines.join("\n"))
    const img = await renderCard(this.e, "update", data)
    return this.#send(img, null, [["更新提示渲染失败", lines.join("\n")]])
  }

  // ---------- 内部通用流程 ----------

  /**
   * 全部设备卡片入口：先取设备列表，live 时并发触发一轮实时上报。
   * 与单台查询共用同一用户冷却（fresh_cooldown_ms），冷却期内直接用最近快照。
   */
  async #renderAllDevices({ live }) {
    let resp
    try {
      resp = await server.getDevices()
    } catch (err) {
      return this.#renderError("设备列表获取失败", err.message)
    }
    const devices = resp.devices || []
    if (!devices.length) {
      return this.#renderError("暂无设备", "还没有任何 Android 设备接入，请先安装并配置 Mugshot App。")
    }

    let cooldownNote = ""
    let refresh = null
    if (live) {
      const last = freshCooldown.get(this.e.user_id) || 0
      const remain = (config.fresh_cooldown_ms || 10000) - (Date.now() - last)
      if (remain > 0) {
        cooldownNote = `实时刷新冷却中（${Math.ceil(remain / 1000)} 秒），以下为最近快照`
      } else {
        freshCooldown.set(this.e.user_id, Date.now())
        refresh = await this.#refreshOnlineDevices(devices)
      }
    }
    return this.#renderAllStatus(devices, live, cooldownNote, refresh)
  }

  /**
   * 对每台在线设备并发请求一次实时状态（服务端按需上报，设备离线/超时自动回落缓存），
   * 用返回的设备快照覆盖列表，让"正在使用"等字段反映此刻。
   * 返回 { live, degraded }：实际拿到实时数据的台数与降级回缓存的台数。
   */
  async #refreshOnlineDevices(devices) {
    const online = devices.filter(d => d.online)
    if (!online.length) return { live: 0, degraded: 0 }
    const results = await Promise.allSettled(
      online.map(d => server.getStatus(d.device_name || d.device_id, true)),
    )
    let live = 0
    let degraded = 0
    for (const r of results) {
      if (r.status !== "fulfilled" || !r.value?.device) {
        degraded++
        continue
      }
      const resp = r.value
      if (resp.data_source === "live") live++
      else degraded++
      const fresh = resp.device
      const idx = devices.findIndex(x => x.device_id === fresh.device_id)
      if (idx >= 0) devices[idx] = { ...devices[idx], ...fresh }
    }
    return { live, degraded }
  }

  /** 全部设备状态 + 使用情况（随机背景图），#群主在干嘛（实时）/ #群主查看（快照）使用 */
  async #renderAllStatus(devices, live, cooldownNote = "", refresh = null) {
    const rows = devices.map(allStatusView)
    let liveHint = "已实时刷新各设备上报；单台可发 #群主查看 <设备名>"
    if (live && refresh && refresh.degraded > 0) {
      liveHint = `已触发实时上报，${refresh.degraded} 台未实时返回（冷却中或响应超时），已展示最近数据；单台可发 #群主查看 <设备名>`
    }
    const data = {
      title: live ? "全部设备当前状态" : "全部设备状态",
      count: rows.length,
      onlineCount: rows.filter(d => d.online).length,
      hint: cooldownNote || (live ? liveHint : "数据来自设备上报快照；实时刷新请发 #群主在干嘛"),
      devices: rows,
      server: pluginFooter(),
      backdrop: getBackdrop(),
    }
    const img = await renderCard(this.e, "allstatus", data)
    return this.#send(img, null, [
      [
        "全部设备状态渲染失败",
        `共 ${rows.length} 台设备：\n` + rows.map(d => `${d.online ? "[在线]" : "[离线]"} ${d.device_name} · ${d.appText} · ${d.batteryText} · ${d.screenText} · ${d.networkText}（最近 ${d.report_time_text}）`).join("\n"),
      ],
    ])
  }

  async #queryAndRender(name, fresh, tpl, title) {
    // fresh 冷却：同一用户 10 秒内重复触发时自动降级为缓存查询，避免频繁唤醒手机
    let useFresh = !!fresh
    let cooldownNote = ""
    if (useFresh) {
      const last = freshCooldown.get(this.e.user_id) || 0
      const remain = (config.fresh_cooldown_ms || 10000) - (Date.now() - last)
      if (remain > 0) {
        useFresh = false
        cooldownNote = `请求过于频繁（${Math.ceil(remain / 1000)} 秒冷却内），本次展示缓存数据`
      } else {
        freshCooldown.set(this.e.user_id, Date.now())
        // 冷却表防膨胀：超过 500 个用户时清理过期项
        if (freshCooldown.size > 500) {
          const now = Date.now()
          for (const [uid, ts] of freshCooldown) {
            if (now - ts > (config.fresh_cooldown_ms || 10000)) freshCooldown.delete(uid)
          }
        }
      }
    }

    let resp
    try {
      resp = await server.getStatus(name, useFresh)
    } catch (err) {
      return this.#renderError("查询失败", err.message)
    }

    const data = statusView(resp)
    data.title = title
    data.class = ""
    if (cooldownNote && !data.note) data.note = cooldownNote
    data.server = pluginFooter()
    data.backdrop = getBackdrop()
    // 设备未上报过 / 缓存为空时，日志说明原因，避免群里误以为“查不到”
    if (!data.has_report) {
      logger.debug(`[Mugshot] 设备 ${name} 暂无上报数据（尚未接入或从未上报），已按空状态展示`)
    }

    const img = await renderCard(this.e, tpl, data)
    const rows = isQQBot(this.e)
      ? [
          [
            commandButton("刷新", `#群主查看 ${name}`, 1),
            commandButton("详情", `#群主详情 ${name}`, 2),
            commandButton("设备列表", "#群主列表", 2),
          ],
        ]
      : []
    return this.#send(img, rows, [
      [
        `「${data.device_name}」状态渲染失败`,
        [
          `设备：${data.device_name}（${data.online ? "在线" : "离线"}）`,
          `正在使用：${data.foreground_app}`,
          `屏幕：${data.screen} | 电量：${data.battery}% | ${data.charging} | ${data.network}`,
          `数据来源：${data.source_text}（${data.report_time || "从未上报"}）`,
          data.note,
        ].filter(Boolean).join("\n"),
      ],
    ])
  }

  #deviceButtonRows(items, prefix) {
    const perRow = Math.min(Math.max(config.device_btn_per_row || 5, 1), 10)
    const rows = []
    for (let i = 0; i < items.length && rows.length < 5; i += perRow) {
      rows.push(
        items.slice(i, i + perRow).map(item =>
          commandButton(item.name, `#群主${prefix === "详情" ? "详情" : "查看"} ${item.name}`, 1),
        ),
      )
    }
    return rows
  }

  /** 统一错误卡片：错误信息也必须渲染成图片回复 */
  async #renderError(title, message) {
    logger.warn(`[Mugshot] ${title}: ${message}`)
    const data = {
      title: `Mugshot · ${title}`,
      message,
      hint: "可发送 #群主状态 检查服务端连通性，或联系群主检查 mugshot-server。",
      time: formatTime(Date.now()),
      server: pluginFooter(),
      backdrop: getBackdrop(),
    }
    const img = await renderCard(this.e, "error", data)
    return this.#send(img, null, [[title, message]])
  }

  /**
   * 发送渲染结果：优先 [图片, 按钮]；渲染完全不可用时降级为纯文本（最后一道防线）。
   * @param {Promise|object|null} imgPromise renderCard 的返回（可能是 Promise）
   * @param {Array<Array<object>>|null} rows 按钮行（仅 QQBot）
   * @param {Array<[string,string]>} fallbacks [标题, 文本] 兜底
   */
  async #send(imgPromise, rows, fallbacks) {
    const img = await imgPromise
    if (img) {
      const msg = buildReply(img, isQQBot(this.e) ? rows || [] : [])
      if (msg) return this.reply(msg)
    }
    const [title, text] = fallbacks?.[0] || ["Mugshot", "处理完成"]
    return this.reply(fallbackText([title, text]))
  }
}

function updateCardData(title, message, confirm = false) {
  return { title, message, confirm, server: pluginFooter(), backdrop: getBackdrop() }
}

/** 设备 -> 列表卡片行数据（含电池/前台应用摘要） */
function listView(d) {
  const p = d?.last_report?.payload || {}
  return {
    ...deviceView(d),
    batteryText: typeof p.battery_level === "number" ? `${p.battery_level}%` : "无数据",
    appText: p.foreground_app || p.foreground_package || "无数据",
  }
}

function formatUptime(sec) {
  if (typeof sec !== "number" || sec < 0) return "-"
  const d = Math.floor(sec / 86400)
  const h = Math.floor((sec % 86400) / 3600)
  const m = Math.floor((sec % 3600) / 60)
  if (d > 0) return `${d} 天 ${h} 小时`
  if (h > 0) return `${h} 小时 ${m} 分钟`
  return `${m} 分钟`
}

export default Mugshot
