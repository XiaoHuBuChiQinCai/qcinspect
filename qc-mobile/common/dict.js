/**
 * 移动端文案映射的唯一出处。
 *
 * 原来 report.vue 和 todo.vue 各写了一份「返工/报废/让步接收/退货」，
 * 后来追溯页也要用，第三份就该抽出来了：这三处只要有一处把「让步接收」
 * 写成「让步放行」，同一个不良品在三个页面显示两种状态，客户截图来问
 * 我们自己也说不清哪个对。
 *
 * 对照关系以后端枚举为准：
 *   disposition        REWORK / SCRAP / CONCESSION / RETURN
 *   dispositionStatus  PENDING / PROCESSING / DONE / WAIVED
 *   inspection.result  PASS / FAIL
 *   batch.status       PENDING / INSPECTING / DONE
 */

export const DISPOSITION_TEXT = {
  REWORK: '返工',
  SCRAP: '报废',
  CONCESSION: '让步接收',
  RETURN: '退货'
}

export const DISPOSITION_STATUS_TEXT = {
  PENDING: '待处理',
  PROCESSING: '处理中',
  DONE: '已完成',
  WAIVED: '已让步'
}

export const BATCH_STATUS_TEXT = {
  PENDING: '待检',
  INSPECTING: '检验中',
  DONE: '已完成'
}

export const INSPECTION_RESULT_TEXT = {
  PASS: '合格',
  FAIL: '不合格',
  NA: '已作废'
}

/** 不良处置方式。未选定不是「未定」以外的空，别让界面出现空白格子 */
export function dispositionText(d) {
  return DISPOSITION_TEXT[d] || '未定'
}

export function dispositionStatusText(s) {
  return DISPOSITION_STATUS_TEXT[s] || s || ''
}

export function batchStatusText(s) {
  return BATCH_STATUS_TEXT[s] || s || ''
}

/** NA 是作废，不能当「不合格」展示：作废单说「不合格」比没有结论更误导 */
export function resultText(r) {
  return INSPECTION_RESULT_TEXT[r] || '未判定'
}

/** 日期只到分钟：现场看单子不需要秒，多两位反而挤 */
export function fmtTime(t) {
  return t ? String(t).replace('T', ' ').slice(0, 16) : '—'
}
