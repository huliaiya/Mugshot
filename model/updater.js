// Mugshot 插件自更新：插件目录是一份 git 检出，更新即"拉取 main 分支源码覆盖本地"
//  - 更新判定：比较本地 HEAD 与远端 main 的最新提交，不同即可更新（版本展示用短哈希）
//  - 更新方式：git fetch <远端> main && git reset --hard FETCH_HEAD（拉源码覆盖工作区）
//  - 非 git 目录（如用 tar 包安装）：直接报错，要求改用 git clone -b main 安装
// 远端地址优先取本地 origin；缺失时回退到配置的 github_repo
import { exec } from "node:child_process"
import { promisify } from "node:util"
import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"
import { config } from "./config.js"

const execAsync = promisify(exec)
/* global logger */

export const pluginDir = path.resolve(fileURLToPath(new URL("../", import.meta.url)))

export function isGitRepo() {
  return fs.existsSync(path.join(pluginDir, ".git"))
}

function short(sha) {
  return String(sha || "").trim().slice(0, 8)
}

function assertGitRepo() {
  if (!isGitRepo()) {
    throw new Error("插件目录不是 git 仓库，无法自动更新；请改用 git clone -b main 方式安装插件")
  }
}

/** 本地当前检出提交（main 分支 HEAD） */
async function localCommit() {
  assertGitRepo()
  const { stdout } = await execAsync("git rev-parse HEAD", { cwd: pluginDir, timeout: 15000 })
  const full = stdout.trim()
  if (!full) throw new Error("无法读取本地提交（git rev-parse HEAD）")
  return full
}

/** 插件源码的远端地址：优先本地 origin，其次配置的 github_repo */
async function resolveRemoteUrl() {
  assertGitRepo()
  try {
    const { stdout } = await execAsync("git remote get-url origin", { cwd: pluginDir, timeout: 15000 })
    const url = stdout.trim()
    if (url) return url
  } catch {}
  if (!config.github_repo) throw new Error("未找到 git origin，且未配置 github_repo（config/mugshot.yaml）")
  return `https://github.com/${config.github_repo}.git`
}

/** 远端 main 分支最新提交 */
async function remoteMainCommit() {
  const url = await resolveRemoteUrl()
  const { stdout } = await execAsync(`git ls-remote "${url}" refs/heads/main`, { cwd: pluginDir, timeout: 30000 })
  const sha = stdout.trim().split(/\s+/)[0]
  if (!sha) throw new Error("远端没有 main 分支，请确认仓库地址")
  return sha
}

// 检查结果缓存：#群主状态 等指令可能频繁触发，5 分钟内复用同一结果，
// 避免频繁发起远程查询；执行更新时强制刷新拿到真正的最新提交
let cache = null // { current, latest, ts }

export async function checkForUpdate(force = false) {
  if (!force && cache && Date.now() - cache.ts < 5 * 60 * 1000) {
    return { current: cache.current, latest: cache.latest, updateAvailable: cache.current !== cache.latest }
  }
  const [local, remote] = await Promise.all([localCommit(), remoteMainCommit()])
  cache = { current: short(local), latest: short(remote), ts: Date.now() }
  return { current: cache.current, latest: cache.latest, updateAvailable: cache.current !== cache.latest }
}

export async function updatePlugin() {
  assertGitRepo()
  const check = await checkForUpdate(true)
  if (!check.updateAvailable) {
    return { updated: false, message: `当前已是最新（${check.current}）` }
  }
  logger.mark(`[Mugshot] 开始更新插件 ${check.current} -> ${check.latest}`)
  const url = await resolveRemoteUrl()
  // 拉 main 分支源码，再硬重置覆盖本地工作区（本地改动会被覆盖）
  await execAsync(`git fetch --quiet "${url}" main`, { cwd: pluginDir, timeout: 120000 })
  await execAsync("git reset --hard FETCH_HEAD", { cwd: pluginDir, timeout: 60000 })
  cache = null
  let after = check.latest
  try {
    after = short(await localCommit())
  } catch {}
  return {
    updated: true,
    message: `插件已更新到 ${after}（已拉取 main 分支源码覆盖）\n改动需重启 Yunzai 后完全生效：可发送 #重启 或手动重启`,
  }
}

// 定时/启动时的静默检查，只写日志
export async function silentCheck() {
  try {
    const { updateAvailable, latest, current } = await checkForUpdate()
    if (updateAvailable) logger.mark(`[Mugshot] 发现插件新版本 ${latest}（当前 ${current}），Master 可发送 #群主更新`)
  } catch (err) {
    logger.debug(`[Mugshot] 更新检查失败: ${err.message}`)
  }
}
