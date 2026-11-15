// Mugshot 图片渲染封装（三级渲染链）
//  1) e.runtime.render —— TRSS-Yunzai 内置 runtime（含模板解析与 Renderer 调度）
//  2) 宿主 lib/puppeteer —— 与 huli-plugins 同款做法：动态 import 宿主根目录的
//     lib/puppeteer/puppeteer.js 直接截图（Miao/TRSS 布局都存在，绕开 runtime 依赖；
//     宿主能渲染其他插件的图，就能渲染本插件的图）
//  3) Bot.renderer.render —— TRSS-Yunzai 渲染器接口兜底
// 任一级成功即返回 segment.image；全部失败返回 null（调用方降级纯文本）。
// 模板均为自包含 HTML；背景图为 data URI 内嵌（见 model/backdrop.js），渲染时无需外网。
import path from "node:path"
import { fileURLToPath } from "node:url"
import { PLUGIN_NAME } from "./config.js"

/* global logger */

const PLUGIN_DIR = path.resolve(fileURLToPath(new URL("../", import.meta.url)))
// 截图内容小于 2KB 视为空白渲染（纯白页），按失败处理（同 huli-plugins 策略）
const MIN_IMAGE_BYTES = 2048

/** 统一归一化渲染结果：base64 字符串转 segment.image；空白图按失败处理 */
function toImageElement(ret) {
  if (!ret) return null
  if (typeof ret !== "string") return ret
  const bytes = Math.floor((ret.length * 3) / 4)
  if (bytes < MIN_IMAGE_BYTES) {
    logger.warn(`[Mugshot] 截图内容过小（${(bytes / 1024).toFixed(1)}KB），疑似空白渲染，按失败处理`)
    return null
  }
  const seg = globalThis.segment
  if (seg && typeof seg.image === "function") return seg.image(ret)
  return ret
}

/** 第 2 级：直接调用宿主根目录 lib/puppeteer/puppeteer.js（huli-plugins 同款） */
async function renderByHostPuppeteer(tpl, data) {
  const mod = await import("../../../lib/puppeteer/puppeteer.js")
  const puppeteer = mod.default || mod
  if (typeof puppeteer?.screenshot !== "function") {
    throw new Error("宿主 puppeteer 无 screenshot 方法")
  }
  const base64 = await puppeteer.screenshot(`${PLUGIN_NAME}/${tpl}`, {
    saveId: tpl,
    tplFile: path.join(PLUGIN_DIR, "resources", "html", `${tpl}.html`),
    pluResPath: `${PLUGIN_DIR}/resources/`,
    quality: 100,
    pageGotoParams: { waitUntil: "networkidle0" },
    ...data,
  })
  return toImageElement(base64)
}

/** 第 3 级：TRSS-Yunzai 渲染器接口（Bot.renderer） */
async function renderByBotRenderer(tpl, data) {
  const renderer = globalThis.Bot?.renderer
  if (!renderer || typeof renderer.render !== "function") return null
  const ret = await renderer.render(PLUGIN_NAME, `html/${tpl}`, data, { retType: "base64" })
  return toImageElement(ret)
}

export async function renderCard(e, tpl, data) {
  // 第 1 级：runtime.render
  try {
    if (e?.runtime && typeof e.runtime.render === "function") {
      const img = toImageElement(await e.runtime.render(PLUGIN_NAME, `html/${tpl}`, data, { retType: "base64" }))
      if (img) return img
      logger.warn(`[Mugshot] runtime.render 渲染 ${tpl} 未返回图片，尝试宿主 puppeteer 渲染`)
    }
  } catch (err) {
    logger.warn(`[Mugshot] runtime.render 渲染 ${tpl} 失败（${err?.message || err}），尝试宿主 puppeteer 渲染`)
  }
  // 第 2 级：宿主 lib/puppeteer
  try {
    const img = await renderByHostPuppeteer(tpl, data)
    if (img) return img
  } catch (err) {
    const reason = err?.code === "ERR_MODULE_NOT_FOUND"
      ? "宿主无 lib/puppeteer/puppeteer.js"
      : err?.message || String(err)
    logger.warn(`[Mugshot] 宿主 puppeteer 渲染 ${tpl} 失败（${reason}），尝试 Bot.renderer 兜底`)
  }
  // 第 3 级：Bot.renderer
  try {
    const img = await renderByBotRenderer(tpl, data)
    if (img) return img
  } catch (err) {
    logger.error(`[Mugshot] Bot.renderer 渲染 ${tpl} 失败: ${err?.stack || err}`)
  }
  logger.error(
    `[Mugshot] 渲染 ${tpl} 三级渲染链全部失败，指令回复将降级为纯文本；` +
    `请确认 Yunzai 已安装渲染依赖（在 Yunzai 根目录 pnpm install puppeteer）并配置 config/renderer.yaml`,
  )
  return null
}

// 渲染失败时的最后一道防线：直接回复错误卡片对应的纯文本（仅在渲染器不可用时出现）
export function fallbackText(lines) {
  return ["【Mugshot】", ...lines].join("\n")
}
