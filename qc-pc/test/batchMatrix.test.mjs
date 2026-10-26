import test from 'node:test'
import assert from 'node:assert/strict'
import {
  numericColumns,
  buildGrid,
  applyGrid,
  cellBad,
  outOfSpecCount,
  filledCount
} from '../src/utils/batchMatrix.js'

/** 造 3 件，每件：外径（单值 14~16）、三点壁厚（多测点）、材质（ENUM）、漏气（BOOL） */
function mkPieces() {
  const mk = pieceNo => ({
    pieceNo,
    groups: {
      尺寸: [
        {
          itemId: 1,
          itemName: '外径',
          valueType: 'NUMBER',
          valueMode: 'SINGLE',
          unit: 'mm',
          minValue: 14,
          maxValue: 16,
          _input: '',
          _inputs: ['']
        },
        {
          itemId: 2,
          itemName: '壁厚',
          valueType: 'NUMBER',
          valueMode: 'MULTIPLE',
          pointCount: 3,
          unit: 'mm',
          minValue: 2.5,
          maxValue: 3,
          _input: '',
          _inputs: ['', '', '']
        }
      ],
      外观: [
        {
          itemId: 3,
          itemName: '材质',
          valueType: 'ENUM',
          valueMode: 'SINGLE',
          _input: '',
          _inputs: ['']
        },
        {
          itemId: 4,
          itemName: '漏气',
          valueType: 'BOOL',
          valueMode: 'SINGLE',
          _input: null,
          _inputs: ['']
        }
      ]
    }
  })
  return [mk(1), mk(2), mk(3)]
}

test('列只取数值项，多测点项展开成多列', () => {
  const cols = numericColumns(mkPieces())
  assert.deepEqual(
    cols.map(c => c.label),
    ['外径', '壁厚 #1', '壁厚 #2', '壁厚 #3']
  )
  // ENUM / BOOL 不能进矩阵，否则一格里贴个数字没法判
  assert.ok(!cols.some(c => c.itemId === 3 || c.itemId === 4))
  // 列上要带规格，贴完当场标红
  assert.equal(cols[0].min, 14)
  assert.equal(cols[0].max, 16)
})

test('空单据不炸，返回空列', () => {
  assert.deepEqual(numericColumns([]), [])
  assert.deepEqual(numericColumns(null), [])
})

test('初始矩阵要预填单据里已有的值，不能一打开全空', () => {
  const pieces = mkPieces()
  pieces[0].groups.尺寸[0]._input = '15.1'
  pieces[0].groups.尺寸[1]._inputs = ['2.6', '2.7', '2.8']
  pieces[1].groups.尺寸[0]._input = '15.9'

  const cols = numericColumns(pieces)
  const grid = buildGrid(pieces, cols)

  assert.deepEqual(grid[0], ['15.1', '2.6', '2.7', '2.8'])
  assert.deepEqual(grid[1], ['15.9', '', '', ''])
  assert.deepEqual(grid[2], ['', '', '', ''])
})

test('回写必须写到对的那一件、那一个测点', () => {
  const pieces = mkPieces()
  const cols = numericColumns(pieces)
  const grid = [
    ['15.0', '2.6', '2.6', '2.6'],
    ['15.1', '2.7', '2.7', '2.7'],
    ['15.2', '2.8', '2.8', '2.8']
  ]
  const n = applyGrid(pieces, cols, grid)
  assert.equal(n, 12)

  assert.equal(pieces[0].groups.尺寸[0]._input, '15.0')
  assert.equal(pieces[1].groups.尺寸[0]._input, '15.1')
  assert.equal(pieces[2].groups.尺寸[0]._input, '15.2')

  // 多测点各归各位——错位了不会报错，只会判出一批似是而非的结果
  assert.deepEqual(pieces[0].groups.尺寸[1]._inputs, ['2.6', '2.6', '2.6'])
  assert.deepEqual(pieces[2].groups.尺寸[1]._inputs, ['2.8', '2.8', '2.8'])
})

test('件数少于矩阵行数时，多出来的行直接忽略', () => {
  const pieces = mkPieces()
  const cols = numericColumns(pieces)
  const grid = [['15', '', '', ''], ['', '', '', ''], ['', '', '', ''], ['99', '9', '9', '9']]
  const n = applyGrid(pieces, cols, grid)
  assert.equal(n, 12) // 写进 3 件，第 4 行没有对应的件
  assert.equal(pieces[2].groups.尺寸[0]._input, '')
})

test('超规、非数字都算问题格，空格子不算', () => {
  const cols = [{ min: 14, max: 16 }]
  assert.equal(cellBad(cols[0], '14'), false) // 闭区间端点合格
  assert.equal(cellBad(cols[0], '16'), false)
  assert.equal(cellBad(cols[0], '13.99'), true)
  assert.equal(cellBad(cols[0], '16.01'), true)
  assert.equal(cellBad(cols[0], 'abc'), true)
  assert.equal(cellBad(cols[0], ''), false)
  assert.equal(cellBad(cols[0], null), false)
})

test('无规格限的项不判超规，但非数字照样标', () => {
  const col = { min: null, max: null }
  assert.equal(cellBad(col, '9999'), false)
  assert.equal(cellBad(col, 'x'), true)
})

test('统计超规格数与已填格数', () => {
  const cols = [
    { min: 14, max: 16 },
    { min: 14, max: 16 }
  ]
  const grid = [
    ['15', '17'],
    ['', 'abc'],
    ['15', '']
  ]
  assert.equal(outOfSpecCount(cols, grid), 2) // 17 超上限、abc 不是数字
  assert.equal(filledCount(grid), 4)
})
