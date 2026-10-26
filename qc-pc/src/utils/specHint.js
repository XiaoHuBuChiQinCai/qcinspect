/**
 * 录入时的即时提示。
 *
 * 以前填 12.5（规格 11.95~12.05）界面上一点反应都没有，等提交判定才报「不合格」，
 * 然后要回头一件件找是哪项超规 —— 而且分不清是真超规还是把 12.5 打成了 125。
 *
 * 这里只做**提示**，不参与判定。真正的判定必须以后端为准：那边有规格快照、
 * 闭区间、布尔合格值方向等完整规则，前端手上的 minValue/maxValue 只是快照的副本。
 * 所以这里判红不影响任何结论，只是让人当场看见。
 */

/** 边界值算合格，与后端 ItemJudge 的闭区间口径一致 */
export const STATE = {
  BLANK: 'blank',       // 还没填，不算错（可以先填一部分）
  EMPTY: 'empty',       // 数值项空着
  NAN: 'nan',           // 填了但不是数字
  OOS: 'oos',           // 超出规格
  OK: 'ok',
  NOLIMIT: 'nolimit'    // 标准没配限值，仅记录
}

function toNum(v) {
  if (v === null || v === undefined) return null
  const s = String(v).trim()
  if (s === '') return null
  const n = Number(s)
  return Number.isFinite(n) ? n : NaN
}

/**
 * 单个读数落在哪里。
 *
 * @param min 规格下限，可为 null（单边）
 * @param max 规格上限，可为 null（单边）
 * @param raw 录入的原始值
 */
export function valueState(min, max, raw) {
  if (raw === null || raw === undefined || String(raw).trim() === '') return STATE.BLANK
  const v = toNum(raw)
  if (Number.isNaN(v)) return STATE.NAN

  const lo = toNum(min)
  const hi = toNum(max)
  if (Number.isNaN(lo) || Number.isNaN(hi)) return STATE.NOLIMIT
  if (lo === null && hi === null) return STATE.NOLIMIT

  // 闭区间：恰好等于上下限是合格的
  if (lo !== null && v < lo) return STATE.OOS
  if (hi !== null && v > hi) return STATE.OOS
  return STATE.OK
}

/** 这一行录入的所有读数 */
function rawValues(line) {
  if (!line) return []
  if (line.valueMode === 'MULTIPLE') {
    const n = line.pointCount || 1
    const arr = Array.isArray(line._inputs) ? line._inputs : []
    return Array.from({ length: n }, (_, i) => arr[i] ?? '')
  }
  return [line._input ?? '']
}

/**
 * 整行的状态：任一测点超规 → 行超规；否则任一填了非数字 → 行有脏值。
 *
 * 只对数值项有意义，其它类型一律返回 `null`（组件据此不显示标记）。
 */
export function lineState(line) {
  if (!line || line.valueType !== 'NUMBER') return null
  const values = rawValues(line)
  let state = STATE.BLANK
  let filled = 0
  for (const v of values) {
    const s = valueState(line.minValue, line.maxValue, v)
    if (s === STATE.OOS) return STATE.OOS
    if (s === STATE.NAN) state = STATE.NAN
    if (s !== STATE.BLANK) filled++
  }
  if (filled === 0) return STATE.BLANK
  return state === STATE.NAN ? STATE.NAN : STATE.OK
}

export const STATE_TEXT = {
  [STATE.OOS]: '超出规格',
  [STATE.NAN]: '不是数字',
  [STATE.NOLIMIT]: '未设限值'
}

/** 规格限的展示文案，单边规格只显示一侧 */
export function specText(min, max, unit) {
  const u = unit ? ` ${unit}` : ''
  const lo = min === null || min === undefined ? null : min
  const hi = max === null || max === undefined ? null : max
  if (lo !== null && hi !== null) return `${lo} ~ ${hi}${u}`
  if (lo !== null) return `≥ ${lo}${u}`
  if (hi !== null) return `≤ ${hi}${u}`
  return '未设限值'
}
