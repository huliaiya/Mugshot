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

/** 远端 main 分支最新提交（按镜像候选顺序尝试，任一源成功即返回） */
async function remoteMainCommit() {
  const url = await resolveRemoteUrl()
  return withCandidates(url, async u => {
    const { stdout } = await execAsync(`git ls-remote "${u}" refs/heads/main`, { cwd: pluginDir, timeout: 20000 })
    const sha = stdout.trim().split(/\s+/)[0]
    if (!sha) throw new Error("远端没有 main 分支，请确认仓库地址")
    return sha
  })
}

// GitHub 直连在部分网络下不稳（GnuTLS recv error / 连接被掐断），
// 更新与检查按候选源依次尝试：配置的镜像优先，其次直连，最后常见公共镜像
const GITHUB_MIRROR_FALLBACKS = ["https://ghfast.top/", "https://gh-proxy.com/"]

function mirrorCandidates(url) {
  const list = []
  const m = String(config.github_mirror || "").trim()
  if (m) list.push(m.endsWith("/") ? m + url : `${m}/${url}`)
  list.push(url)
  if (/^https:\/\/github\.com\//.test(url)) {
    for (const mf of GITHUB_MIRROR_FALLBACKS) list.push(mf + url)
  }
  return list
}

/** 依次尝试每个候选源执行 fn，全部失败时抛出最后一次的错误 */
async function withCandidates(url, fn) {
  const candidates = mirrorCandidates(url)
  let lastErr
  for (const u of candidates) {
    try {
      return await fn(u)
    } catch (err) {
      lastErr = err
      logger.debug(`[Mugshot] git 访问 ${u} 失败（${err.message}），尝试下一个源`)
    }
  }
  throw new Error(`${lastErr?.message || lastErr}（已尝试 ${candidates.length} 个源，可在 config/mugshot.yaml 配置 github_mirror 加速镜像）`)
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
  // 拉取 main 分支源码后硬重置覆盖本地工作区（本地改动会被覆盖）；
  // 网络不稳时自动切换镜像候选源重试
  await withCandidates(url, async u => {
    await execAsync(`git fetch --quiet "${u}" main`, { cwd: pluginDir, timeout: 90000 })
    await execAsync("git reset --hard FETCH_HEAD", { cwd: pluginDir, timeout: 60000 })
  })
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

// ---------- 宿主更新机制（与 huli-plugins 同款） ----------
// 优先复用宿主内置更新插件 other/update.js（TRSS/Miao 布局二选一）：
// 它按 plugins/<安装目录名>/.git 定位插件仓库，自动拉取源码、按需安装依赖并按宿主逻辑重启；
// 全程只更新插件本体，与 Mugshot 服务端无关。不可用时由调用方回退到上方直接拉取方案。

const HOST_UPDATE_CANDIDATES = [
  "../../other/update.js", // TRSS-Yunzai 布局：插件装在 Yunzai/plugins/<name> 时 ../../ 即 plugins/
  "../../../other/update.js", // Miao-Yunzai / Yunzai v3 布局：更新插件在 Yunzai 根目录
]

/** 动态加载宿主更新插件，避免静态 import 因路径不成立导致整个模块加载失败 */
async function loadHostUpdate() {
  for (const spec of HOST_UPDATE_CANDIDATES) {
    try {
      const mod = await import(spec)
      if (mod?.update) return mod.update
    } catch {
      // 布局不匹配则尝试下一个候选
    }
  }
  return null
}

/**
 * 构造宿主更新插件所需的事件对象。
 * 载入器用 Object.defineProperty 定义 isMaster 等属性（不可枚举），
 * 直接 { ...e } 会把它们丢掉；用原型继承保留原事件全部属性，再覆盖 msg / logFnc。
 */
function createUpdaterEvent(e, msg) {
  const evt = Object.create(e ?? null)
  evt.msg = msg
  evt.logFnc = "[Mugshot][更新]"
  return evt
}

/** 插件安装目录名（宿主 getPlugin 按目录名定位仓库，目录名与仓库默认名不同也能更新） */
function installDirName() {
  return path.basename(pluginDir)
}

/**
 * 用宿主更新机制更新插件本体（自动处理依赖与重启）。
 * 成功返回 true（宿主已自行回复进度与结果）；宿主更新插件不可用返回 null（调用方回退）。
 */
export async function updateViaHost(e) {
  const HostUpdate = await loadHostUpdate()
  if (!HostUpdate) return null
  const name = installDirName()
  const updater = new HostUpdate(e)
  updater.e = createUpdaterEvent(e, `#更新${name}`)
  if ((await updater.getPlugin(name)) === false) return null
  await updater.update()
  return true
}

/** 用宿主更新机制读取插件最近提交；宿主更新插件或 getLog 不可用返回 null */
export async function updateLogViaHost(e) {
  const HostUpdate = await loadHostUpdate()
  if (!HostUpdate) return null
  const name = installDirName()
  const updater = new HostUpdate(e)
  updater.e = createUpdaterEvent(e, `#更新日志${name}`)
  if ((await updater.getPlugin(name)) === false) return null
  if (typeof updater.getLog !== "function") return null
  return await updater.getLog(name)
}
