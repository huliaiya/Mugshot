// 指令别名：默认 #群主xxx；配置 alias 后查询类指令改成 #<alias>xxx，
// 帮助与更新保持固定 #群主帮助 / #群主更新，避免改名后找不到入口或无法更新。
import { config } from "./config.js"

export const FIXED_HELP_PREFIX = "群主"

// 不随别名变化的指令：帮助用于找回入口，更新用于改错别名后仍能升级插件
const FIXED_ACTIONS = new Set(["帮助", "更新"])

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

/** 是否属于固定前缀的指令（帮助、更新） */
export function isFixedAction(action) {
  return FIXED_ACTIONS.has(action)
}

/** 生成当前前缀的指令文本，如 cmd("查看") -> #群主查看；帮助/更新固定为 #群主 */
export function cmd(action = "") {
  return `#${isFixedAction(action) ? FIXED_HELP_PREFIX : commandAlias()}${action}`
}

/** 是否为当前配置的指令前缀 */
export function matchesAlias(prefix) {
  return prefix === commandAlias()
}
