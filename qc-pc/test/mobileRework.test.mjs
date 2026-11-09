import test from 'node:test'
import assert from 'node:assert/strict'
import * as pc from '../src/utils/rework.js'
import * as mobile from '../../qc-mobile/common/rework.js'

/**
 * 移动端与 PC 端的返修报数口径必须一致。
 *
 * 这不是「两边都测一遍」的重复劳动：真正的风险是有人只改了其中一边。
 * 改完在电脑上试通了，车间拿手机填却被打回来——而手机上是标准终端。
 * 所以这里比对的是同一批输入下两端返回的**同一句话**。
 */

const FINISH_CASES = [
  [5, 5, 0], [5, 4, 1], [5, 0, 5],
  [5, 3, 1], [5, 4, 2], [5, 6, -1], [5, -1, 6],
  [5, 5, ''], [5, '', 0], [5, null, null],
  [5, 2.5, 2.5], [1, 1, 0], [500, 499, 1]
]

const QTY_CASES = [null, '', 0, -3, 1, 2.5, 499, 500, 501, '12']

const OVERDUE_CASES = [
  ['2026-11-08', 'OPEN', '2026-11-09'],
  ['2026-11-09', 'OPEN', '2026-11-09'],
  ['2026-11-10', 'OPEN', '2026-11-09'],
  ['2026-11-01', 'DONE', '2026-11-09'],
  ['2026-11-01', 'CANCELLED', '2026-11-09'],
  ['2026-11-01', 'DOING', '2026-11-09'],
  [null, 'OPEN', '2026-11-09'],
  ['2026-11-08T16:00:00', 'OPEN', '2026-11-09']
]

test('两端报数提示逐字一致', () => {
  for (const [q, r, s] of FINISH_CASES) {
    assert.equal(
      mobile.checkFinish(q, r, s),
      pc.checkFinish(q, r, s),
      `checkFinish(${q}, ${r}, ${s}) 两端说法不一致`
    )
  }
})

test('两端送修数量校验逐字一致', () => {
  for (const q of QTY_CASES) {
    assert.equal(mobile.checkQty(q), pc.checkQty(q), `checkQty(${q}) 两端不一致`)
  }
})

test('两端超期判定一致', () => {
  for (const [due, status, today] of OVERDUE_CASES) {
    assert.equal(
      mobile.isOverdue(due, status, today),
      pc.isOverdue(due, status, today),
      `isOverdue(${due}, ${status}, ${today}) 两端不一致`
    )
  }
})

test('两端对照文案一致', () => {
  for (const [q, r, s] of FINISH_CASES) {
    assert.deepEqual(mobile.balanceText(q, r, s), pc.balanceText(q, r, s))
  }
})

test('两端上限都是 500', () => {
  assert.equal(mobile.MAX_QTY, pc.MAX_QTY)
  assert.equal(pc.MAX_QTY, 500)
})

/**
 * 这条守卫的意义：如果哪天有人把某一边改成「不超过 500 就行」的宽松口径，
 * 上面逐条比对会自动失败。这里单独立一条，让失败信息直接指向原因。
 */
test('返好 + 返废 必须正好等于送修——两端都不接受「少报」', () => {
  assert.equal(pc.checkFinish(5, 4, 0), '还差 1 件没交代去向')
  assert.equal(mobile.checkFinish(5, 4, 0), '还差 1 件没交代去向')
})
