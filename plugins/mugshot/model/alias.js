// 指令别名：默认 #群主xxx；配置 alias 后所有业务指令改成 #<alias>xxx，
// 唯独帮助保持固定 #群主帮助，避免改成别名后老用户找不到入口。
import { config } from "./config.js"

export const FIXED_HELP_PREFIX = "群主"

const ALIAS_RE = /^[\w\u4e00-\u9fa5]{1,12}$/

/** 规范化别名：去空白与开头的 #；非法值回退默认 群主 */
export function commandAlias() {
  const raw = String(config.alias ?? "").trim().replace(/^#+/, "")
  return ALIAS_RE.test(raw) ? raw : FIXED_HELP_PREFIX
}

/** 校验面板输入；合法返回规范化别名，非法返回空串 */
export function normalizeAlias(value) {
  const raw = String(value ?? "").trim().replace(/^#+/, "")
  return ALIAS_RE.test(raw) ? raw : ""
}

/** 生成当前前缀的指令文本，如 cmd("查看") -> #群主查看 */
export function cmd(action = "") {
  return `#${commandAlias()}${action}`
}

/** 是否为当前配置的指令前缀 */
export function matchesAlias(prefix) {
  return prefix === commandAlias()
}
