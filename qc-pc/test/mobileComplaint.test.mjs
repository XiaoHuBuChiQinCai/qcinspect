import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

import {
  RESPONSIBILITY_OPTIONS,
  closeBlocker,
  isClosed,
  canCloseRole,
  severityWeight,
  sortComplaints,
  openCount
} from '../../qc-mobile/common/complaint.js'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const JAVA = path.resolve(
  HERE,
  '../../qc-server/src/main/java/com/qc/inspect/service/ComplaintService.java'
)

const ok = { rootCause: '供应商棒料直径超下偏差', action: '全检在库并加严来料', responsibility: '供应商' }

test('三项齐了才放行', () => {
  assert.equal(closeBlocker(ok), '')
})

test('缺哪一项就说哪一项，且顺序与后端一致：根因 → 措施 → 责任', () => {
  // 三项都缺时必须先说根因。反过来先报「责任归属」，人会以为前面已经填过了
  assert.equal(closeBlocker({}), '关闭前必须填写根本原因')
  assert.equal(closeBlocker({ rootCause: 'x', action: 'y' }), '关闭前必须填写责任归属（供应商 / 本厂制程 / 设计 / 运输 / 客户方）')
  assert.equal(closeBlocker({ rootCause: 'x' }), '关闭前必须填写纠正预防措施')
  assert.equal(closeBlocker({ action: 'y', responsibility: '供应商' }), '关闭前必须填写根本原因')
})

test('空白串不算填了', () => {
  // 手机上空格很容易带出来，只判 null 会把一条空格当根因提交上去
  assert.equal(closeBlocker({ rootCause: '   ', action: 'y', responsibility: '供应商' }), '关闭前必须填写根本原因')
  assert.equal(closeBlocker({ rootCause: 'x', action: '\n\t', responsibility: '供应商' }), '关闭前必须填写纠正预防措施')
})

test('null / undefined 不炸', () => {
  assert.equal(closeBlocker(null), '关闭前必须填写根本原因')
  assert.equal(closeBlocker(undefined), '关闭前必须填写根本原因')
  assert.equal(closeBlocker({ rootCause: undefined, action: undefined, responsibility: undefined }), '关闭前必须填写根本原因')
})

test('关闭权限只认审核员与管理员，和后端 @PreAuthorize 对齐', () => {
  assert.equal(canCloseRole('ADMIN'), true)
  assert.equal(canCloseRole('REVIEWER'), true)
  assert.equal(canCloseRole('INSPECTOR'), false)
  assert.equal(canCloseRole(undefined), false)
})

test('已关闭的两种状态判断', () => {
  assert.equal(isClosed('CLOSED'), true)
  assert.equal(isClosed('OPEN'), false)
  assert.equal(isClosed('PROCESSING'), false)
  assert.equal(isClosed(null), false)
})

test('严重度权重别靠字母序', () => {
  // HIGH/MEDIUM/LOW 的字母序是 HIGH(0) < LOW(2) < MEDIUM(1)... 其实字母序里
  // LOW 会排在 MEDIUM 前面，于是「轻微」压住「一般」。这里显式钉住顺序
  assert.ok(severityWeight('HIGH') < severityWeight('MEDIUM'))
  assert.ok(severityWeight('MEDIUM') < severityWeight('LOW'))
  assert.equal(severityWeight('HIGH'), 0)
  // 认不出的排最后但不丢：后端加了新等级而前端没跟上时，那条也不至于消失
  assert.equal(severityWeight('BLOCKER'), 9)
})

test('列表排序：严重的在前，同严重度按日期新的在前', () => {
  const rows = [
    { id: 1, severity: 'LOW', complaintDate: '2026-11-05' },
    { id: 2, severity: 'HIGH', complaintDate: '2026-11-01' },
    { id: 3, severity: 'MEDIUM', complaintDate: '2026-11-03' },
    { id: 4, severity: 'HIGH', complaintDate: '2026-11-04' },
    { id: 5, severity: 'HIGH', complaintDate: '2026-11-04' }
  ]
  const sorted = sortComplaints(rows)
  // 4 与 5 同一天，按 id 倒序——先登的在前，后登记的不会压在先登记的前面
  assert.deepEqual(sorted.map(r => r.id), [5, 4, 2, 3, 1])
  // 不改原数组：模板里直接 sort 会把接口返回的 records 顺序改掉，翻页时看着像乱跳
  assert.deepEqual(rows.map(r => r.id), [1, 2, 3, 4, 5])
})

test('排序遇到脏数据不崩', () => {
  assert.deepEqual(sortComplaints(null), [])
  assert.deepEqual(sortComplaints(undefined), [])
  const sorted = sortComplaints([null, { id: 1, severity: 'HIGH' }])
  assert.equal(sorted.length, 2)
})

test('未关闭条数只数真正没关的', () => {
  assert.equal(openCount([]), 0)
  assert.equal(openCount(null), 0)
  assert.equal(openCount([
    { status: 'OPEN' },
    { status: 'PROCESSING' },
    { status: 'CLOSED' }
  ]), 2)
})

test('责任归属选项与后端提示里列的一致', () => {
  const java = fs.readFileSync(JAVA, 'utf8')
  // 后端那句错误提示把责任方枚举写死在文案里，芯片组照它来。
  // 后端改了文案而前端没跟，用户会看到两套说法
  assert.ok(java.includes('责任归属（供应商 / 本厂制程 / 设计 / 运输 / 客户方）'), '后端提示语变了')
  for (const opt of RESPONSIBILITY_OPTIONS) {
    assert.ok(
      java.includes(opt),
      `责任归属选项「${opt}」在后端 ComplaintService 里找不到，两边对不上`
    )
  }
})

test('前端提示文案与后端逐字一致', () => {
  const java = fs.readFileSync(JAVA, 'utf8')
  const cases = [
    ['关闭前必须填写根本原因', { rootCause: '' }],
    ['关闭前必须填写纠正预防措施', { rootCause: 'x' }],
    ['关闭前必须填写责任归属', { rootCause: 'x', action: 'y' }]
  ]
  for (const [msg, form] of cases) {
    assert.ok(java.includes(msg), `后端没有这句提示了：${msg}`)
    assert.ok(closeBlocker(form).startsWith(msg), `前端文案与后端不一致：${msg}`)
  }
})
