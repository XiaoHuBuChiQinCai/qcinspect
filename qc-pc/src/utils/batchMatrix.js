/**
 * 批量录入的矩阵模型。
 *
 * 检验单的数据是按「件 → 分组 → 明细行」三层存的，每行一个检验项，
 * 多测点项把多个读数塞进同一个 `_inputs` 数组。要变成「件 × 检验项」的表格，
 * 得把多测点项拆成多列；写回去还要按列找到对应那一行的第几个测点。
 *
 * 这层映射错了不会报错，只会把 3 号件的值写到 4 号件上，判出来的结果凑巧还能看——
 * 所以抽成纯函数，用测试盯住。
 */

import { isNumeric, isBlank } from './pasteGrid.js'

/** 明细行是否数值型（只有数值项进矩阵） */
const isNumberLine = l => l && l.valueType === 'NUMBER'

function flatLines(piece) {
  if (!piece || !piece.groups) return []
  return Object.values(piece.groups).flat()
}

/**
 * 矩阵列：数值型检验项，多测点项按测点展开成多列。
 *
 * 列里带 min/max，是为了贴完当场标红超规，不用等提交判定才发现整批不在规格内。
 */
export function numericColumns(pieces) {
  const first = Array.isArray(pieces) ? pieces[0] : null
  if (!first) return []
  const cols = []
  flatLines(first)
    .filter(isNumberLine)
    .forEach(l => {
      const n = l.valueMode === 'MULTIPLE' ? l.pointCount || 1 : 1
      for (let i = 0; i < n; i++) {
        cols.push({
          itemId: l.itemId,
          point: i,
          label: n > 1 ? `${l.itemName} #${i + 1}` : l.itemName,
          unit: l.unit,
          min: l.minValue,
          max: l.maxValue
        })
      }
    })
  return cols
}

/** 取某件某列当前的值（已在单据里的要预填进来，不能一打开就是空白） */
export function cellValue(piece, col) {
  const line = flatLines(piece).find(l => l.itemId === col.itemId)
  if (!line) return ''
  if (line.valueMode === 'MULTIPLE') {
    return line._inputs?.[col.point] ?? ''
  }
  return line._input ?? ''
}

/** 拼出初始矩阵：行=件，列=检验项 */
export function buildGrid(pieces, columns) {
  return (pieces || []).map(p => (columns || []).map(c => String(cellValue(p, c) ?? '')))
}

/**
 * 把矩阵写回单据的明细行（就地修改）。
 * 返回写了几格，方便调用方判断有没有真的写进去。
 */
export function applyGrid(pieces, columns, grid) {
  let n = 0
  ;(grid || []).forEach((row, ri) => {
    const piece = pieces[ri]
    if (!piece) return
    ;(row || []).forEach((v, ci) => {
      const col = columns[ci]
      if (!col) return
      const line = flatLines(piece).find(l => l.itemId === col.itemId)
      if (!line) return
      if (line.valueMode === 'MULTIPLE') {
        if (!Array.isArray(line._inputs)) line._inputs = []
        line._inputs[col.point] = v
      } else {
        line._input = v
      }
      n++
    })
  })
  return n
}

/** 单格是否超规或填了非数字（空格子不算） */
export function cellBad(col, v) {
  if (!col || isBlank(v)) return false
  if (!isNumeric(v)) return true
  const n = Number(v)
  if (col.min != null && n < col.min) return true
  if (col.max != null && n > col.max) return true
  return false
}

export function outOfSpecCount(columns, grid) {
  let n = 0
  ;(grid || []).forEach(row =>
    (row || []).forEach((v, ci) => {
      if (cellBad(columns[ci], v)) n++
    })
  )
  return n
}

export function filledCount(grid) {
  let n = 0
  ;(grid || []).forEach(row =>
    (row || []).forEach(v => {
      if (!isBlank(v)) n++
    })
  )
  return n
}
