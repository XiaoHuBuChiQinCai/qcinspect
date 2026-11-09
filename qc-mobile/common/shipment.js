/**
 * 出货单的展示文案与红冲判定。
 *
 * 放成零依赖文件是为了能被 `node --test` 直接跑：qc-mobile 的 package.json 没有
 * `type: module`，一旦这里 import 了 api.js，整个文件会被判成 CommonJS，
 * 测试里 `import { canVoid }` 直接报 Named export not found。
 */

export const SHIPMENT_STATUS_TEXT = {
  NORMAL: '正常',
  VOID: '已红冲'
}

export function shipmentStatusText(status) {
  if (!status) return '—'
  return SHIPMENT_STATUS_TEXT[status] || status
}

/**
 * 常用红冲理由。
 *
 * 现场说得出的话就那几句，别让人在手机上敲一整段——敲得越费劲，写的越像
 * 「登记错了」这种一年后没人看得懂的四个字。
 */
export const VOID_REASONS = [
  '客户取消订单',
  '数量登记错了',
  '重复登记',
  '客户退货',
  '批次发错了'
]

/**
 * 当前登录人的展示名。
 *
 * **必须与后端 `LoginUser.displayName()` 同一规则**：有真实姓名用真实姓名，
 * 没有就用账号。`created_by` 列存的就是这个值，前端只比 `realName` 的话，
 * 没填姓名的账号会永远认不出自己的单——明明是自己登记的说「只能红冲别人的」。
 */
export function displayNameOf(user) {
  if (!user) return ''
  const real = String(user.realName || '').trim()
  if (real) return real
  return String(user.username || '').trim()
}

/**
 * 能不能红冲这张出货单。返回 `null` 表示可以，返回字符串表示不能的原因（直接给人看）。
 *
 * 与 `ShipmentService.voidShipment` 的三道门槛逐条对齐：
 * 已作废不能重复红冲、非本人且非管理员不能动、登记人为空只有管理员能动。
 * 理由必填那条放在 `checkVoidReason`，因为它是提交时才知道的。
 *
 * 顺序有讲究：**先判管理员再判归属**。反过来的话管理员会被
 * 「只能红冲自己登记的单」挡住——界面上写着能点，一点就被驳回。
 */
export function voidBlocker(row, user) {
  if (!row) return '出货单不存在'
  if (row.status === 'VOID') return '已经红冲过了，不用重复红冲'
  if (user && user.role === 'ADMIN') return null
  if (!row.createdBy) return '这张单没记下登记人，只能请管理员红冲'
  const me = displayNameOf(user)
  if (!me) return '拿不到当前登录人，请重新登录后再试'
  if (row.createdBy !== me) {
    return '只能红冲自己登记的单（这张是 ' + row.createdBy + ' 登记的）'
  }
  return null
}

export function canVoid(row, user) {
  return voidBlocker(row, user) === null
}

/**
 * 红冲理由校验。**只判空，不设最小长度**——后端 `voidShipment` 就是这么判的。
 *
 * 前端多拦一道「至少 5 个字」看着更严格，实际是让人先把界面上的话删掉再重写，
 * 最后写出来的还是「错了」。前后端判同一件事必须同一条线。
 */
export function checkVoidReason(text) {
  if (!String(text || '').trim()) {
    return '作废必须写理由。客户退货、登记错数量、重复登记都要写清楚'
  }
  return null
}

/**
 * 排序：正常的在前，已红冲的沉底；同类按出货时间倒序，时间相同按 id 倒序。
 *
 * **用字符串比，不用 `new Date()`**：`shippedAt` 是 `2026-11-09 10:00:00` 这种格式，
 * iOS Safari 解析它得到 Invalid Date（它只认 ISO 的 `T` 分隔），
 * 排序会静默失效——安卓上排得对、苹果上顺序乱，最难查的那类问题。
 * 格式固定，字典序就是时间序。
 */
export function sortShipments(rows) {
  return (rows || []).slice().sort((a, b) => {
    const av = a.status === 'VOID' ? 1 : 0
    const bv = b.status === 'VOID' ? 1 : 0
    if (av !== bv) return av - bv
    const at = String(a.shippedAt || '')
    const bt = String(b.shippedAt || '')
    if (at !== bt) return bt.localeCompare(at)
    return Number(b.id || 0) - Number(a.id || 0)
  })
}

/** 红冲确认弹层里那句人话：这几件退回来之后能干什么 */
export function voidEffect(row) {
  if (!row || !row.qty) return '红冲后这批货可以重新登记出货'
  return '红冲后这 ' + row.qty + ' 件退回批次，可以重新登记出货'
}
