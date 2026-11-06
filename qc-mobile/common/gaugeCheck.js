/**
 * 量具点检（期间核查）的算法只在这一个文件里。
 *
 * 校准是外部机构一年做一次，点检是车间每月拿标准件对一遍。两件事都会让量具停用，
 * 但依据不同：一个看证书，一个看偏差。所以点检的结论必须和校准分开算，
 * 也不能把「上次校准合格」当成「点检一定合格」。
 *
 * 结论口径与后端 GaugeService.submitCheck 一致：
 *   |实测 - 标称| <= 允差  → 合格（闭区间，等于允差算合格）
 * 数值一律按万分之一（4 位小数）取整后再比，和后端 BigDecimal.setScale(4) 对齐。
 * 直接用浮点比会出事：10.01 - 10 在 double 里是 0.010000000000000675，
 * 界面判超差、后端判合格，人拿着两个结论来问。
 */

import { dateOnly, daysBetween } from './gauge.js'

/** 点检结论，来自 V17 表注释：PASS 合格 / FAIL 不合格 */
export const CHECK_RESULT_TEXT = {
  PASS: '合格',
  FAIL: '不合格'
}

export function checkResultText(r) {
  return CHECK_RESULT_TEXT[r] || r || ''
}

/** 与 numeric(18,4) 对齐的精度：1e4 = 万分之一 */
const UNIT = 1e4

/**
 * 转成「万分之一」的整数，等价于 BigDecimal.setScale(4, HALF_UP)。
 *
 * 负数走 -Math.round(-n) 而不是 Math.round(n)：Math.round(-0.5) 是 -0（向 +∞ 取整），
 * 和后端 HALF_UP（远离零）差一个最小单位。这点差在点检里正好卡在允差边界上。
 */
export function toUnits(v) {
  if (v === null || v === undefined || v === '') return NaN
  const n = Number(v)
  if (!Number.isFinite(n)) return NaN
  return n < 0 ? -Math.round(-n * UNIT) : Math.round(n * UNIT)
}

/** 数值展示：最多 4 位小数，末尾的 0 去掉。别用 toFixed(4) 直接显示，0.0100 看着像四位有效数字 */
export function formatAmount(v) {
  const n = Number(v)
  if (!Number.isFinite(n)) return ''
  const s = n.toFixed(4).replace(/0+$/, '').replace(/\.$/, '')
  return s === '-0' ? '0' : s
}

/**
 * 从量具的「精度」里抠一个默认允差。
 *
 * 精度字段是人工填的字符串：'0.01mm'、'±0.02'、'HRC 1'，没有统一格式。
 * 认得出就预填，认不出返回 null 让人自己填——猜错了比空着更糟，
 * 空着至少会停下来想一下「允差到底多少」。
 */
export function parseTolerance(accuracy) {
  if (accuracy === null || accuracy === undefined) return null
  const m = String(accuracy).match(/\d+(?:\.\d+)?/)
  if (!m) return null
  const v = Number(m[0])
  return Number.isFinite(v) ? v : null
}

/**
 * 一次点检的结论。
 *
 * @param standard  标准件标称值
 * @param measured  这把量具量出来的值
 * @param tolerance 允差（±，填绝对值）
 * @returns {{pass: boolean|null, deviation: number|null, text: string}}
 *          pass 为 null 表示还填不全，不是不合格
 */
export function checkVerdict(standard, measured, tolerance) {
  const s = toUnits(standard)
  const m = toUnits(measured)
  const t = toUnits(tolerance)
  if (!Number.isFinite(s) || !Number.isFinite(m)) {
    return { pass: null, deviation: null, text: '填上标称值和实测值，这里会实时算偏差' }
  }
  if (!Number.isFinite(t)) {
    return { pass: null, deviation: (m - s) / UNIT, text: '还差一个允差：问清楚这把量具的允差是多少' }
  }
  if (t < 0) {
    return { pass: null, deviation: (m - s) / UNIT, text: '允差按 ± 填绝对值，不要填负数' }
  }
  const dev = (m - s) / UNIT
  // 闭区间：偏差正好等于允差算合格
  const pass = Math.abs(m - s) <= t
  return {
    pass,
    deviation: dev,
    text: pass
      ? '合格：偏差 ' + formatAmount(dev) + '，允差 ±' + formatAmount(t / UNIT)
      : '超差：偏差 ' + formatAmount(dev) + '，超过允差 ±' + formatAmount(t / UNIT)
  }
}

/** 本月是否已经点检过（同一个月就算，不看具体几号） */
export function checkedThisMonth(lastCheckDate, today) {
  const last = dateOnly(lastCheckDate)
  const now = dateOnly(today)
  if (!last || !now) return false
  return last.slice(0, 7) === now.slice(0, 7)
}

/**
 * 距上次点检多少天；从没点检过返回 Infinity。
 *
 * 后端 pendingChecks 用 Integer.MAX_VALUE 表示「从没点检过」并排在前面，
 * 这里用 Infinity 做同样的事，排序结果一致。
 */
export function daysSinceCheck(lastCheckDate, today) {
  const last = dateOnly(lastCheckDate)
  if (!last) return Infinity
  return daysBetween(last, today)
}

/**
 * 待点检排序：隔得最久的排最前，其次按编号。
 * 这一页是拿来催活的，不是拿来看台账的——昨天刚点检的排第一没有意义。
 */
export function sortPending(items, today) {
  return items
    .map(it => ({ ...it, _days: daysSinceCheck(it.lastCheckDate, today) }))
    .sort((a, b) => {
      if (a._days !== b._days) return a._days === Infinity ? -1 : b._days === Infinity ? 1 : b._days - a._days
      return String(a.gauge?.gaugeCode || '').localeCompare(String(b.gauge?.gaugeCode || ''))
    })
}

/** 待点检那一行的说明文字 */
export function pendingHint(item, today) {
  const d = daysSinceCheck(item.lastCheckDate, today)
  if (d === Infinity) return '从没点检过'
  if (d === 0) return '今天点检过'
  return d + ' 天前点检' + (item.lastResult === 'FAIL' ? '（不合格）' : '')
}
