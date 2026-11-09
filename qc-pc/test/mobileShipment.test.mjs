import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import {
  canVoid,
  checkVoidReason,
  displayNameOf,
  shipmentStatusText,
  sortShipments,
  voidBlocker,
  voidEffect,
  VOID_REASONS
} from '../../qc-mobile/common/shipment.js'

const HERE = dirname(fileURLToPath(import.meta.url))
const ROOT = join(HERE, '..', '..')

const ADMIN = { id: 1, username: 'admin', realName: '系统管理员', role: 'ADMIN' }
const ZHANG = { id: 2, username: 'zhangsan', realName: '张三', role: 'INSPECTOR' }
const LI = { id: 3, username: 'lisi', realName: '李四', role: 'INSPECTOR' }

const ship = (over = {}) => ({
  id: 1,
  shipmentNo: 'SH20261109001',
  customer: '宁波华翔',
  qty: 3,
  shippedAt: '2026-11-09 10:00:00',
  status: 'NORMAL',
  createdBy: '张三',
  ...over
})

/**
 * 「这张单是不是我自己登记的」必须与后端同一规则算。
 *
 * 后端 `created_by` 存的是 `LoginUser.displayName()`，而 displayName 是
 * 「有真实姓名用真实姓名，否则用账号」。前端要是只比 realName，
 * 没填姓名的账号永远认不出自己的单——界面上写着「张三 登记」，
 * 而当前用户就叫张三，点红冲却被告知「只能红冲自己登记的单」。
 */
test('展示名与后端 LoginUser.displayName() 同一规则', () => {
  assert.equal(displayNameOf(ZHANG), '张三')
  // 没填真实姓名 → 退回账号，跟 Java 那行三元一样
  assert.equal(displayNameOf({ username: 'wangwu', realName: '' }), 'wangwu')
  assert.equal(displayNameOf({ username: 'wangwu', realName: '   ' }), 'wangwu')
  assert.equal(displayNameOf(null), '')
})

test('displayNameOf 的规则确实是从 Java 源码里抄的，不是自己编的', () => {
  const java = readFileSync(
    join(ROOT, 'qc-server/src/main/java/com/qc/inspect/auth/LoginUser.java'),
    'utf8'
  )
  assert.match(java, /realName == null \|\| realName\.isBlank\(\) \? username : realName/)
})

test('本人可以红冲自己登记的单', () => {
  assert.equal(voidBlocker(ship(), ZHANG), null)
  assert.equal(canVoid(ship(), ZHANG), true)
})

test('别人的单不能红冲，且要说清是谁登记的', () => {
  const why = voidBlocker(ship(), LI)
  assert.equal(canVoid(ship(), LI), false)
  assert.match(why, /张三/)
  assert.match(why, /只能红冲自己登记的单/)
})

test('管理员可以红冲任何人登记的单', () => {
  assert.equal(canVoid(ship(), ADMIN), true)
  assert.equal(canVoid(ship({ createdBy: '李四' }), ADMIN), true)
})

test('管理员先判、归属后判——反了会把管理员也挡住', () => {
  // 这张单不是管理员登记的，但管理员照样能红冲
  const why = voidBlocker(ship({ createdBy: '李四' }), ADMIN)
  assert.equal(why, null)
})

test('已红冲的单不能再红冲', () => {
  const why = voidBlocker(ship({ status: 'VOID', voidReason: '客户取消订单' }), ZHANG)
  assert.match(why, /已经红冲过/)
  // 管理员也不行：这不是权限问题，是这张单已经不存在了
  assert.equal(canVoid(ship({ status: 'VOID' }), ADMIN), false)
})

test('登记人为空只有管理员能动', () => {
  assert.match(voidBlocker(ship({ createdBy: null }), ZHANG), /只能请管理员/)
  assert.equal(canVoid(ship({ createdBy: null }), ADMIN), true)
})

test('拿不到登录人不放行，而不是当成管理员', () => {
  assert.match(voidBlocker(ship(), null), /重新登录/)
  assert.equal(canVoid(ship(), null), false)
})

test('理由只判空，与后端的门槛一致', () => {
  assert.match(checkVoidReason(''), /必须写理由/)
  assert.match(checkVoidReason('   '), /必须写理由/)
  assert.match(checkVoidReason(null), /必须写理由/)
  // 一个字也是理由：后端只判 isBlank，前端别自己加最小长度
  assert.equal(checkVoidReason('错'), null)
  assert.equal(checkVoidReason('客户取消订单'), null)
})

test('checkVoidReason 的判法与 ShipmentService 一致', () => {
  const java = readFileSync(
    join(ROOT, 'qc-server/src/main/java/com/qc/inspect/service/ShipmentService.java'),
    'utf8'
  )
  // 后端就是 isBlank(reason) 一条，没有长度要求
  assert.match(java, /if \(isBlank\(reason\)\) \{[\s\S]{0,120}作废必须写理由/)
  assert.doesNotMatch(java, /reason\.trim\(\)\.length\(\)\s*</)
})

test('排序：正常的在前，红冲的沉底，同类按时间倒序', () => {
  const rows = [
    { id: 1, status: 'VOID', shippedAt: '2026-11-09 12:00:00' },
    { id: 2, status: 'NORMAL', shippedAt: '2026-11-08 09:00:00' },
    { id: 3, status: 'NORMAL', shippedAt: '2026-11-09 08:00:00' },
    { id: 4, status: 'VOID', shippedAt: '2026-11-07 09:00:00' }
  ]
  const ids = sortShipments(rows).map(r => r.id)
  assert.deepEqual(ids, [3, 2, 1, 4])
})

test('排序不修改原数组', () => {
  const rows = [ship({ id: 1, status: 'VOID' }), ship({ id: 2, status: 'NORMAL' })]
  const before = rows.map(r => r.id)
  sortShipments(rows)
  assert.deepEqual(rows.map(r => r.id), before)
})

test('时间相同按 id 倒序，空时间不炸', () => {
  const ids = sortShipments([
    { id: 1, status: 'NORMAL', shippedAt: '2026-11-09 10:00:00' },
    { id: 2, status: 'NORMAL', shippedAt: '2026-11-09 10:00:00' },
    { id: 3, status: 'NORMAL', shippedAt: null }
  ]).map(r => r.id)
  assert.deepEqual(ids, [2, 1, 3])
})

test('状态文案：未知状态原样回显，不硬翻成「正常」', () => {
  assert.equal(shipmentStatusText('NORMAL'), '正常')
  assert.equal(shipmentStatusText('VOID'), '已红冲')
  assert.equal(shipmentStatusText('HOLD'), 'HOLD')
  assert.equal(shipmentStatusText(null), '—')
})

test('红冲影响说明带上件数', () => {
  assert.equal(voidEffect(ship({ qty: 3 })), '红冲后这 3 件退回批次，可以重新登记出货')
  assert.match(voidEffect(ship({ qty: 0 })), /重新登记/)
  assert.match(voidEffect(null), /重新登记/)
})

test('理由芯片是现场说得出的话，不是空数组', () => {
  assert.ok(Array.isArray(VOID_REASONS) && VOID_REASONS.length >= 4)
  VOID_REASONS.forEach(r => assert.ok(r.trim().length > 0))
  assert.ok(VOID_REASONS.includes('客户取消订单'))
})
