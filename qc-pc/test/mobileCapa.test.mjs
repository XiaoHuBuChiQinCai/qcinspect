import test from 'node:test'
import assert from 'node:assert/strict'
import {
  statusTone, isOpen, canStart, canEdit, canSubmit,
  missingForSubmit, daysBetween, dueLabel, summaryLine
} from '../../qc-mobile/common/capa.js'

/**
 * 移动端纠正措施的显示判定。
 *
 * 这里只测「该不该出按钮」「还差什么字段」「期限怎么说成人话」。
 * 判定文案（为什么被拒、下一步做什么）不在前端，只有服务端 CapaRules 一处——
 * 所以这个文件里一个中文结论句都不该出现，出现就说明有人又把话抄了一份。
 */

const ALL_STATUS = ['OPEN', 'DOING', 'VERIFYING', 'CLOSED', 'CANCELLED']

test('statusTone：五个状态各有自己的色调，未知状态不炸', () => {
  assert.equal(statusTone('OPEN'), 'idle')
  assert.equal(statusTone('DOING'), 'doing')
  assert.equal(statusTone('VERIFYING'), 'wait')
  assert.equal(statusTone('CLOSED'), 'done')
  assert.equal(statusTone('CANCELLED'), 'drop')
  assert.equal(statusTone(undefined), 'idle')
  assert.equal(statusTone('WHATEVER'), 'idle')
})

test('isOpen：只有还在走流程的三种算「开着」，关掉和撤销的都不算', () => {
  assert.equal(isOpen('OPEN'), true)
  assert.equal(isOpen('DOING'), true)
  assert.equal(isOpen('VERIFYING'), true)
  assert.equal(isOpen('CLOSED'), false)
  assert.equal(isOpen('CANCELLED'), false)
  assert.equal(isOpen(null), false)
})

test('三个动作按钮各自只在对应状态出现，且互不重叠', () => {
  // 待分析：能开工、能写原因、不能提交（还没做就先报）
  assert.equal(canStart('OPEN'), true)
  assert.equal(canEdit('OPEN'), true)
  assert.equal(canSubmit('OPEN'), false)

  // 措施执行中：不能再「开工」，能继续改，能提交
  assert.equal(canStart('DOING'), false)
  assert.equal(canEdit('DOING'), true)
  assert.equal(canSubmit('DOING'), true)

  // 待验证：冻结。要改得让验证人判无效退回来
  assert.equal(canStart('VERIFYING'), false)
  assert.equal(canEdit('VERIFYING'), false)
  assert.equal(canSubmit('VERIFYING'), false)

  for (const s of ['CLOSED', 'CANCELLED', null]) {
    assert.equal(canStart(s), false, `${s} 不该能开工`)
    assert.equal(canEdit(s), false, `${s} 不该能改`)
    assert.equal(canSubmit(s), false, `${s} 不该能提交`)
  }
})

test('missingForSubmit：两个必填项都齐了才返回空数组', () => {
  assert.deepEqual(missingForSubmit({ rootCause: '刀补未计热变形', actionPlan: '按班次修正' }), [])
  assert.deepEqual(missingForSubmit({ rootCause: '刀补未计热变形', actionPlan: '' }), ['纠正措施'])
  assert.deepEqual(missingForSubmit({ rootCause: '', actionPlan: '按班次修正' }), ['根本原因'])
  assert.deepEqual(missingForSubmit({}), ['根本原因', '纠正措施'])
  assert.deepEqual(missingForSubmit(null), ['根本原因', '纠正措施'])
})

test('missingForSubmit：只有空格的也算没写', () => {
  // 空格能骗过「非空」判断，但提交到服务端会被 trim 后再判一次，白让人点一次
  assert.deepEqual(missingForSubmit({ rootCause: '   ', actionPlan: '\n\t ' }), ['根本原因', '纠正措施'])
  assert.deepEqual(missingForSubmit({ rootCause: ' 有 ', actionPlan: ' ' }), ['纠正措施'])
})

test('daysBetween：按日历日算，跨月跨年都对', () => {
  assert.equal(daysBetween('2026-11-11', '2026-11-11'), 0)
  assert.equal(daysBetween('2026-11-11', '2026-11-25'), 14)
  assert.equal(daysBetween('2026-11-11', '2026-12-01'), 20)
  assert.equal(daysBetween('2026-12-31', '2027-01-01'), 1)
  assert.equal(daysBetween('2026-03-01', '2026-02-28'), -1)
  // 带时分秒也照切前 10 位：iOS Safari 上 new Date('2026-11-11 10:00:00') 是 Invalid Date
  assert.equal(daysBetween('2026-11-11T08:00:00', '2026-11-18T23:59:59'), 7)
})

