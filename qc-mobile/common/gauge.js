/**
 * 量具相关的说法只在这一个文件里。
 *
 * 「能不能用」的判定见 judgeGauge；校准结果的枚举见 CAL_RESULT_TEXT。
 * 别在页面里各写一份——同一个量具在量具页说「已停用」、在录入页说「不可用」，
 * 检验员会以为是两回事。
 */

/** 30 天内到期算「快到期」：提前一个月提醒，送检还来得及 */
export const SOON_DAYS = 30

const LEVEL_TEXT = {
  OK: '可用',
  SOON: '快到期',
  EXPIRED: '已超期',
  SUSPENDED: '已停用',
  SCRAPPED: '已报废',
  NOCAL: '未校准'
}

/** 校准结果，来自 V10 表注释：PASS 合格 / FAIL 不合格 / LIMITED 限用 */
export const CAL_RESULT_TEXT = {
  PASS: '合格',
  FAIL: '不合格',
  LIMITED: '限用'
}

/**
 * 台账状态。和「能不能用」不是一个维度：在用（ACTIVE）的量具也可能已经超期校准，
 * 所以状态照实显示、结论另算，别把「在用」当成「可用」。
 */
export const GAUGE_STATUS_TEXT = {
  ACTIVE: '在用',
  SUSPENDED: '停用',
  SCRAPPED: '报废'
}

export function gaugeStatusText(s) {
  return GAUGE_STATUS_TEXT[s] || s || ''
}

/** 不能用时该干什么。只说「不能用」等于把问题丢回去 */
export function gaugeAdvice(level) {
  return {
    EXPIRED: '先送检校准，合格后才能再用来判活',
    SUSPENDED: '维修后重新登记校准，结果合格即自动恢复可用',
    SCRAPPED: '已报废，请从台账换一把',
    NOCAL: '这把量具还没有任何校准记录，先登记一次校准'
  }[level] || ''
}

export function gaugeLevelText(level) {
  return LEVEL_TEXT[level] || level || ''
}

export function calResultText(r) {
  return CAL_RESULT_TEXT[r] || r || ''
}

/** 只有 OK 与 SOON 能选。超期、停用、报废、没校准过的量具后端提交时一律硬拦 */
export function gaugeUsable(level) {
  return level === 'OK' || level === 'SOON'
}

/** 日期只取前 10 位：后端 LocalDate 序列化成 "2026-11-05"，但别指望它一定不带时间 */
export function dateOnly(v) {
  return v ? String(v).slice(0, 10) : null
}

/**
 * @param g   量具，至少含 status / nextCalibrateDate
 * @param today 今天，'YYYY-MM-DD'。传进来而不是内部取，测试才能钉住边界
 */
export function judgeGauge(g, today) {
  if (!g) {
    return { level: 'NOCAL', text: '量具不存在', usable: false, days: null }
  }
  const status = g.status
  if (status === 'SCRAPPED') {
    return { level: 'SCRAPPED', text: '已报废', usable: false, days: null }
  }
  if (status === 'SUSPENDED') {
    // 停用可能是校准不合格，也可能是点检不合格。两句话的下一步不一样：
    // 一个要送计量院，一个可能只是标准件对不上。照实说，别写死
    const why = g.suspendReason ? String(g.suspendReason) : '上次校准不合格'
    return { level: 'SUSPENDED', text: '已停用：' + why, usable: false, days: null }
  }
  const next = dateOnly(g.nextCalibrateDate)
  const now = dateOnly(today)
  if (!next) {
    return { level: 'NOCAL', text: '没有校准记录', usable: false, days: null }
  }
  if (now && next < now) {
    const days = daysBetween(next, now)
    return {
      level: 'EXPIRED',
      text: days > 0 ? '已超期 ' + days + ' 天' : '已超期',
      usable: false,
      days
    }
  }
  if (now) {
    const left = daysBetween(now, next)
    if (left <= SOON_DAYS) {
      return {
        level: 'SOON',
        text: left === 0 ? '今天到期' : left + ' 天后到期',
        usable: true,
        days: left
      }
    }
  }
  return { level: 'OK', text: '可用', usable: true, days: null }
}

/**
 * 两个 'YYYY-MM-DD' 相差几天。
 *
 * <p>不用 `new Date(a) - new Date(b)`：带 '-' 的日期串在部分浏览器里会被当成本地时间、
 * 又被 Date.parse 当 UTC，跨时区差出一天。用 UTC 构造再按天取整，只算日历日。
 */
export function daysBetween(from, to) {
  const a = dateOnly(from)
  const b = dateOnly(to)
  if (!a || !b) return 0
  const ms = Date.UTC(
    Number(b.slice(0, 4)), Number(b.slice(5, 7)) - 1, Number(b.slice(8, 10))
  ) - Date.UTC(
    Number(a.slice(0, 4)), Number(a.slice(5, 7)) - 1, Number(a.slice(8, 10))
  )
  return Math.round(ms / 86400000)
}
