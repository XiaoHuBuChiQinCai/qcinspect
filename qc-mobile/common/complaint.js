/**
 * 客诉受理与关闭的判定逻辑。
 *
 * <p>这里刻意零 import：qc-mobile 的 package.json 没有 `type: module`，
 * 一旦 import 到碰 uni API 的模块，Node 会把它判成 CommonJS 直接报
 * 「Named export not found」，单测就跑不起来。
 */

/** 后端 ComplaintService.close 的三项必填，顺序也一致：根因 → 措施 → 责任归属 */
export const RESPONSIBILITY_OPTIONS = ['供应商', '本厂制程', '设计', '运输', '客户方']

/**
 * 关闭前还缺什么。返回空串表示可以提交。
 *
 * <p>这三项的提示文案与后端逐字一致——前端先拦一次只是为了少跑一趟网络，
 * 真正拦得住的是 `ComplaintService.close`。两边文案不同时，用户会以为是两个问题。
 */
export function closeBlocker(form) {
  const f = form || {}
  if (!String(f.rootCause == null ? '' : f.rootCause).trim()) {
    return '关闭前必须填写根本原因'
  }
  if (!String(f.action == null ? '' : f.action).trim()) {
    return '关闭前必须填写纠正预防措施'
  }
  if (!String(f.responsibility == null ? '' : f.responsibility).trim()) {
    return '关闭前必须填写责任归属（供应商 / 本厂制程 / 设计 / 运输 / 客户方）'
  }
  return ''
}

/** 已关闭的客诉不能再受理，也不能重复关闭；后端两种操作都会拒 */
export function isClosed(status) {
  return status === 'CLOSED'
}

/** 关闭按钮只对审核员和管理员显示，与后端 @PreAuthorize 对齐。界面约束不是权限约束 */
export function canCloseRole(role) {
  return role === 'ADMIN' || role === 'REVIEWER'
}

/** 严重度权重。**别按字母序**：HIGH/MEDIUM/LOW 的字母序恰好把「严重」排在中间 */
export function severityWeight(severity) {
  if (severity === 'HIGH') return 0
  if (severity === 'MEDIUM') return 1
  if (severity === 'LOW') return 2
  // 认不出的排最后，但也别丢掉：后端加了新等级而前端没跟上时不至于整条消失
  return 9
}

/**
 * 客诉列表排序：严重的在前，同严重度按日期新的在前。
 *
 * <p>现场看这页只有一个顺序要求——「哪条更急」。按登记时间排会让一条轻微的
 * 包装划伤压住严重的尺寸超差，那条已经发出去了。
 */
export function sortComplaints(list) {
  const rows = Array.isArray(list) ? list.slice() : []
  rows.sort((a, b) => {
    const w = severityWeight(a && a.severity) - severityWeight(b && b.severity)
    if (w !== 0) return w
    const da = String((a && a.complaintDate) || '')
    const db = String((b && b.complaintDate) || '')
    if (da !== db) return da < db ? 1 : -1
    return Number((b && b.id) || 0) - Number((a && a.id) || 0)
  })
  return rows
}

/** 未关闭条数，首页角标用 */
export function openCount(list) {
  const rows = Array.isArray(list) ? list : []
  return rows.filter(r => r && !isClosed(r.status)).length
}
