// Mugshot 按钮消息封装
// 按钮仅 QQBot 适配器可用；其他协议端降级为纯图片（本函数直接返回 []）。
// 依据 TRSS-Yunzai 源码：
//  - 适配器注册时 Bot[id].adapter = adapter，QQBot 适配器 id 为 "QQBot"
//  - segment.button(row1, row2, ...) 变参，每行为按钮数组，行最多 10 个、最多 5 行
//  - 按钮三选一：callback / input / link；input 搭配 enter(TRSS 适配器读 send) 自动发送
//  - callback 按钮：QQBot.yaml toCallback=true（默认）时点击会以 sub_type=callback
//    的消息事件重新进入插件规则，文本即 callback 内容，可直接命中指令正则

/* global segment, Bot */

export function isQQBot(e) {
  const ids = [e?.bot?.adapter?.id, e?.self?.adapter?.id, Bot?.[e?.self_id]?.adapter?.id]
  return ids.includes("QQBot")
}

// 把一段指令包装成“点击即发送”的按钮属性
export function commandButton(text, command, style = 1) {
  return {
    text,
    input: command,
    // enter: wind-trace-typ 适配器字段；send: TRSS QQBot 适配器字段
    enter: true,
    send: true,
    style,
  }
}

export function linkButton(text, link, style = 4) {
  return { text, link, style }
}

/**
 * 组装最终回复消息：[图片, 按钮...]
 * @param {*} img runtime.render 返回的 segment.image 元素
 * @param {Array<Array<object>>} rows 按钮行（每行为按钮数组）
 * @returns {Array} e.reply 可用的消息数组；img 为 null 时返回 null（由调用方降级）
 */
export function buildReply(img, rows = []) {
  if (!img) return null
  const msg = [img]
  if (rows.length && typeof segment !== "undefined" && typeof segment.button === "function") {
    const limited = rows.slice(0, 5).map(row => row.slice(0, 10))
    if (limited.some(row => row.length)) msg.push(segment.button(...limited))
  }
  return msg
}
