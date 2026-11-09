import test from 'node:test'
import assert from 'node:assert/strict'
import {
  DRAFT_PREFIX,
  DRAFT_TTL_MS,
  KEEP_DRAFTS,
  diffLines,
  draftKey,
  normalizeLines,
  pruneIndex,
  shouldRestore
} from '../../qc-mobile/common/draft.js'

const ADMIN = { id: 1, username: 'admin', realName: '系统管理员' }
const ZHANG = { id: 2, username: 'zhangsan', realName: '张三' }
const NO_REALNAME = { id: 7, username: 'lisi', realName: '' }
const NO_ID = { username: 'wangwu', realName: '王五' }
const NO_ID_NO_NAME = { username: 'zhaoliu' }

const line = (lineId, values, over = {}) => ({
  lineId,
  values,
  textValue: null,
  gaugeId: null,
  ...over
})

const NOW = 1762700000000

/**
 * 抄本 key 必须同时带人和单。
 *
 * 只带单号的话，车间那台公用平板上甲填了一半的单，乙登录后会被恢复出来，
 * 乙一提交，这条质量记录在系统里就变成「乙录的」——不是显示问题，是记录伪造。
 */
test('抄本按「人 + 单」隔离，缺一项就不生成 key', () => {
  const a = draftKey(ZHANG, 101)
  const b = draftKey(ADMIN, 101)
  const c = draftKey(ZHANG, 102)

  assert.equal(a, DRAFT_PREFIX + 'u2_101')
  assert.notEqual(a, b, '换个人不能拿到前一个人的抄本')
  assert.notEqual(a, c, '换张单不能拿到别的单的抄本')

  assert.equal(draftKey(null, 101), '')
  assert.equal(draftKey(ZHANG, null), '')
  assert.equal(draftKey(ZHANG, ''), '')
  assert.equal(draftKey({}, ''), '')
})

test('没有 id 时退回展示名，没填姓名就用账号', () => {
  assert.equal(draftKey(NO_ID, 9), DRAFT_PREFIX + 'n王五_9')
  assert.equal(draftKey(NO_ID_NO_NAME, 9), DRAFT_PREFIX + 'nzhaoliu_9')
  // 有 id 就只认 id：改了姓名也不会认不出自己的抄本
  assert.equal(draftKey(NO_REALNAME, 9), DRAFT_PREFIX + 'u7_9')
})

/**
 * `''` / null / 数字，在比较前必须先归一。
 * 不归一的话「没填」和「填了空」会被当成两种状态，于是每打开一次页面
 * 都弹一次「要恢复吗」——问到人把提示关掉，真丢数据那次也不看了。
 */
test('填写归一：空值与字符串统一，数字转字符串', () => {
  const map = normalizeLines([
    line(1, [null, undefined, 3.5]),
    line(2, [], { textValue: null }),
    { lineId: 3, values: 'not-array' },
    { values: ['1'] },
    null
  ])

  assert.deepEqual(map['1'], { values: ['', '', '3.5'], text: '', gauge: '' })
  assert.deepEqual(map['2'], { values: [], text: '', gauge: '' })
  assert.deepEqual(map['3'], { values: [], text: '', gauge: '' })
  // 没 lineId 的行直接丢弃，不会在比较里制造幻影差异
  assert.equal(Object.keys(map).length, 3)
  assert.deepEqual(normalizeLines(undefined), {})
})

test('两组填写相同就没有差异', () => {
  const a = [line(1, ['10.00']), line(2, [], { textValue: '外观正常' }), line(3, ['true'], { gaugeId: 5 })]
  const b = [line(3, ['true'], { gaugeId: 5 }), line(1, ['10.00']), line(2, [], { textValue: '外观正常' })]
  assert.deepEqual(diffLines(a, b), [], '顺序不同不算改动')
})

test('改值、改文本、换量具、从未填到有值都算差异', () => {
  const base = [line(1, ['10.00']), line(2, ['true']), line(3, [], { textValue: '正常' }), line(4, [])]

  assert.deepEqual(diffLines([line(1, ['10.01']), ...base.slice(1)], base), [1])
  assert.deepEqual(diffLines([base[0], line(2, ['false']), base[2], base[3]], base), [2])
  assert.deepEqual(diffLines([base[0], base[1], line(3, [], { textValue: '有划痕' }), base[3]], base), [3])
  assert.deepEqual(diffLines([base[0], base[1], base[2], line(4, ['7'], { gaugeId: 2 })], base), [4])
})

