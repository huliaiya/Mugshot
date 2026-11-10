// Mugshot —— 基于 TRSS-Yunzai 的设备状态远程查看插件
//
// 规范依据（TRSS-Yunzai ESM 插件规范）：
//  - 插件目录 plugins/mugshot，入口 index.js，导出 class Mugshot extends plugin
//  - rule[].fnc 写方法名字符串；全部使用 import/export，无 require
//  - 所有指令回复均通过 e.runtime.render 渲染为图片后发送（配合 QQBot 按钮）
//  - 按钮仅 QQBot 适配器可用（见 model/buttons.js），其他协议端自动降级为纯图片
//  - 定时任务 cron 为 6 位（秒 分 时 日 月 星期）
//  - 权限：#mugshot更新 仅 Master 或群主；#mugshot插件更新 仅 Master
import plugin from "../../lib/plugins/plugin.js"

import { config } from "./model/config.js"
import server from "./model/server.js"
import { renderCard, fallbackText } from "./model/render.js"
import { isQQBot, buildReply, commandButton, linkButton } from "./model/buttons.js"
import {
  statusView,
  deviceView,
  formatTime,
  formatAgo,
  maskDeviceID,
  pluginFooter,
} from "./model/format.js"
import { checkForUpdate, updatePlugin, silentCheck } from "./model/updater.js"

