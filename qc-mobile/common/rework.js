/**
 * 返修报数的即时提示。
 *
 * <p>与 {@code qc-pc/src/utils/rework.js} 逐字一致：两端各算一遍、各写一处，
 * 早晚会出现「手机上算出来能提交、服务端说不行」。改这里就同步改那边。
 *
 *
 * 与后端 ReworkBalance 同一套口径：返好 + 返废 必须正好等于送修。
 * 这里只是为了让人在点「提交」之前就看见差几件，真正的闸门在服务端和库层 CHECK。
 *
 * 为什么值得在前端再写一遍：报数是车间的手速活，填错了要到服务端才被打回来，
 * 人得重新点开票、重新填一遍。当场看见「少了 1 件」比事后报错有用得多。
 */

// 与后端 ReworkBalance.MAX_QTY（200）一致：再大就会卡在「开复检单」那一步
export const MAX_QTY = 200

function toInt(v) {
  if (v === null || v === undefined) return null
  const s = String(v).trim()
  if (s === '') return null
  const n = Number(s)
  return Number.isInteger(n) ? n : NaN
}

/** 送修数量校验，返回 null 表示通过 */
export function checkQty(qty) {
  const n = toInt(qty)
  if (n === null) return '请填送修数量'
  if (Number.isNaN(n)) return '送修数量要填整数'
  if (n <= 0) return '送修数量必须大于 0'
  if (n > MAX_QTY) return `一张票最多 ${MAX_QTY} 件，送修更多请拆成多张`
  return null
}

/**
 * 完工报数校验，返回 null 表示通过。
 *
 * 少于送修：有几件没交代去向（多半还在返修台上）；
 * 多于送修：多半是记错了票。
 */
export function checkFinish(qty, reworkedQty, scrappedQty) {
  const q = toInt(qty)
  const r = toInt(reworkedQty)
  const s = toInt(scrappedQty)
  if (r === null || s === null) return '返好件数和返废件数都要填，返废填 0 也行'
  if (Number.isNaN(r) || Number.isNaN(s)) return '件数要填整数'
  if (r < 0 || s < 0) return '件数不能是负数'
  const sum = r + s
  if (sum < q) return `还差 ${q - sum} 件没交代去向`
  if (sum > q) return `比送修多了 ${sum - q} 件，是不是记错了票`
  return null
}

/**
 * 人数对照，界面上实时显示「返好 4 + 返废 1 = 5 / 送修 5」。
 * 数字摆出来比一句「不匹配」有用：人一眼就知道差在哪。
 */
export function balanceText(qty, reworkedQty, scrappedQty) {
  const q = toInt(qty) ?? 0
  const r = toInt(reworkedQty) ?? 0
  const s = toInt(scrappedQty) ?? 0
  const sum = (Number.isNaN(r) ? 0 : r) + (Number.isNaN(s) ? 0 : s)
  return { text: `返好 ${r || 0} + 返废 ${s || 0} = ${sum} / 送修 ${q}`, ok: sum === q }
}

/**
 * 是否超期。只对未完工的票判超期——完工的票晚了两天也没必要一直飘红。
 *
 * 日期比较一律用字符串（'2026-11-09' 直接比字面量）。
 * `new Date('2026-11-09 10:00:00')` 在 iOS Safari 上是 Invalid Date，
 * 而这里只要比到天，字符串比自己更省心。
 */
export function isOverdue(dueDate, status, today) {
  if (!dueDate || !today) return false
  if (status !== 'OPEN' && status !== 'DOING') return false
  return String(dueDate).slice(0, 10) < String(today).slice(0, 10)
}

/**
 * 今天（本地时区）。
 *
 * 不能用 `new Date().toISOString().slice(0,10)`：那是 UTC，早上八点前会取到昨天。
 */
export function todayLocal(now = new Date()) {
  const y = now.getFullYear()
  const m = String(now.getMonth() + 1).padStart(2, '0')
  const d = String(now.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}
