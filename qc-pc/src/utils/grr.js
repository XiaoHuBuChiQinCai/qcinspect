/**
 * 量具重复性与再现性（GR&R）研究的实时预览。
 *
 * 和 `qc-server/.../msa/GrrStudy.java` 是同一套算法：页面边录边算，人不用点保存
 * 才知道这套量具够不够用。**改这里必须同步改 Java**，`test/grr.test.mjs` 里锁了
 * 一组样例，两边的数字对不上就会挂。
 *
 * 刻意只算数、不生成文案：结论之外那几句「先查量具分辨力还是先统一定位基准」
 * 只留在服务端一处。否则同一句话两份副本，改了一处忘了另一处，
 * 页面上就会出现两种说法。
 */

export const MIN_APPRAISERS = 2
export const MAX_APPRAISERS = 5
export const MIN_PARTS = 2
export const MAX_PARTS = 10
export const MIN_TRIALS = 2
export const MAX_TRIALS = 3

export const VERDICT_GOOD = 'GOOD'
export const VERDICT_MARGINAL = 'MARGINAL'
export const VERDICT_BAD = 'BAD'
export const VERDICT_UNKNOWN = 'UNKNOWN'

// AIAG MSA 手册第四版公布的 K 值（= 6/d2*）。不是运行时反算的
const K1 = [0, 0, 0.8862, 0.5908, 0]
const K2 = [0, 0, 0.7071, 0.5231, 0.4467, 0.4030]
const K3 = [0, 0, 0.7071, 0.5231, 0.4467, 0.4030, 0.3742, 0.3534, 0.3375, 0.3249, 0.3146]

function round(v, scale) {
  if (!Number.isFinite(v)) return 0
  const p = Math.pow(10, scale)
  return Math.round(v * p) / p
}

/** 结构校验，和 Java 的 checkShape 同一句话 */
export function checkShape(appraisers, parts, trials) {
  if (appraisers < MIN_APPRAISERS || appraisers > MAX_APPRAISERS) {
    return `评价人要 ${MIN_APPRAISERS}~${MAX_APPRAISERS} 个，现在是 ${appraisers} 个`
  }
  if (parts < MIN_PARTS || parts > MAX_PARTS) {
    return `零件要 ${MIN_PARTS}~${MAX_PARTS} 件，现在是 ${parts} 件`
  }
  if (trials < MIN_TRIALS || trials > MAX_TRIALS) {
    return `每件重复测 ${MIN_TRIALS}~${MAX_TRIALS} 次，现在是 ${trials} 次`
  }
  return null
}

/** 按 %GRR 定档。10 / 30 取闭区间——和页面上显示的数是同一个（都定标到 2 位） */
export function gradeOf(pct) {
  if (pct <= 10) return VERDICT_GOOD
  return pct <= 30 ? VERDICT_MARGINAL : VERDICT_BAD
}

export function gradeLabel(verdict) {
  switch (verdict) {
    case VERDICT_GOOD:
      return '可接受'
    case VERDICT_MARGINAL:
      return '有条件接受'
    case VERDICT_BAD:
      return '不可接受'
    default:
      return '算不出'
  }
}

/**
 * 算一次研究。
 *
 * @param readings [评价人][零件][试次] 的数值三维数组
 * @param lsl 规格下限，没有传 null
 * @param usl 规格上限，没有传 null
 * @returns null 表示读数还没填全；填全了返回各项指标（字段名与后端一致）
 */
