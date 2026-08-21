// 视口工具：小屏（手机）判定，用于输入框提示语等移动端差异化展示

/**
 * 是否为移动端视口（≤767px）。
 * 页面加载时判定一次即可（横竖屏切换后刷新/重新进入页面会重新取值）。
 */
export function isMobileViewport() {
  return window.matchMedia('(max-width: 767px)').matches
}
