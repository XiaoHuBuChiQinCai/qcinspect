import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import { valueState, lineState, specText, STATE } from '../src/utils/specHint.js'

const num = (o = {}) => ({ valueType: 'NUMBER', valueMode: 'SINGLE', minValue: 11.95, maxValue: 12.05, ...o })

test('边界值算合格（闭区间，和后端口径一致）', () => {
  assert.equal(valueState(11.95, 12.05, '11.95'), STATE.OK)
  assert.equal(valueState(11.95, 12.05, '12.05'), STATE.OK)
  assert.equal(valueState(11.95, 12.05, 12), STATE.OK)
})

test('超出上下限判 oos', () => {
  assert.equal(valueState(11.95, 12.05, '11.94'), STATE.OOS)
  assert.equal(valueState(11.95, 12.05, '12.06'), STATE.OOS)
  // 打错字：12.5 而不是 12.05 —— 这正是要当场看见的那一类
  assert.equal(valueState(11.95, 12.05, '12.5'), STATE.OOS)
})

test('没填算 blank，不算错', () => {
  assert.equal(valueState(11.95, 12.05, ''), STATE.BLANK)
  assert.equal(valueState(11.95, 12.05, '   '), STATE.BLANK)
  assert.equal(valueState(11.95, 12.05, null), STATE.BLANK)
  assert.equal(valueState(11.95, 12.05, undefined), STATE.BLANK)
})

test('填了不是数字要单独区分，不能当通过', () => {
  assert.equal(valueState(11.95, 12.05, 'abc'), STATE.NAN)
  assert.equal(valueState(11.95, 12.05, '12,05'), STATE.NAN)
})

test('单边规格只看那一侧', () => {
  assert.equal(valueState(10, null, '9'), STATE.OOS)
  assert.equal(valueState(10, null, '99'), STATE.OK)
  assert.equal(valueState(null, 10, '11'), STATE.OOS)
  assert.equal(valueState(null, 10, '-99'), STATE.OK)
})

test('没有限值时不说人对不对（不能凭空判红）', () => {
  assert.equal(valueState(null, null, '123'), STATE.NOLIMIT)
})

test('上下限是字符串也认（后端 BigDecimal 可能序列化成字符串）', () => {
  assert.equal(valueState('11.95', '12.05', '12.00'), STATE.OK)
  assert.equal(valueState('11.95', '12.05', '12.06'), STATE.OOS)
})

test('lineState：多测点任一超规 → 整行超规', () => {
  const line = num({ valueMode: 'MULTIPLE', pointCount: 3, _inputs: ['12.00', '12.10', '12.01'] })
  assert.equal(lineState(line), STATE.OOS)
})

test('lineState：全在范围内 → OK', () => {
  const line = num({ valueMode: 'MULTIPLE', pointCount: 3, _inputs: ['12.00', '11.96', '12.05'] })
  assert.equal(lineState(line), STATE.OK)
})

test('lineState：一个都没填 → BLANK，不该标红', () => {
  const line = num({ valueMode: 'MULTIPLE', pointCount: 3, _inputs: ['', '', ''] })
  assert.equal(lineState(line), STATE.BLANK)
})

test('lineState：只填了一部分且都合格 → OK，不因为没填完就报错', () => {
  const line = num({ valueMode: 'MULTIPLE', pointCount: 3, _inputs: ['12.00', '', ''] })
  assert.equal(lineState(line), STATE.OK)
})

test('lineState：超规优先于非数字 —— 先让人看见最严重的那件事', () => {
  const line = num({ valueMode: 'MULTIPLE', pointCount: 2, _inputs: ['abc', '12.10'] })
  assert.equal(lineState(line), STATE.OOS)
})

test('lineState：单值项读 _input', () => {
  assert.equal(lineState(num({ _input: '12.10' })), STATE.OOS)
  assert.equal(lineState(num({ _input: '12.00' })), STATE.OK)
})

test('lineState：非数值项不参与，返回 null（组件据此不显示标记）', () => {
  assert.equal(lineState({ valueType: 'ENUM', _input: 'LIGHT' }), null)
  assert.equal(lineState({ valueType: 'BOOL', _input: true }), null)
  assert.equal(lineState(null), null)
})

test('specText 单边与双边', () => {
  assert.equal(specText(11.95, 12.05, 'mm'), '11.95 ~ 12.05 mm')
  assert.equal(specText(10, null, 'mm'), '≥ 10 mm')
  assert.equal(specText(null, 10, ''), '≤ 10')
  assert.equal(specText(null, null, ''), '未设限值')
})

test('移动端那份副本必须与这里逐字一致', () => {
  // 移动端是 uni-app 独立工程，跨目录 import 不稳，只能各放一份。
  // 但「PC 上标红、手机上不标红」会让人以为手机没保存成功，
  // 所以这条比对就是防漂移的闸门：改了这边忘了改那边，这里直接红。
  const here = fs.readFileSync(new URL('../src/utils/specHint.js', import.meta.url), 'utf8')
  const mobile = fs.readFileSync(new URL('../../qc-mobile/common/specHint.js', import.meta.url), 'utf8')
  assert.equal(mobile, here, 'qc-mobile/common/specHint.js 与 qc-pc/src/utils/specHint.js 不一致')
})