export function compute(readings, lsl, usl) {
  if (!readings || !readings.length || !readings[0] || !readings[0].length) return null
  const a = readings.length
  const b = readings[0].length
  const n = readings[0][0] ? readings[0][0].length : 0
  if (!a || !b || !n) return null

  const cellMean = []
  const cellRange = []
  let sum = 0
  let rangeSum = 0
  let cells = 0
  for (let i = 0; i < a; i++) {
    cellMean.push([])
    cellRange.push([])
    for (let j = 0; j < b; j++) {
      const vs = readings[i][j]
      if (!vs || vs.length !== n) return null
      let lo = Infinity
      let hi = -Infinity
      let s = 0
      for (let k = 0; k < n; k++) {
        const v = vs[k]
        if (v === null || v === undefined || v === '' || !Number.isFinite(Number(v))) return null
        const num = Number(v)
        s += num
        if (num < lo) lo = num
        if (num > hi) hi = num
      }
      cellMean[i].push(s / n)
      cellRange[i].push(hi - lo)
      sum += s
      rangeSum += hi - lo
      cells++
    }
  }

  const mean = sum / (cells * n)
  const rangeBar = rangeSum / cells
  const ev = rangeBar * K1[n]

  const appraiserMean = []
  const appraiserRange = []
  let aMin = Infinity
  let aMax = -Infinity
  for (let i = 0; i < a; i++) {
    let s = 0
    let r = 0
    for (let j = 0; j < b; j++) {
      s += cellMean[i][j]
      r += cellRange[i][j]
    }
    const m = s / b
    appraiserMean.push(round(m, 4))
    appraiserRange.push(round(r / b, 4))
    if (m < aMin) aMin = m
    if (m > aMax) aMax = m
  }
  const xDiff = aMax - aMin
  const avInner = Math.pow(xDiff * K2[a], 2) - (ev * ev) / (b * n)
  const avClamped = avInner < 0
  const av = Math.sqrt(Math.max(0, avInner))
  const grr = Math.hypot(ev, av)

  const partMean = []
  const partRange = []
  let pMin = Infinity
  let pMax = -Infinity
  for (let j = 0; j < b; j++) {
    let s = 0
    let lo = Infinity
    let hi = -Infinity
    for (let i = 0; i < a; i++) {
      s += cellMean[i][j]
      if (cellMean[i][j] < lo) lo = cellMean[i][j]
      if (cellMean[i][j] > hi) hi = cellMean[i][j]
    }
    const m = s / a
    partMean.push(round(m, 4))
    partRange.push(round(hi - lo, 4))
    if (m < pMin) pMin = m
    if (m > pMax) pMax = m
  }
  const pv = (pMax - pMin) * K3[b]
  const tv = Math.hypot(grr, pv)

  if (tv <= 0) {
    return {
      appraisers: a, parts: b, trials: n,
      mean: round(mean, 4), rangeBar: round(rangeBar, 4),
      ev: 0, av: 0, grr: 0, pv: 0, tv: 0,
      pctEv: 0, pctAv: 0, pctGrr: 0, pctPv: 0, pctGrrTol: null, ndc: 0,
      verdict: VERDICT_UNKNOWN,
      appraiserMean, appraiserRange, partMean, partRange, avClamped
    }
  }

  const pctEv = round((100 * ev) / tv, 2)
  const pctAv = round((100 * av) / tv, 2)
  const pctGrr = round((100 * grr) / tv, 2)
  const pctPv = round((100 * pv) / tv, 2)
  const ndc = grr <= 0 ? 99 : Math.floor((1.41 * pv) / grr)
  const pctGrrTol =
    lsl !== null && lsl !== undefined && usl !== null && usl !== undefined && Number(usl) > Number(lsl)
      ? round((100 * grr) / (Number(usl) - Number(lsl)), 2)
      : null

  return {
    appraisers: a, parts: b, trials: n,
    mean: round(mean, 4), rangeBar: round(rangeBar, 4),
    ev: round(ev, 4), av: round(av, 4), grr: round(grr, 4), pv: round(pv, 4), tv: round(tv, 4),
    pctEv, pctAv, pctGrr, pctPv, pctGrrTol, ndc,
    verdict: gradeOf(pctGrr),
    appraiserMean, appraiserRange, partMean, partRange, avClamped
  }
}

/**
 * 已填格数 / 总格数。
 *
 * 录入表是 3×10×3 = 90 个格子，没有进度提示的话人不知道还剩几个没填，
 * 点保存被驳回一次就得从头数一遍。
 */
export function filledCells(readings) {
  let filled = 0
  let total = 0
  if (!readings) return { filled, total }
  readings.forEach((parts) => {
    if (!parts) return
    parts.forEach((vs) => {
      if (!vs) return
      vs.forEach((v) => {
        total++
        if (v !== null && v !== undefined && v !== '' && Number.isFinite(Number(v))) filled++
      })
    })
  })
  return { filled, total }
}
