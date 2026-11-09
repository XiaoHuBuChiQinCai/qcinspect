/**
 * 会话的本地存储口径。抽成零依赖纯函数，是为了能单测——
 * 这里的 bug 不报错、不白屏，只是右上角悄悄变成「未知角色」、管理员菜单整块不见。
 */

/** PC 端独占这两个键。移动端用 qc_h5_*，见 common/api.js 顶部的说明 */
export const TOKEN_KEY = 'qc_token'
export const USER_KEY = 'qc_user'

/**
 * 把 localStorage 里的原文解析成用户对象，取不到就给 null。
 *
 * 为什么不能直接 JSON.parse：uni-app 的 H5 端 `uni.setStorageSync` 对非字符串值
 * 会包一层 `{ type: 'object', data: ... }`（见 uni-h5 源码的 setStorageSync）。
 * 而 H5 和 PC 是同域的两个前端，以前共用 `qc_user` 这个键——在手机上登录过一次，
 * 这台浏览器里 PC 读到的就是那个外壳，`user.role` 永远是 undefined。
 * token 是字符串所以不包装，接口照样能调通，于是现象只是「能查数据但角色是未知的」。
 *
 * 现在两端已经分键，这里留着剥壳是为了救那些本地还残留着包装值的浏览器，
 * 不然它们得等用户手动退出重登才恢复。
 */
export function parseStoredUser(raw) {
  if (!raw) return null
  let v
  try {
    v = JSON.parse(raw)
  } catch {
    return null
  }
  if (!v || typeof v !== 'object' || Array.isArray(v)) return null
  // 与 uni 自己的 parseValue 同口径：正好两个键 type + data，才认定是包装值。
  // 注意 data 可能是 null（saveSession(token, null) 就是这个形状），不能用真值判断挡掉
  if (Object.keys(v).length === 2 && 'data' in v && typeof v.type === 'string') {
    return v.data
  }
  return v
}
