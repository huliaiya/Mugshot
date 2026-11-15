// 渲染背景：对齐 huli-plugins 的"随机背景图 + 半透明毛玻璃卡片"风格。
//  - 默认走远程随机图接口（与 huli-plugins 同源 https://t.alcy.cc/mp），
//    在 Node 侧拉取后内嵌为 data URI：渲染时零外网依赖，也不受协议端防盗链影响
//  - 5 分钟内存缓存并自动换图：同一时段的多张卡片共用同一背景，风格统一
//  - 获取失败回退为随机渐变深色底（纯 CSS，无外网），面板半透明毛玻璃由模板实现
//  - getBackdrop 保持同步：插件加载即后台预取，指令到达时缓存通常已就绪
const BG_API = "https://t.alcy.cc/mp"
const CACHE_TTL = 5 * 60 * 1000
const FETCH_TIMEOUT = 8000

const PALETTES = [
  ["#0f2027", "#203a43", "#2c5364"], // 深蓝青
  ["#1a2a3a", "#2c3e50", "#4ca1af"], // 蓝
  ["#232526", "#414345", "#4b5e75"], // 石墨
  ["#0f0c29", "#302b63", "#24243e"], // 夜空紫
  ["#141e30", "#243b55", "#2c7a78"], // 墨青
  ["#1f1c2c", "#45407c", "#6a3093"], // 葡萄紫
  ["#0d1b2a", "#1b263b", "#415a77"], // 深海
  ["#16222a", "#3a6073", "#415a77"], // 灰蓝
]

/* global logger */

let cache = null // { dataUri, ts }
let refreshing = false

function randomGradient() {
  const p = PALETTES[Math.floor(Math.random() * PALETTES.length)]
  const angle = 115 + Math.floor(Math.random() * 50) // 115~165 度随机
  return {
    gradient: `linear-gradient(${angle}deg, ${p[0]} 0%, ${p[1]} 55%, ${p[2]} 100%)`,
    fallbackColor: p[0],
  }
}

async function fetchBackdropImage() {
  const ctrl = new AbortController()
  const timer = setTimeout(() => ctrl.abort(), FETCH_TIMEOUT)
  try {
    const resp = await fetch(BG_API, { signal: ctrl.signal, redirect: "follow" })
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`)
    const type = (resp.headers.get("content-type") || "image/jpeg").split(";")[0]
    if (!type.startsWith("image/")) throw new Error(`非图片响应（${type}）`)
    const buf = Buffer.from(await resp.arrayBuffer())
    if (buf.length < 1024) throw new Error("图片内容过小")
    // 用无引号 url()：art-template 会 $escape 输出，双引号会被转义成 &quot; 破坏 CSS；
    // base64 字符集（A-Za-z0-9+/=）不含可转义字符，无引号形式可原样通过
    return `url(data:${type};base64,${buf.toString("base64")}) center / cover no-repeat`
  } finally {
    clearTimeout(timer)
  }
}

function refreshCache() {
  if (refreshing) return
  refreshing = true
  fetchBackdropImage()
    .then(dataUri => {
      cache = { dataUri, ts: Date.now() }
      logger.info("[Mugshot] 随机背景图已更新（5 分钟后自动更换）")
    })
    .catch(err => {
      logger.warn(`[Mugshot] 随机背景图获取失败（${err?.message || err}），暂用渐变底色，5 分钟后重试`)
    })
    .finally(() => {
      refreshing = false
    })
}

refreshCache()
// unref 定时器不阻止进程退出；取图失败时也会按此周期自动重试
const refreshTimer = setInterval(refreshCache, CACHE_TTL)
if (typeof refreshTimer.unref === "function") refreshTimer.unref()

// 返回 CSS background 值（背景图层叠渐变兜底），供模板 {{ backdrop.background }} 直接使用；
// 背景图未就绪时仅返回渐变底色
export function getBackdrop() {
  const { gradient, fallbackColor } = randomGradient()
  if (cache) {
    return { background: `${cache.dataUri}, ${gradient}`, fallbackColor }
  }
  return { background: gradient, fallbackColor }
}