/* global logger, Bot */

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
        { reg: "^#[mM]ugshot帮助$", fnc: "help", permission: "all" },
        { reg: "^#[mM]ugshot列表$", fnc: "deviceList", permission: "all" },
        { reg: "^#[mM]ugshot(查看|在干嘛)\\s*(.*)$", fnc: "viewDevice", permission: "all" },
        { reg: "^#[mM]ugshot详情\\s*(.*)$", fnc: "deviceDetail", permission: "all" },
        { reg: "^#[mM]ugshot状态$", fnc: "serverStatus", permission: "all" },
        { reg: "^#[mM]ugshot更新\\s*(.*)$", fnc: "serverUpdate", permission: "owner" },
        { reg: "^#[mM]ugshot插件更新$", fnc: "pluginUpdate", permission: "master" },
      ],
    })
    this.freshCooldown = new Map() // user_id -> last fresh query ts
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

  /** #mugshot帮助 */
  async help() {
    const data = {
      title: "Mugshot 指令帮助",
      server: pluginFooter(),
      groups: [
        {
          name: "设备查询",
          list: [
            { cmd: "#mugshot列表", desc: "查看所有已接入设备（在线状态/最后上报）" },
            { cmd: "#mugshot查看 [设备名]", desc: "查看设备当前状态，默认触发一次实时上报" },
            { cmd: "#mugshot在干嘛 [设备名]", desc: "同 #mugshot查看" },
            { cmd: "#mugshot详情 [设备名]", desc: "查看设备完整字段与最近上报记录" },
          ],
        },
        {
          name: "系统维护",
          list: [
            { cmd: "#mugshot状态", desc: "服务端健康状态与版本" },
            { cmd: "#mugshot更新", desc: "检查/执行服务端更新（Master 或群主）" },
            { cmd: "#mugshot插件更新", desc: "更新 Mugshot 插件（仅 Master）" },
          ],
        },
      ],
    }
    const img = await renderCard(this.e, "help", data)
    return this.#send(img, null, [
      ["Mugshot 帮助渲染失败", `${data.title}\n${data.groups.map(g => `${g.name}：\n` + g.list.map(i => `${i.cmd}  ${i.desc}`).join("\n")).join("\n\n")}`],
    ])
  }

  /** #mugshot列表 */
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
    }
    const img = await renderCard(this.e, "devices", data)
    const rows = this.#deviceButtonRows(devices.map(d => ({ name: d.device_name })), "查看")
    return this.#send(img, rows, [
      ["设备列表渲染失败", `共 ${devices.length} 台设备：\n` + devices.map(d => `${d.online ? "[在线]" : "[离线]"} ${d.device_name}（最后上报 ${d.last_report_short}）`).join("\n")],
    ])
  }

  /** #mugshot查看 [设备名] / #mugshot在干嘛 [设备名] */
  async viewDevice() {
    const name = (this.e.msg.replace(/^#[mM]ugshot(查看|在干嘛)/, "") || "").trim()
    return this.#view(name, { fresh: true, tpl: "status", title: "设备当前状态" })
  }

  /** #mugshot详情 [设备名] */
  async deviceDetail() {
    const name = (this.e.msg.replace(/^#[mM]ugshot详情/, "") || "").trim()
    return this.#view(name, { fresh: !!config.detail_fresh, tpl: "detail", title: "设备详情" })
  }

  /** #mugshot状态 */
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
        { label: "插件版本", value: versionInfo ? `v${versionInfo.current}` : "-" },
        {
          label: "可用更新",
          value: versionInfo ? (versionInfo.updateAvailable ? `${versionInfo.latest}（可更新）` : "已是最新") : "检查失败",
          cls: versionInfo?.updateAvailable ? "warn" : "",
        },
      ],
      server: pluginFooter(),
    }
    const img = await renderCard(this.e, "update", data)
    return this.#send(img, null, [
      ["服务端状态渲染失败", `mugshot-server ${health.version}，在线设备 ${health.devices_online}/${health.devices_total}`],
    ])
  }

  /** #mugshot更新 [应用|跳过]（Master 或群主）：检查并可执行服务端自更新 */
  async serverUpdate() {
    const arg = (this.e.msg.replace(/^#[mM]ugshot更新/, "") || "").trim()
    if (/^(应用|确认|确定|是|y|yes)$/i.test(arg)) {
      return this.#applyServerUpdate()
    }
    if (/^(跳过|取消|否|n|no)$/i.test(arg)) {
      return this.reply("已取消服务端更新。", true)
    }
    let resp
    try {
      resp = await server.checkServerUpdate()
    } catch (err) {
      return this.#renderError("服务端更新检查失败", err.message)
    }
    if (!resp.update_available) {
      const data = updateCardData(`服务端已是最新版本`, `当前版本 ${resp.current}（远端 ${resp.latest}）`)
      const img = await renderCard(this.e, "update", data)
      return this.#send(img, null, [["更新检查渲染失败", `${resp.current} 已是最新（远端 ${resp.latest}）`]])
    }
    // 有更新：渲染带确认按钮的卡片，60 秒内可回复“应用/跳过”
    const data = updateCardData(
      `发现服务端新版本 ${resp.latest}`,
      `当前版本 ${resp.current}\n发布说明：${(resp.notes || "无").slice(0, 300)}\n\n回复“#mugshot更新 应用”开始更新，“#mugshot更新 跳过”取消（60 秒有效）`,
      true,
    )
    const img = await renderCard(this.e, "update", data)
    const rows = isQQBot(this.e)
      ? [[commandButton("应用更新", "#mugshot更新 应用", 1), commandButton("暂不更新", "#mugshot更新 跳过", 2)]]
      : []
    this.setContext("onUpdateConfirm", this.e.isGroup, 60, "Mugshot 更新确认超时已取消")
    return this.#send(img, rows, [["更新提示渲染失败", `发现服务端新版本 ${resp.latest}（当前 ${resp.current}）。回复“#mugshot更新 应用”执行更新。`]])
  }

  /** #mugshot更新（无参数）后 60 秒内的下一条消息回调；无关消息返回 "continue" 交给正常规则处理 */
  async onUpdateConfirm() {
    const msg = (this.e.msg || "").trim()
    if (/应用|确认|确定|^是$|^y$|^yes$/i.test(msg)) {
      this.finish("onUpdateConfirm")
      return this.#applyServerUpdate()
    }
    if (/跳过|取消|不更新|否/.test(msg)) {
      this.finish("onUpdateConfirm")
      return this.reply("已取消服务端更新。", true)
    }
    return "continue"
  }

  async #applyServerUpdate() {
    let resp
    try {
      resp = await server.applyServerUpdate()
    } catch (err) {
      return this.#renderError("服务端更新执行失败", err.message)
    }
    if (resp.updated) {
      const data = updateCardData("服务端更新已开始", `正在下载并应用 ${resp.to}\n进程将自动重启（systemd Restart=always），约 5~10 秒后恢复。`)
      const img = await renderCard(this.e, "update", data)
      return this.#send(img, null, [["更新提示渲染失败", `服务端正在更新到 ${resp.to}，进程将自动重启。`]])
    }
    const data = updateCardData("无需更新", resp.note || "服务端已是最新版本")
    const img = await renderCard(this.e, "update", data)
    return this.#send(img, null, [["更新提示渲染失败", resp.note || "已是最新版本"]])
  }

  /** #mugshot插件更新（仅 Master） */
  async pluginUpdate() {
    let result
    try {
      result = await updatePlugin()
    } catch (err) {
      return this.#renderError("插件更新失败", err.message)
    }
    const data = updateCardData(
      result.updated ? "Mugshot 插件更新完成" : "Mugshot 插件已是最新",
      result.message,
    )
    const img = await renderCard(this.e, "update", data)
    return this.#send(img, null, [["插件更新提示渲染失败", result.message]])
  }

  // ---------- 内部通用流程 ----------

  /**
   * 查询设备状态并渲染。
   * 多设备未指定名称时渲染设备选择图片（附按钮），不随意挑选某一台。
   */
  async #view(name, { fresh, tpl, title }) {
    // 1) 未指定设备名
    if (!name) {
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
      if (devices.length === 1) {
        return this.#queryAndRender(devices[0].device_name || devices[0].device_id, fresh, tpl, title)
      }
      return this.#renderDevicePicker(devices, fresh ? "查看" : "查看详情")
    }

    // 2) 指定设备名（服务端负责名称解析，含大小写/前缀容错）
    return this.#queryAndRender(name, fresh, tpl, title)
  }

  async #queryAndRender(name, fresh, tpl, title) {
    // fresh 冷却：同一用户 10 秒内重复触发时自动降级为缓存查询，避免频繁唤醒手机
    let useFresh = !!fresh
    let cooldownNote = ""
    if (useFresh) {
      const last = this.freshCooldown.get(this.e.user_id) || 0
      const remain = (config.fresh_cooldown_ms || 10000) - (Date.now() - last)
      if (remain > 0) {
        useFresh = false
        cooldownNote = `请求过于频繁（${Math.ceil(remain / 1000)} 秒冷却内），本次展示缓存数据`
      } else {
        this.freshCooldown.set(this.e.user_id, Date.now())
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

    const img = await renderCard(this.e, tpl, data)
    const rows = isQQBot(this.e)
      ? [
          [
            commandButton("刷新", `#mugshot查看 ${name}`, 1),
            commandButton("详情", `#mugshot详情 ${name}`, 2),
            commandButton("设备列表", "#mugshot列表", 2),
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

  /** 多设备选择卡片：每台设备一个按钮，点击即发送对应查询指令 */
  #renderDevicePicker(devices, actionText) {
    const list = devices.map(listView)
    const data = {
      title: "选择要查询的设备",
      hint: `接入 ${list.length} 台设备，请点击按钮或发送「#mugshot${actionText} 设备名」`,
      devices: list,
      server: pluginFooter(),
    }
    const rows = this.#deviceButtonRows(
      list.map(d => ({ name: d.device_name })),
      actionText === "查看详情" ? "详情" : "查看",
    )
    const img = renderCard(this.e, "devices", data)
    return img.then(i => this.#send(i, isQQBot(this.e) ? rows : [], [
      ["设备选择渲染失败", `共 ${list.length} 台设备，请发送“#mugshot查看 设备名”指定设备：\n` + list.map(d => `${d.online ? "[在线]" : "[离线]"} ${d.device_name}`).join("\n")],
    ]))
  }

  #deviceButtonRows(items, prefix) {
    const perRow = Math.min(Math.max(config.device_btn_per_row || 5, 1), 10)
    const rows = []
    for (let i = 0; i < items.length && rows.length < 5; i += perRow) {
      rows.push(
        items.slice(i, i + perRow).map(item =>
          commandButton(item.name, `#mugshot${prefix === "详情" ? "详情" : "查看"} ${item.name}`, 1),
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
      hint: "可发送 #mugshot状态 检查服务端连通性，或联系群主检查 mugshot-server。",
      time: formatTime(Date.now()),
      server: pluginFooter(),
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
  return { title, message, confirm, server: pluginFooter() }
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
