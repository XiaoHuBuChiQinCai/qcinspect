/**
 * 表格粘贴解析。
 *
 * 从 Excel / 量具软件复制出来的是「制表符分列、换行分行」的纯文本，
 * Windows 上的换行是 \r\n，末尾通常还挂着空行。
 *
 * 这些函数不碰 DOM、不依赖 Vue，纯粹为了能单独测 ——
 * 粘贴解析错一格，录入的人不会发现，等提交时才炸，而且查不出来是哪一步错的。
 */

/** 解析成二维字符串数组；空文本返回空数组 */
export function parseGrid(text) {
  if (!text) return []
  const rows = String(text)
    .replace(/\r\n?/g, '\n') // Windows 换行
    .replace(/\n+$/, '') // 末尾空行：不裁掉会多铺一行空值，把件号顶错
    .split('\n')
  // 纯空白粘贴等于什么都没贴，别当成「一行一个空值」
  if (rows.length === 1 && rows[0].trim() === '') return []
  return rows.map(r => r.split('\t').map(c => c.trim()))
}

export function isBlank(v) {
  return v === '' || v == null
}

/** 空值不算错（允许只填一部分），但填了就必须是能解析的数字 */
export function isNumeric(v) {
  return !isBlank(v) && Number.isFinite(Number(v))
}

/**
 * 第一个「填了但不是数字」的格；全合法返回 null。
 *
 * 空格子跳过：允许只贴一部分（多测点还没测完、后半批还没量），
 * 把空格子也算错的话，半张表都贴不进来。
 */
export function firstNonNumeric(grid) {
  for (let i = 0; i < grid.length; i++) {
    const row = grid[i] || []
    for (let j = 0; j < row.length; j++) {
      if (!isBlank(row[j]) && !isNumeric(row[j])) return { row: i, col: j, raw: row[j] }
    }
  }
  return null
}

/**
 * 把一片 grid 铺到 target 的 (r0, c0)，越界部分丢弃。
 * 返回实际写入的格数，调用方用它判断要不要提示「超出范围已忽略」。
 */
export function blit(target, grid, r0, c0) {
  if (!Array.isArray(target) || !Array.isArray(grid)) return 0
  let n = 0
  grid.forEach((row, i) => {
    ;(row || []).forEach((v, j) => {
      const ri = r0 + i
      const ci = c0 + j
      if (ri >= 0 && ri < target.length && ci >= 0 && ci < (target[ri] || []).length) {
        target[ri][ci] = v
        n++
      }
    })
  })
  return n
}

/** 一片 grid 的总格数，用于判断有没有被截断 */
export function gridSize(grid) {
  if (!Array.isArray(grid) || !grid.length) return 0
  return grid.reduce((s, r) => s + (r || []).length, 0)
}
