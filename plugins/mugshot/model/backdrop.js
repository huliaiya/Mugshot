// 渲染背景：参考 huli-plugins 的"随机背景图"风格——每次渲染随机选一张背景。
// 为避免在仓库里塞入二进制图片，这里用"随机色相的 CSS 渐变"实现：
//   - 每次调用生成一个随机的双色渐变（135deg，两个相邻色相），铺在卡片外层
//   - 内容放在半透明毛玻璃面板里（模板侧 backdrop-filter: blur），视觉与 huli 一致
// 取随机而非固定，保证"每次渲染的背景都不一样"。
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

// 返回一组 CSS 背景值（渐变字符串 + 兜底色），供模板 {{ backdrop }} 使用
export function getBackdrop() {
  const p = PALETTES[Math.floor(Math.random() * PALETTES.length)]
  const angle = 115 + Math.floor(Math.random() * 50) // 115~165 度随机
  const gradient = `linear-gradient(${angle}deg, ${p[0]} 0%, ${p[1]} 55%, ${p[2]} 100%)`
  return {
    background: gradient,
    fallbackColor: p[0],
  }
}
