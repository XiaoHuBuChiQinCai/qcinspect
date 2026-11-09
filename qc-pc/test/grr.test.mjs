import test from 'node:test'
import assert from 'node:assert/strict'
import {
  compute,
  checkShape,
  gradeOf,
  gradeLabel,
  filledCells,
  VERDICT_GOOD,
  VERDICT_BAD,
  VERDICT_UNKNOWN
} from '../src/utils/grr.js'

/**
 * GR&R 预览必须和 Java 算出来的是同一个数。
 *
 * 页面上的期望值全部取自 `qc-server` 的 `GrrStudyTest`：同一组读数，
 * 后端存进去算出 45.12%，前端边录边算却显示 45.2%，那就等于没有预览——
 * 人会以为保存一下就能改数。这里逐个数字锁死。
 */

/** 两人读数完全相同、每格极差 d、两件均值差 gap */
function twoByTwo(d, gap) {
  const x = []
  for (let i = 0; i < 2; i++) {
    const parts = []
    for (let j = 0; j < 2; j++) {
      const c = 10.0 + gap * j
      parts.push([round4(c), round4(c + d)])
    }
    x.push(parts)
  }
  return x
}

/** 3 人 × 3 件 × 2 次：每人整体偏 0.2，每格极差 0.2 */
function threeByThree() {
  const base = [10.0, 10.5, 11.0]
  const x = []
  for (let i = 0; i < 3; i++) {
    const parts = []
    for (let j = 0; j < 3; j++) {
      const c = base[j] + 0.2 * i
      parts.push([round4(c), round4(c + 0.2)])
    }
    x.push(parts)
  }
  return x
}

function round4(v) {
  return Math.round(v * 10000) / 10000
}

function near(actual, expected, tol, label) {
  assert.ok(
    Math.abs(actual - expected) <= tol,
    `${label}：期望 ${expected}±${tol}，实际 ${actual}`
  )
}

test('两人读数一致：只剩重复性，再现性被压成 0', () => {
  const r = compute(twoByTwo(0.02, 0.5), null, null)
  near(r.ev, 0.0177, 0.0002, 'ev')
  assert.equal(r.av, 0)
  near(r.grr, 0.0177, 0.0002, 'grr')
  near(r.pv, 0.3536, 0.0002, 'pv')
  near(r.pctGrr, 5.01, 0.05, 'pctGrr')
  near(r.pctPv, 99.87, 0.05, 'pctPv')
  assert.equal(r.ndc, 28)
  assert.equal(r.verdict, VERDICT_GOOD)
  assert.equal(r.avClamped, true)
})

test('重复性与再现性各占一半：45.12% 判不可接受（与后端同值）', () => {
  const r = compute(threeByThree(), null, null)
  near(r.ev, 0.1772, 0.0005, 'ev')
  near(r.av, 0.1963, 0.0005, 'av')
  near(r.grr, 0.2645, 0.0005, 'grr')
  near(r.pv, 0.5231, 0.0005, 'pv')
  near(r.pctGrr, 45.12, 0.1, 'pctGrr')
  near(r.pctEv, 30.24, 0.1, 'pctEv')
  near(r.pctAv, 33.49, 0.1, 'pctAv')
  assert.equal(r.ndc, 2)
  assert.equal(r.verdict, VERDICT_BAD)
})

test('明细数组：每人均值/平均极差、每件均值/件内差', () => {
  const r = compute(threeByThree(), null, null)
  assert.deepEqual(r.appraiserMean, [10.6, 10.8, 11.0])
  assert.deepEqual(r.appraiserRange, [0.2, 0.2, 0.2])
  assert.deepEqual(r.partMean, [10.3, 10.8, 11.3])
  assert.deepEqual(r.partRange, [0.4, 0.4, 0.4])
})

test('变差分解：%GRR² + %PV² 等于 100²', () => {
  for (const data of [twoByTwo(0.02, 0.5), threeByThree()]) {
    const r = compute(data, null, null)
    near(r.pctGrr * r.pctGrr + r.pctPv * r.pctPv, 10000, 5, '分解')
  }
})

test('阈值 10 与 30 都取闭区间', () => {
  assert.equal(gradeOf(9.99), VERDICT_GOOD)
  assert.equal(gradeOf(10.0), VERDICT_GOOD)
  assert.equal(gradeOf(10.01), 'MARGINAL')
  assert.equal(gradeOf(30.0), 'MARGINAL')
  assert.equal(gradeOf(30.01), VERDICT_BAD)
})

test('三档短标签', () => {
  assert.equal(gradeLabel(VERDICT_GOOD), '可接受')
  assert.equal(gradeLabel('MARGINAL'), '有条件接受')
  assert.equal(gradeLabel(VERDICT_BAD), '不可接受')
  assert.equal(gradeLabel('WHATEVER'), '算不出')
})

test('公差口径：两边都给才算，只有一边不算', () => {
  const r = compute(threeByThree(), 9.0, 13.6)
  near(r.pctGrrTol, 5.75, 0.1, 'pctGrrTol')
  assert.equal(compute(threeByThree(), 9.0, null).pctGrrTol, null)
  assert.equal(compute(threeByThree(), null, 13.6).pctGrrTol, null)
  // 上下限填反了：不算，也不报错（报错归后端那句人话）
  assert.equal(compute(threeByThree(), 13.6, 9.0).pctGrrTol, null)
})

test('所有读数一字不差：判 UNKNOWN，不判合格', () => {
  const x = []
  for (let i = 0; i < 3; i++) {
    x.push([
      [10, 10],
      [10, 10],
      [10, 10]
    ])
  }
  const r = compute(x, null, null)
  assert.equal(r.tv, 0)
  assert.equal(r.verdict, VERDICT_UNKNOWN)
})

test('没填全就返回 null：预览区不该显示半截结论', () => {
  assert.equal(compute(null, null, null), null)
  assert.equal(compute([], null, null), null)
  const half = twoByTwo(0.02, 0.5)
  half[1][0][1] = null
  assert.equal(compute(half, null, null), null)
  const blank = twoByTwo(0.02, 0.5)
  blank[0][0][0] = ''
  assert.equal(compute(blank, null, null), null)
})

test('已填格数：90 个格子里还剩几个没填', () => {
  const readings = [
    [
      [1, 2, 3],
      [4, 5, 6]
    ],
    [
      [1, 2, null],
      [null, null, null]
    ]
  ]
  assert.deepEqual(filledCells(readings), { filled: 8, total: 12 })
  assert.deepEqual(filledCells(null), { filled: 0, total: 0 })
})

test('结构校验与 Java 同范围', () => {
  assert.equal(checkShape(2, 10, 3), null)
  assert.match(checkShape(1, 10, 3), /评价人/)
  assert.match(checkShape(6, 10, 3), /评价人/)
  assert.match(checkShape(3, 1, 3), /零件/)
  assert.match(checkShape(3, 11, 3), /零件/)
  assert.match(checkShape(3, 10, 1), /重复测/)
  assert.match(checkShape(3, 10, 4), /重复测/)
})
