import test from 'node:test'
import assert from 'node:assert/strict'
import { checkQty, checkFinish, balanceText, isOverdue, todayLocal, MAX_QTY } from '../src/utils/rework.js'

/* ---------------- 送修数量 ---------------- */

test('送修数量：0 件和负数不是单据', () => {
  assert.equal(checkQty(0), '送修数量必须大于 0')
  assert.equal(checkQty(-2), '送修数量必须大于 0')
})

test('送修数量：不填要明说', () => {
  assert.equal(checkQty(''), '请填送修数量')
  assert.equal(checkQty(null), '请填送修数量')
  assert.equal(checkQty(undefined), '请填送修数量')
})

test('送修数量：小数要拒，件没有半件', () => {
  assert.equal(checkQty(2.5), '送修数量要填整数')
})

test('送修数量：上限 200，与检验单单次上限一致', () => {
  assert.equal(checkQty(MAX_QTY), null)
  assert.match(checkQty(MAX_QTY + 1), /拆成多张/)
})

/* ---------------- 完工报数 ---------------- */

test('报数：正好相等才放行', () => {
  assert.equal(checkFinish(5, 5, 0), null)
  assert.equal(checkFinish(5, 4, 1), null)
  assert.equal(checkFinish(5, 0, 5), null)
})

test('报数：少报要点出差几件', () => {
  assert.equal(checkFinish(5, 3, 1), '还差 1 件没交代去向')
})

test('报数：多报要怀疑记错票', () => {
  assert.equal(checkFinish(5, 4, 2), '比送修多了 1 件，是不是记错了票')
})

test('报数：返废填 0 可以，空着不行', () => {
  assert.equal(checkFinish(5, 5, 0), null)
  assert.match(checkFinish(5, 5, ''), /都要填/)
  assert.match(checkFinish(5, '', 0), /都要填/)
})

test('报数：负数拒掉', () => {
  assert.equal(checkFinish(5, 6, -1), '件数不能是负数')
})

test('对照文案：数字摆出来，别只说「不匹配」', () => {
  assert.deepEqual(balanceText(5, 4, 1), { text: '返好 4 + 返废 1 = 5 / 送修 5', ok: true })
  assert.deepEqual(balanceText(5, 3, 1), { text: '返好 3 + 返废 1 = 4 / 送修 5', ok: false })
  // 还没填的时候按 0 显示，不要把整行变成 NaN
  assert.deepEqual(balanceText(5, '', ''), { text: '返好 0 + 返废 0 = 0 / 送修 5', ok: false })
})

/* ---------------- 超期 ---------------- */

test('超期：当天到期不算超，昨天才算', () => {
  assert.equal(isOverdue('2026-11-09', 'OPEN', '2026-11-09'), false)
  assert.equal(isOverdue('2026-11-08', 'OPEN', '2026-11-09'), true)
  assert.equal(isOverdue('2026-11-10', 'OPEN', '2026-11-09'), false)
})

test('超期：完工和取消的不飘红', () => {
  assert.equal(isOverdue('2026-11-01', 'DONE', '2026-11-09'), false)
  assert.equal(isOverdue('2026-11-01', 'CANCELLED', '2026-11-09'), false)
  assert.equal(isOverdue('2026-11-01', 'DOING', '2026-11-09'), true)
})

test('超期：带时分秒的日期也能比，只看到天', () => {
  assert.equal(isOverdue('2026-11-08T16:00:00', 'OPEN', '2026-11-09'), true)
  assert.equal(isOverdue('2026-11-09T23:59:59', 'OPEN', '2026-11-09'), false)
})

test('超期：没期限就不催', () => {
  assert.equal(isOverdue(null, 'OPEN', '2026-11-09'), false)
  assert.equal(isOverdue('', 'OPEN', '2026-11-09'), false)
})

/* ---------------- 今天 ---------------- */

test('今天：按本地时区取，不走 UTC', () => {
  // 本地 11 月 9 日 00:30，UTC 还是 11 月 8 日 16:30。
  // toISOString().slice(0,10) 会给出 8 号，早上录的单会被算成昨天的
  assert.equal(todayLocal(new Date(2026, 10, 9, 0, 30)), '2026-11-09')
  assert.equal(todayLocal(new Date(2026, 10, 9, 23, 30)), '2026-11-09')
})
