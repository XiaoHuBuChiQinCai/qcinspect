import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

import {
  judgeGauge,
  daysBetween,
  gaugeAdvice,
  gaugeStatusText,
  calResultText,
  gaugeLevelText,
  SOON_DAYS
} from '../../qc-mobile/common/gauge.js'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const TODAY = '2026-11-05'

const gauge = (over = {}) => ({
  id: 1,
  gaugeCode: 'GC-001',
  gaugeName: '数显卡尺',
  status: 'ACTIVE',
  nextCalibrateDate: '2027-01-01',
  ...over
})

test('有效期内的量具可用', () => {
  const j = judgeGauge(gauge(), TODAY)
  assert.equal(j.level, 'OK')
  assert.equal(j.usable, true)
})

test('过期一天就不许用，且报出超了几天', () => {
  const j = judgeGauge(gauge({ nextCalibrateDate: '2026-10-26' }), TODAY)
  assert.equal(j.level, 'EXPIRED')
  assert.equal(j.usable, false)
  assert.equal(j.days, 10)
  assert.match(j.text, /已超期 10 天/)
})

test('到期当天仍然可用——后端用的是 isBefore，不是 isEqual', () => {
  // 这条边界是前后端最容易走岔的一处：前端要是写成 <=，今天到期的卡尺
  // 会被挡在门外，而提交时后端却放行，人以为是前端坏了
  const j = judgeGauge(gauge({ nextCalibrateDate: TODAY }), TODAY)
  assert.equal(j.level, 'SOON')
  assert.equal(j.usable, true)
  assert.equal(j.days, 0)
  assert.equal(j.text, '今天到期')
})

test(`${SOON_DAYS} 天内到期要提醒，但还能用`, () => {
  assert.equal(judgeGauge(gauge({ nextCalibrateDate: '2026-12-05' }), TODAY).level, 'SOON')
  assert.equal(judgeGauge(gauge({ nextCalibrateDate: '2026-12-06' }), TODAY).level, 'OK')
})

test('报废优先于超期：不能让人以为送检就能解决', () => {
  // 一把报废的卡尺如果先按「超期」报出来，人的第一反应是送去校准，
  // 而报废意味着校准也没用。判定顺序必须和后端 assertUsable 一致
  const j = judgeGauge(gauge({ status: 'SCRAPPED', nextCalibrateDate: '2020-01-01' }), TODAY)
  assert.equal(j.level, 'SCRAPPED')
  assert.equal(j.usable, false)
})

test('停用与未校准都不可用', () => {
  assert.equal(judgeGauge(gauge({ status: 'SUSPENDED' }), TODAY).level, 'SUSPENDED')
  assert.equal(judgeGauge(gauge({ nextCalibrateDate: null }), TODAY).level, 'NOCAL')
  assert.equal(judgeGauge(null, TODAY).usable, false)
})

test('停用要说清是校准还是点检不合格——两句话的下一步不一样', () => {
  // 说「上次校准不合格」而实际是点检不合格，人会把量具送去计量院白花一笔钱，
  // 回来还是不能用。原因写在哪，就照哪念
  assert.equal(judgeGauge(gauge({ status: 'SUSPENDED' }), TODAY).text, '已停用：上次校准不合格')
  const j = judgeGauge(gauge({ status: 'SUSPENDED', suspendReason: '点检不合格 2026-11-06' }), TODAY)
  assert.equal(j.text, '已停用：点检不合格 2026-11-06')
  assert.equal(j.usable, false)
})

test('每档都给一句「接下来该干什么」', () => {
  for (const lv of ['EXPIRED', 'SUSPENDED', 'SCRAPPED', 'NOCAL']) {
    assert.ok(gaugeAdvice(lv).length > 4, lv + ' 缺少处置建议')
  }
})

test('daysBetween 只算日历日，跨月跨年都不差一天', () => {
  assert.equal(daysBetween('2026-11-05', '2026-11-06'), 1)
  assert.equal(daysBetween('2026-11-05', '2026-12-05'), 30)
  assert.equal(daysBetween('2026-12-31', '2027-01-01'), 1)
  assert.equal(daysBetween('2026-11-05', '2026-11-05'), 0)
})

test('日期带时间也能判（别指望 LocalDate 序列化一定不带 T）', () => {
  const j = judgeGauge(gauge({ nextCalibrateDate: '2026-10-26T00:00:00' }), TODAY)
  assert.equal(j.level, 'EXPIRED')
  assert.equal(j.days, 10)
})

test('认不出的值不显示空白', () => {
  assert.equal(gaugeStatusText('NEW'), 'NEW')
  assert.equal(gaugeStatusText(null), '')
  assert.equal(calResultText('LIMITED'), '限用')
  assert.equal(gaugeLevelText('XYZ'), 'XYZ')
})

test('判定顺序与后端 GaugeService 保持一致', () => {
  // 前端算的是「选的时候能不能选」，后端算的是「提交时放不放行」。
  // 两处结论不一致，就会出现「界面说能用、提交被驳回」这种最难解释的情况。
  const java = fs.readFileSync(
    path.resolve(HERE, '../../qc-server/src/main/java/com/qc/inspect/service/GaugeService.java'),
    'utf8'
  )
  const body = java.slice(java.indexOf('public QcGauge assertUsable'), java.indexOf('/** 报废'))
  const order = ['SCRAPPED', 'SUSPENDED', 'getNextCalibrateDate', 'isBefore']
  let cursor = -1
  for (const key of order) {
    const at = body.indexOf(key)
    assert.ok(at > cursor, `assertUsable 里「${key}」的位置变了，前端 gauge.js 要跟着改`)
    cursor = at
  }
  // 边界是闭区间：写成 isEqual/isAfter 会让「今天到期」的两端结论对不上
  assert.ok(body.includes('next.isBefore(LocalDate.now())'), '超期判定必须用 isBefore（今天到期仍可用）')
})
