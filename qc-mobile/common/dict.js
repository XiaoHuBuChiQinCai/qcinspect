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

/**
 * 客诉类别。这几项**不在数据库里**，白名单写在 ComplaintService.CATEGORIES
 * （Java 里写死，因为类别是质量体系的固定用语，不该现场自造），
 * 所以 mobileDict.test.mjs 是去读那段 Java 源码来比对的，不是读 SQL。
 */
export const COMPLAINT_CATEGORY_TEXT = {
  APP: '外观',
  SIZE: '尺寸',
  PERF: '性能',
  PACK: '包装',
  MARK: '标识',
  OTHER: '其他'
}

export const COMPLAINT_STATUS_TEXT = {
  OPEN: '待处理',
  PROCESSING: '处理中',
  CLOSED: '已关闭'
}

/** 严重度。不良项（qc_defect_code）不用这套，它是 CRITICAL/MAJOR/MINOR */
export const SEVERITY_TEXT = {
  HIGH: '严重',
  MEDIUM: '一般',
  LOW: '轻微'
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

export function complaintCategoryText(c) {
  return COMPLAINT_CATEGORY_TEXT[c] || c || '其他'
}

export function complaintStatusText(s) {
  return COMPLAINT_STATUS_TEXT[s] || s || ''
}

export function severityText(s) {
  return SEVERITY_TEXT[s] || s || '—'
}

/** 日期只到分钟：现场看单子不需要秒，多两位反而挤 */
export function fmtTime(t) {
  return t ? String(t).replace('T', ' ').slice(0, 16) : '—'
}

/**
 * 今天的日期，YYYY-MM-DD。
 *
 * **别用 `new Date().toISOString().slice(0, 10)`**：那是 UTC 日期，
 * 北京时间早上 8 点之前取到的是「昨天」，客诉页的日期选择器会连今天都选不了。
 */
export function todayLocal(now) {
  const d = now || new Date()
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}
