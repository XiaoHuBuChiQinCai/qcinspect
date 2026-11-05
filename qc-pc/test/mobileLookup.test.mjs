import test from 'node:test'
import assert from 'node:assert/strict'
import { normalizeCode } from '../../qc-mobile/common/code.js'

/**
 * 归一化这件事看着无所谓，实际上是三个页面的入口：
 * 追溯、出货登记、客诉登记都靠它把「扫码结果 / 手输 / 粘贴」变成同一个可比对的字符串。
 * 少去掉一个空格，`===` 就匹配不上，表现成「这批次不存在」——
 * 而批次明明在系统里，用户只会觉得系统坏了。
 */
test('批次号归一化：去空格、转大写', () => {
  assert.equal(normalizeCode('  b20261019001 '), 'B20261019001')
  assert.equal(normalizeCode('B2026 1019 001'), 'B20261019001')
  assert.equal(normalizeCode('\tB001\n'), 'B001')
})

test('空值一律给空串，别变成 "NULL" 或 "UNDEFINED"', () => {
  // String(null) 是 "null"，直接拿来查会把 null 当单号搜出去
  assert.equal(normalizeCode(null), '')
  assert.equal(normalizeCode(undefined), '')
  assert.equal(normalizeCode('   '), '')
})

test('非字符串输入不炸', () => {
  assert.equal(normalizeCode(12345), '12345')
})
