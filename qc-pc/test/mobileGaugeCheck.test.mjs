import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

import {
  checkVerdict,
  checkResultText,
  parseTolerance,
  formatAmount,
  toUnits,
  checkedThisMonth,
  daysSinceCheck,
  sortPending,
  pendingHint
} from '../../qc-mobile/common/gaugeCheck.js'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const TODAY = '2026-11-06'

test('偏差正好等于允差算合格——边界是闭区间', () => {
  // 与判定引擎同一口径。写成 < 会让「刚好卡在允差上」的两端结论对不上：
  // 界面说超差，后端存进去的 result 是 PASS
  const eq = checkVerdict(10, 10.01, 0.01)
  assert.equal(eq.pass, true)
  assert.equal(eq.deviation, 0.01)

  const over = checkVerdict(10, 10.0101, 0.01)
  assert.equal(over.pass, false)
})

test('浮点误差不能把合格判成超差', () => {
  // 10.01 - 10 在 double 里是 0.010000000000000675，直接跟 0.01 比就是「超差」。
  // 后端用 BigDecimal 是合格的，所以前端必须先在万分之一上取整再比
  assert.equal(checkVerdict(10, 10.01, 0.01).pass, true)
  assert.equal(checkVerdict(0.3, 0.1 + 0.2, 0).pass, true)
  assert.equal(checkVerdict(1.005, 1.0, 0.005).pass, true)
})

test('负偏差同样按绝对值判', () => {
  assert.equal(checkVerdict(10, 9.98, 0.02).pass, true)
  assert.equal(checkVerdict(10, 9.98, 0.02).deviation, -0.02)
  assert.equal(checkVerdict(10, 9.9799, 0.02).pass, false)
})

test('填不全时 pass 是 null，不是 false', () => {
  // 这不是小事：把「还没填」渲染成「不合格」，人会以为量具坏了
  assert.equal(checkVerdict('', '', '').pass, null)
  assert.equal(checkVerdict(10, '', 0.01).pass, null)
  assert.equal(checkVerdict(10, 10, '').pass, null)
  assert.equal(checkVerdict('abc', 10, 0.01).pass, null)
})

test('允差填成负数要拦住并说清怎么填', () => {
  const v = checkVerdict(10, 10.02, -0.01)
  assert.equal(v.pass, null)
  assert.match(v.text, /绝对值/)
})

test('结论文字带上偏差和允差，别只说一句不合格', () => {
  assert.match(checkVerdict(10, 10.01, 0.02).text, /合格.*0\.01.*0\.02/)
  assert.match(checkVerdict(10, 10.05, 0.02).text, /超差.*0\.05.*0\.02/)
})

test('从「精度」预填允差：认得出就填，认不出宁可空着', () => {
  assert.equal(parseTolerance('0.01mm'), 0.01)
  assert.equal(parseTolerance('±0.02'), 0.02)
  assert.equal(parseTolerance('0.001mm'), 0.001)
  assert.equal(parseTolerance('HRC 1'), 1)
  assert.equal(parseTolerance('目视'), null)
  assert.equal(parseTolerance(''), null)
  assert.equal(parseTolerance(null), null)
})

test('数值展示去掉尾零，0.0100 写成 0.01', () => {
  assert.equal(formatAmount(0.01), '0.01')
  assert.equal(formatAmount(0), '0')
  assert.equal(formatAmount(-0.0), '0')
  assert.equal(formatAmount(1.5), '1.5')
  assert.equal(formatAmount(Number.NaN), '')
})

test('toUnits 与 BigDecimal.setScale(4, HALF_UP) 一致（负数也远离零）', () => {
  assert.equal(toUnits(0.00005), 1)
  assert.equal(toUnits(-0.00005), -1)
  assert.equal(toUnits('10.01'), 100100)
})

test('本月是否点检过：同月就算，不看具体几号', () => {
  assert.equal(checkedThisMonth('2026-11-01', TODAY), true)
  assert.equal(checkedThisMonth('2026-11-06T00:00:00', TODAY), true)
  assert.equal(checkedThisMonth('2026-10-31', TODAY), false)
  assert.equal(checkedThisMonth(null, TODAY), false)
  assert.equal(checkedThisMonth('2026-11-01', null), false)
})

test('距上次点检的天数：没点检过是 Infinity，排最前', () => {
  assert.equal(daysSinceCheck(null, TODAY), Infinity)
  assert.equal(daysSinceCheck('2026-11-05', TODAY), 1)
  assert.equal(daysSinceCheck('2026-10-06', TODAY), 31)
})

test('待点检排序：从没点检的最前，其次隔得最久，且不改原数组', () => {
  const items = [
    { gauge: { gaugeCode: 'G-002' }, lastCheckDate: '2026-11-01' },
    { gauge: { gaugeCode: 'G-003' }, lastCheckDate: null },
    { gauge: { gaugeCode: 'G-001' }, lastCheckDate: '2026-08-01' }
  ]
  const sorted = sortPending(items, TODAY)
  assert.deepEqual(sorted.map(i => i.gauge.gaugeCode), ['G-003', 'G-001', 'G-002'])
  assert.equal(items[0].gauge.gaugeCode, 'G-002', 'sortPending 不该改调用方的数组')
})

test('待点检那一行说清多久没点检，别让人去数日期', () => {
  assert.equal(pendingHint({ lastCheckDate: null }, TODAY), '从没点检过')
  assert.equal(pendingHint({ lastCheckDate: '2026-11-06' }, TODAY), '今天点检过')
  assert.equal(pendingHint({ lastCheckDate: '2026-09-06', lastResult: 'PASS' }, TODAY), '61 天前点检')
  assert.equal(pendingHint({ lastCheckDate: '2026-09-06', lastResult: 'FAIL' }, TODAY), '61 天前点检（不合格）')
})

test('点检结论文案与表注释一致', () => {
  assert.equal(checkResultText('PASS'), '合格')
  assert.equal(checkResultText('FAIL'), '不合格')
  assert.equal(checkResultText(null), '')
})

test('判定口径与后端 GaugeService.submitCheck 保持一致', () => {
  // 前端实时算一遍是为了让人在填的时候就看见「这把要停用了」，
  // 但真正落库的结论在后端。两处算法一岔，就成了「界面合格、库里不合格」
  const java = fs.readFileSync(
    path.resolve(HERE, '../../qc-server/src/main/java/com/qc/inspect/service/GaugeService.java'),
    'utf8'
  )
  const body = java.slice(java.indexOf('public CheckResult submitCheck'), java.indexOf('/** 点检结果'))
  assert.ok(body.includes('dev.abs().compareTo(tol) <= 0'),
    '后端必须是闭区间比较：偏差等于允差算合格，前端 checkVerdict 要跟着改')
  assert.ok(body.includes('setScale(AMOUNT_SCALE, RoundingMode.HALF_UP)'),
    '偏差与允差要先按 4 位小数取整再比，否则浮点误差会让两边结论相反')
})
