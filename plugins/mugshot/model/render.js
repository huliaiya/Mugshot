// Mugshot 图片渲染封装
// 依据 TRSS-Yunzai 源码：e.runtime.render(plugin, tpl, data, cfg) 会把模板
// 解析为 plugins/mugshot/resources/<tpl>.html 并用内置 Renderer 截图；
// cfg.retType = "base64" 时返回 segment.image 元素（或 false），不自动发送。
// 模板均为自包含 HTML（不依赖 miao-plugin 的 defaultLayout）。
import { PLUGIN_NAME } from "./config.js"

/* global logger */

export async function renderCard(e, tpl, data) {
  try {
    if (!e.runtime || typeof e.runtime.render !== "function") {
      logger.error(`[Mugshot] 当前 Yunzai 运行环境无 runtime.render（截图渲染器未安装/未启用），` +
        `指令回复将降级为纯文本；请在 TRSS-Yunzai 安装并配置渲染器（puppeteer）后重试`)
      return null
    }
    const img = await e.runtime.render(PLUGIN_NAME, `html/${tpl}`, data, { retType: "base64" })
    if (img) return img
    logger.error(`[Mugshot] 渲染 ${tpl} 未返回图片（请检查 Yunzai 渲染器配置 config/renderer.yaml）`)
  } catch (err) {
    logger.error(`[Mugshot] 渲染 ${tpl} 失败: ${err?.stack || err}`)
  }
  return null
}

// 渲染失败时的最后一道防线：直接回复错误卡片对应的纯文本（仅在渲染器不可用时出现）
export function fallbackText(lines) {
  return ["【Mugshot】", ...lines].join("\n")
}