test('daysBetween：残缺或空值返回 null，不返回 NaN', () => {
  assert.equal(daysBetween('', '2026-11-25'), null)
  assert.equal(daysBetween('2026-11-11', ''), null)
  assert.equal(daysBetween(null, undefined), null)
  assert.equal(daysBetween('abc', '2026-11-25'), null)
})

test('dueLabel：超期以服务端算的为准，不自己拿本地时间和完成日比', () => {
  // 完成日已经过了，但服务端说没超期（比如已经关闭）——那就不能飘红
  const closed = { dueDate: '2026-11-01', status: 'CLOSED', overdue: false, overdueDays: 0 }
  assert.equal(dueLabel(closed, '2026-11-11'), '要求 2026-11-01 完成')

  const late = { dueDate: '2026-11-01', status: 'DOING', overdue: true, overdueDays: 10 }
  assert.equal(dueLabel(late, '2026-11-11'), '超期 10 天')

  // overdueDays 缺失时按 0 说，不说「超期 undefined 天」
  assert.equal(dueLabel({ dueDate: '2026-11-01', status: 'OPEN', overdue: true }, '2026-11-11'), '超期 0 天')
})

test('dueLabel：没超期的说「还剩几天」，今天到期单独说', () => {
  const base = { status: 'DOING', overdue: false, overdueDays: 0 }
  assert.equal(dueLabel({ ...base, dueDate: '2026-11-25' }, '2026-11-11'), '还剩 14 天')
  assert.equal(dueLabel({ ...base, dueDate: '2026-11-12' }, '2026-11-11'), '还剩 1 天')
  assert.equal(dueLabel({ ...base, dueDate: '2026-11-11' }, '2026-11-11'), '今天到期')
})

test('dueLabel：没期限就说空，不编一句「未设置」占着地方', () => {
  assert.equal(dueLabel({ status: 'DOING' }, '2026-11-11'), '')
  assert.equal(dueLabel({ status: 'DOING', dueDate: null }, '2026-11-11'), '')
  assert.equal(dueLabel(null, '2026-11-11'), '')
})

test('待验证的也算开着：期限照常提示，因为审核员还压着它', () => {
  const row = { dueDate: '2026-11-25', status: 'VERIFYING', overdue: false, overdueDays: 0 }
  assert.equal(dueLabel(row, '2026-11-11'), '还剩 14 天')
  assert.equal(isOpen(row.status), true)
})

/* ---------------- 首页那一格的摘要句 ---------------- */

test('摘要优先级：超期压过快到期，快到期压过待验证', () => {
  // 三个都有的时候只说最要紧的那一条，不堆成一句话
  assert.equal(
    summaryLine({ capaOverdue: 2, capaDueSoon: 3, capaVerifying: 1, capaOpen: 9 }),
    '2 条超期没改')
  assert.equal(
    summaryLine({ capaOverdue: 0, capaDueSoon: 3, capaVerifying: 1, capaOpen: 9 }),
    '3 条 3 天内到期')
  assert.equal(
    summaryLine({ capaDueSoon: 0, capaVerifying: 1, capaOpen: 9 }),
    '1 条待验证')
  assert.equal(summaryLine({ capaOpen: 9 }), '9 条在改')
  assert.equal(summaryLine({}), '没有在改的措施')
  assert.equal(summaryLine(null), '没有在改的措施')
})

test('天数用服务端下发的，不在前端写死 3', () => {
  // 窗口改成 5 天，句子得跟着变——写死的话页面上还写着 3，而且它已经不准了也没人发现
  assert.equal(summaryLine({ capaDueSoon: 1, capaDueSoonDays: 5 }), '1 条 5 天内到期')
  // 老后端没这个字段时退回 3，不至于说出「NaN 天内到期」
  assert.equal(summaryLine({ capaDueSoon: 1 }), '1 条 3 天内到期')
})

test('字段缺省或给成字符串都不该说出 NaN', () => {
  assert.equal(summaryLine({ capaOverdue: '2' }), '2 条超期没改')
  assert.equal(summaryLine({ capaDueSoon: undefined }), '没有在改的措施')
})
