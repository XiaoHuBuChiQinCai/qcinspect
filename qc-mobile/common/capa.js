/**
 * 纠正措施在手机上的显示判定。
 *
 * <p>只回答两件事：这一步该不该出现按钮、点之前还差什么。**不生成判定文案**——
 * 为什么被拒、下一步做什么，话只有服务端 {@code CapaRules} 一处。
 * 两端各写一套话早晚会变成「手机上说得通、服务端说不行」，而这类话恰恰是给人看的重点。
 *
 * <p>所以这里没有 `checkSubmitVerify` 那样的函数，只有 `missingForSubmit` 返回缺哪几个字段，
 * 页面拼成「还差：根本原因、纠正措施」这种中性提示。真正的闸门在服务端和库层 CHECK。
 */

/** 状态色调，只影响样式，不产生文字 */
export function statusTone(status) {
  if (status === 'OPEN') return 'idle'
  if (status === 'DOING') return 'doing'
  if (status === 'VERIFYING') return 'wait'
  if (status === 'CLOSED') return 'done'
  if (status === 'CANCELLED') return 'drop'
  return 'idle'
}

/** 还没结案（含待验证）：这类才谈得上「期限」和「超期」 */
export function isOpen(status) {
  return status === 'OPEN' || status === 'DOING' || status === 'VERIFYING'
}

/** 待分析才能点「开始处理」 */
export function canStart(status) {
  return status === 'OPEN'
}

/** 按原因与措施只能在「待分析 / 措施执行中」写，进了待验证就冻结 */
export function canEdit(status) {
  return status === 'OPEN' || status === 'DOING'
}

/** 提交验证只在「措施执行中」——还没开始就提交等于没做先报 */
export function canSubmit(status) {
  return status === 'DOING'
}

function filled(v) {
  return v !== null && v !== undefined && String(v).trim() !== ''
}

/**
 * 提交验证还差什么字段，空数组表示齐了。
 * 只给字段名，不给「没什么不写」那类劝告——劝告在服务端一处。
 */
export function missingForSubmit(row) {
  if (!row) return ['根本原因', '纠正措施']
  const miss = []
  if (!filled(row.rootCause)) miss.push('根本原因')
  if (!filled(row.actionPlan)) miss.push('纠正措施')
  return miss
}

/**
 * 两个日期差几天（后者减前者），按本地日历日算。
 *
 * 不用 `new Date('2026-11-25')` 之外的花样：iOS Safari 上带时分秒的字符串是 Invalid Date，
 * 这里干脆自己拆数字再走 Date.UTC，任何机型都不会跑偏。
 */
export function daysBetween(from, to) {
  const parse = s => String(s === null || s === undefined ? '' : s).slice(0, 10).split('-').map(Number)
  const [ay, am, ad] = parse(from)
  const [by, bm, bd] = parse(to)
  if (!ay || !am || !ad || !by || !bm || !bd) return null
  return Math.round((Date.UTC(by, bm - 1, bd) - Date.UTC(ay, am - 1, ad)) / 86400000)
}

/**
 * 期限说成一句人话。
 *
 * <p>「超期」一律以服务端算的 `overdue / overdueDays` 为准——它判的是「还开着的才算超期」，
 * 关掉的单子过了完成日是历史不是拖延。这里只负责把「还剩几天」算出来，
 * 因为服务端没下发这个值，而人对「还剩 3 天」比对「2026-11-25」有感觉得多。
 */
export function dueLabel(row, today) {
  if (!row || !row.dueDate) return ''
  const d = String(row.dueDate).slice(0, 10)
  if (row.overdue) return `超期 ${Number(row.overdueDays) || 0} 天`
  if (!isOpen(row.status)) return `要求 ${d} 完成`
  const left = daysBetween(today, d)
  if (left === null) return `要求 ${d} 完成`
  if (left === 0) return '今天到期'
  if (left > 0) return `还剩 ${left} 天`
  return `要求 ${d} 完成`
}

/**
 * 首页「纠正措施」那一格的摘要句。
 *
 * <p>优先级固定：超期 > 快到期 > 待验证 > 在改。顺序不是随手排的——「超期」是事故，
 * 「快到期」是自己的活，「待验证」是别人的活，「在改」只是现状。把别人的活排在自己
 * 前面，这一格就变成「看着有事、点进去没事」，点两次以后人就再也不点了。
 *
 * <p>天数用服务端下发的 `capaDueSoonDays`，不在前端写死 3：窗口一改，
 * 页面上还写着「3 天内」，而且它已经不准了也没人会发现。
 */
export function summaryLine(summary) {
  const s = summary || {}
  const overdue = Number(s.capaOverdue) || 0
  const dueSoon = Number(s.capaDueSoon) || 0
  const verifying = Number(s.capaVerifying) || 0
  const open = Number(s.capaOpen) || 0
  const days = Number(s.capaDueSoonDays) || 3
  if (overdue) return overdue + ' 条超期没改'
  if (dueSoon) return dueSoon + ' 条 ' + days + ' 天内到期'
  if (verifying) return verifying + ' 条待验证'
  if (open) return open + ' 条在改'
  return '没有在改的措施'
}
