import test from 'node:test'
import assert from 'node:assert/strict'
import { parseGrid, isNumeric, firstNonNumeric, blit, gridSize } from '../src/utils/pasteGrid.js'

test('解析 Excel 整块粘贴：制表符分列、\\r\\n 分行', () => {
  assert.deepEqual(parseGrid('1.1\t2.1\r\n1.2\t2.2\r\n'), [
    ['1.1', '2.1'],
    ['1.2', '2.2']
  ])
})

test('解析单列粘贴：每行一个值，末尾空行不能多铺一行', () => {
  assert.deepEqual(parseGrid('12.01\n12.02\n12.03\n\n'), [['12.01'], ['12.02'], ['12.03']])
})

test('解析单个值：只写一格', () => {
  assert.deepEqual(parseGrid('12.5'), [['12.5']])
})

test('空粘贴返回空数组，而不是一行空值', () => {
  assert.deepEqual(parseGrid(''), [])
  assert.deepEqual(parseGrid('   \n\n'), [])
  assert.deepEqual(parseGrid(null), [])
})

test('单元格两侧空格要裁掉，否则 Number() 之外的地方会带进脏字符', () => {
  assert.deepEqual(parseGrid(' 1.1 \t 2.2 '), [['1.1', '2.2']])
})

test('负数、小数、科学计数法都算数字', () => {
  assert.equal(isNumeric('-0.05'), true)
  assert.equal(isNumeric('1.5e-3'), true)
  assert.equal(isNumeric('12'), true)
})

test('空值不算错，但填了非数字就是错', () => {
  assert.equal(isNumeric(''), false)
  assert.equal(isNumeric(null), false)
  assert.equal(isNumeric('abc'), false)
  assert.equal(isNumeric('12,5'), false) // 逗号小数不认，宁可报错也别猜
  assert.equal(isNumeric('NaN'), false)
  assert.equal(isNumeric('1 2'), false)
})

test('找出第一个非法格并给出行列号', () => {
  assert.equal(firstNonNumeric([['1', '2'], ['3', '4']]), null)
  assert.deepEqual(firstNonNumeric([['1', '2'], ['3', 'x']]), { row: 1, col: 1, raw: 'x' })
  // 空格子跳过，不算错
  assert.equal(firstNonNumeric([['1', ''], ['', '4']]), null)
})

test('铺入矩阵：从锚点向右向下展开', () => {
  const target = [
    ['', ''],
    ['', '']
  ]
  const n = blit(target, [['a', 'b'], ['c', 'd']], 0, 0)
  assert.equal(n, 4)
  assert.deepEqual(target, [['a', 'b'], ['c', 'd']])
})

test('铺入矩阵：从中间锚点开始', () => {
  const target = [
    ['', '', ''],
    ['', '', '']
  ]
  blit(target, [['X']], 1, 2)
  assert.deepEqual(target, [
    ['', '', ''],
    ['', '', 'X']
  ])
})

test('铺入矩阵：超出部分丢弃，不越界报错', () => {
  const target = [
    ['', ''],
    ['', '']
  ]
  const n = blit(target, [['a', 'b', 'c'], ['d', 'e', 'f'], ['g', 'h', 'i']], 1, 1)
  // 只有 (1,1) 落在范围内
  assert.equal(n, 1)
  assert.deepEqual(target, [
    ['', ''],
    ['', 'a']
  ])
  assert.equal(gridSize([['a', 'b', 'c'], ['d', 'e', 'f'], ['g', 'h', 'i']]), 9)
})

test('铺入矩阵：粘贴区域比矩阵大时，实际写入数少于总格数', () => {
  const target = [
    ['', ''],
    ['', '']
  ]
  const grid = parseGrid('1\t2\t3\r\n4\t5\t6\r\n7\t8\t9')
  const n = blit(target, grid, 0, 0)
  assert.ok(n < gridSize(grid), '应当写入 4 格、丢弃 5 格')
  assert.equal(n, 4)
})