test('服务端多了一行（标准补了测点）也算差异，不能悄悄丢弃', () => {
  const local = [line(1, ['1'])]
  const server = [line(1, ['1']), line(2, [''])]
  assert.deepEqual(diffLines(local, server), [2])
})

test('有内容、没过期、与服务端不同 → 提示恢复', () => {
  const draft = { lines: [line(1, ['10.00'])], savedAt: NOW - 60 * 1000 }
  assert.equal(shouldRestore({ draft, currentLines: [line(1, [''])], now: NOW }), true)
})

test('与服务端内容一致就不弹——人已经暂存过了', () => {
  const draft = { lines: [line(1, ['10.00'])], savedAt: NOW - 60 * 1000 }
  assert.equal(shouldRestore({ draft, currentLines: [line(1, ['10.00'])], now: NOW }), false)
  // 数字与字符串在归一这一步就该对齐，别留到比较里
  assert.equal(shouldRestore({ draft, currentLines: [line(1, [10.0])], now: NOW }), true,
    '10 与 "10.00" 是两回事，不能私自判等')
})

test('空抄本、过期抄本、时钟大幅回拨都不提示', () => {
  const lines = [line(1, ['10.00'])]
  assert.equal(shouldRestore({ draft: null, currentLines: [], now: NOW }), false)
  assert.equal(shouldRestore({ draft: { lines: [], savedAt: NOW }, currentLines: [], now: NOW }), false)
  assert.equal(shouldRestore({
    draft: { lines, savedAt: NOW - DRAFT_TTL_MS - 1000 },
    currentLines: [line(1, ['9'])],
    now: NOW
  }), false, '过期抄本对应的可能是旧版标准，恢复反而害人')
  assert.equal(shouldRestore({
    draft: { lines, savedAt: NOW + 5 * 3600 * 1000 },
    currentLines: [line(1, ['9'])],
    now: NOW
  }), false, '系统时间被往回调，不认这份抄本')
})

test('自定义 TTL 生效', () => {
  const draft = { lines: [line(1, ['1'])], savedAt: NOW - 10 * 60 * 1000 }
  assert.equal(shouldRestore({ draft, currentLines: [], now: NOW, ttlMs: 5 * 60 * 1000 }), false)
  assert.equal(shouldRestore({ draft, currentLines: [], now: NOW, ttlMs: 20 * 60 * 1000 }), true)
})

/**
 * localStorage 不会自己回收。一年不清理的话，
 * 手机上会躺着几百张单的残片，还都是明文测量值。
 */
test('清理旧抄本：过期的全删，剩下的留最近几张', () => {
  const index = [
    { key: 'qc_draft_u2_1', savedAt: NOW - 1000 },
    { key: 'qc_draft_u2_2', savedAt: NOW - 2000 },
    { key: 'qc_draft_u2_3', savedAt: NOW - 3000 },
    { key: 'qc_draft_u2_4', savedAt: NOW - DRAFT_TTL_MS - 1 }
  ]
  const dead = pruneIndex(index, 'qc_draft_u2_9', NOW, DRAFT_TTL_MS, 3)

  // 保留名额 3 = 刚写的 keepKey + 最近的 2 张
  assert.ok(dead.includes('qc_draft_u2_4'), '过期的一定删')
  assert.ok(dead.includes('qc_draft_u2_3'), '超出名额的删最旧')
  assert.ok(!dead.includes('qc_draft_u2_1'))
  assert.ok(!dead.includes('qc_draft_u2_2'))
  assert.ok(!dead.includes('qc_draft_u2_9'), '刚写的不能删')
})

test('清理时刚写的 key 不会出现在待删列表里', () => {
  const index = [{ key: 'qc_draft_u2_1', savedAt: NOW }, { key: 'qc_draft_u2_2', savedAt: NOW }]
  const dead = pruneIndex(index, 'qc_draft_u2_1', NOW, DRAFT_TTL_MS, 1)
  assert.deepEqual(dead, ['qc_draft_u2_2'])
  assert.ok(KEEP_DRAFTS >= 1)
})

test('索引是空的或脏的也不会炸', () => {
  assert.deepEqual(pruneIndex(null, 'k', NOW), [])
  assert.deepEqual(pruneIndex([null, {}, { key: '', savedAt: NOW }], 'k', NOW), [])
  assert.deepEqual(pruneIndex([{ key: 'a', savedAt: 'x' }], 'k', NOW), ['a'], '时间解析不出来按过期处理')
})
