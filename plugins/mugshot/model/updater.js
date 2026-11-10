// Mugshot 插件自更新：
//  1) 优先 git pull（插件目录本身是 git 仓库或位于 git 仓库内的场景）
//  2) 否则从 GitHub Releases 下载 mugshot-plugin.tar.gz 覆盖安装
// 更新检查带故障转移：GitHub API 失败时回退到 releases/latest 重定向解析
import { exec } from "node:child_process"
import { promisify } from "node:util"
import fs from "node:fs"
import path from "node:path"
import os from "node:os"
import { fileURLToPath } from "node:url"
import { PLUGIN_NAME, PLUGIN_VERSION, config } from "./config.js"

const execAsync = promisify(exec)
/* global logger, Bot */

export const pluginDir = path.resolve(fileURLToPath(new URL("../", import.meta.url)))

export function localVersion() {
  try {
    const pkg = JSON.parse(fs.readFileSync(path.join(pluginDir, "package.json"), "utf8"))
    return pkg.version || PLUGIN_VERSION
  } catch {
    return PLUGIN_VERSION
  }
}

function parseVersion(v) {
  v = String(v || "").trim().replace(/^[vV]/, "").split(/[-+]/)[0]
  const parts = v.split(".").map(n => parseInt(n, 10))
  if (parts.length < 3 || parts.some(n => Number.isNaN(n))) return null
  return parts
}

export function newerThan(tag, current) {
  const a = parseVersion(tag)
  const b = parseVersion(current)
  if (!a || !b) return false
  for (let i = 0; i < 3; i++) if (a[i] !== b[i]) return a[i] > b[i]
  return false
}

async function fetchLatest() {
  const repo = config.github_repo
  if (!repo) throw new Error("未配置 github_repo（config/mugshot.yaml）")
  try {
    const res = await fetch(`https://api.github.com/repos/${repo}/releases/latest`, {
      headers: { Accept: "application/vnd.github+json", "User-Agent": "Mugshot-Yunzai-Plugin" },
      signal: AbortSignal.timeout(20000),
    })
    if (res.ok) {
      const data = await res.json()
      if (data?.tag_name) return { tag: data.tag_name, assets: data.assets || [] }
      throw new Error("响应缺少 tag_name")
    }
    throw new Error(`GitHub API 返回 ${res.status}`)
  } catch (err) {
    logger.warn(`[Mugshot] 更新检查主源失败（${err.message}），尝试备用源`)
    // 备用源：跟随 /releases/latest 的重定向拿到 tag
    const res = await fetch(`https://github.com/${repo}/releases/latest`, {
      method: "GET",
      signal: AbortSignal.timeout(20000),
    })
    const final = res.url || ""
    const idx = final.lastIndexOf("/tag/")
    if (idx < 0) throw new Error(`主源与备用源均失败（${err.message}）`)
    return { tag: decodeURIComponent(final.slice(idx + 5)), assets: [] }
  }
}

// GitHub 检查结果缓存：#mugshot状态 等指令可能频繁触发，5 分钟内复用同一结果，
// 避免触发 GitHub API 限流；执行更新时强制刷新拿到真正的最新版本
let ghCache = null // { tag, ts }

export async function checkForUpdate(force = false) {
  if (!force && ghCache && Date.now() - ghCache.ts < 5 * 60 * 1000) {
    return { latest: ghCache.tag, current: localVersion(), updateAvailable: newerThan(ghCache.tag, localVersion()) }
  }
  const { tag } = await fetchLatest()
  ghCache = { tag, ts: Date.now() }
  return { latest: tag, current: localVersion(), updateAvailable: newerThan(tag, localVersion()) }
}

async function tryGitPull() {
  if (!fs.existsSync(path.join(pluginDir, ".git"))) return null
  try {
    const { stdout } = await execAsync("git pull --ff-only", { cwd: pluginDir, timeout: 60000 })
    return stdout?.trim() || "git pull 完成"
  } catch (err) {
    logger.warn(`[Mugshot] git pull 失败，回退到 Release 包更新: ${err.message}`)
    return null
  }
}

async function downloadAndInstall(tag) {
  const repo = config.github_repo
  const assetName = "mugshot-plugin.tar.gz"
  let url = `https://github.com/${repo}/releases/download/${tag}/${assetName}`
  const res = await fetch(url, { signal: AbortSignal.timeout(120000) })
  if (!res.ok) throw new Error(`下载 ${assetName} 失败：HTTP ${res.status}`)
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "mugshot-update-"))
  const tgzPath = path.join(tmpDir, assetName)
  fs.writeFileSync(tgzPath, Buffer.from(await res.arrayBuffer()))
  await execAsync(`tar -xzf "${tgzPath}" -C "${tmpDir}"`, { timeout: 60000 })
  const srcDir = path.join(tmpDir, PLUGIN_NAME)
  if (!fs.existsSync(path.join(srcDir, "index.js"))) throw new Error("Release 包结构异常（缺少 index.js）")
  await execAsync(`cp -a "${srcDir}/." "${pluginDir}/"`, { timeout: 60000 })
  fs.rmSync(tmpDir, { recursive: true, force: true })
  return "已从 Release 包覆盖安装"
}

export async function updatePlugin() {
  const check = await checkForUpdate(true)
  if (!check.updateAvailable) {
    return { updated: false, message: `当前已是最新版本 v${check.current}（远端 ${check.latest}）` }
  }
  logger.mark(`[Mugshot] 开始更新插件 ${check.current} -> ${check.latest}`)
  const viaGit = await tryGitPull()
  let detail
  if (viaGit) {
    detail = `git pull：${viaGit}`
  } else {
    detail = await downloadAndInstall(check.latest)
  }
  return {
    updated: true,
    message: `插件已更新到 ${check.latest}（${detail}）\n改动需重启 Yunzai 后完全生效：可发送 #重启 或手动重启`,
  }
}

// 定时/启动时的静默检查，只写日志
export async function silentCheck() {
  try {
    const { updateAvailable, latest, current } = await checkForUpdate()
    if (updateAvailable) logger.mark(`[Mugshot] 发现插件新版本 ${latest}（当前 v${current}），Master 可发送 #mugshot插件更新`)
  } catch (err) {
    logger.debug(`[Mugshot] 更新检查失败: ${err.message}`)
  }
}
