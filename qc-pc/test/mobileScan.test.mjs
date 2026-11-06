import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { cleanScanText, classifyScan } from '../../qc-mobile/common/scan.js'

const HERE = dirname(fileURLToPath(import.meta.url))

/**
 * 扫出来的那串字，能不能直接用。
 *
 * 这一层判错的后果不是报错，是**白扫**：车间标签旁边还贴着设备点检码、
 * 公司公众号、供应商网址，手机对着哪个都是扫。扫到网址就直接拿去查批次，
 * 只会得到「批次不存在」，人以为是系统坏了，其实扫错了地方。
 */
test('清理：换行、零宽、BOM、首尾空白全去掉', () => {
  assert.equal(cleanScanText('  B0012026001 \r\n'), 'B0012026001')
  assert.equal(cleanScanText('\uFEFFB001\u200B2026001'), 'B0012026001')
  assert.equal(cleanScanText('\tB001\n'), 'B001')
})

test('清理：空值给空串，别变成 "null" 或 "undefined"', () => {
  assert.equal(cleanScanText(null), '')
  assert.equal(cleanScanText(undefined), '')
  assert.equal(cleanScanText('   '), '')
})

test('清理：数字等非字符串输入不炸', () => {
  assert.equal(cleanScanText(12345), '12345')
})

test('正常单号：判为可查', () => {
  for (const code of ['B0012026001', 'QC-2026-001', 'B001', 'sh20261106001']) {
    assert.equal(classifyScan(code).kind, 'code', code)
    assert.equal(classifyScan(code).tip, '')
  }
})

test('网址：直说扫错了地方，别让它去查批次', () => {
  for (const url of ['https://www.qq.com', 'http://a.cn/x', 'www.example.com']) {
    const v = classifyScan(url)
    assert.equal(v.kind, 'url', url)
    assert.match(v.tip, /网址/)
  }
})

test('带空格的网址也能认出来：先清理再判', () => {
  assert.equal(classifyScan('  https://example.com/label  ').kind, 'url')
})

test('太长的不当单号：一整段 JSON 或备注不可能是批次号', () => {
  const v = classifyScan('X'.repeat(80))
  assert.equal(v.kind, 'text')
  assert.match(v.tip, /80/)
})

test('长度边界：正好 60 个字仍算单号，61 才拦', () => {
  assert.equal(classifyScan('A'.repeat(60)).kind, 'code')
  assert.equal(classifyScan('A'.repeat(61)).kind, 'text')
})

test('空：给一句能照做的提示，不是干瞪眼', () => {
  const v = classifyScan('')
  assert.equal(v.kind, 'empty')
  assert.match(v.tip, /再拍/)
})

/**
 * 前后端各清一遍，是故意的：万一后端换了解码库、或新老版本混跑，
 * 入口这层不该指望对方守规矩。但**两边清的字符集必须一样**——
 * 前端放行了后端丢掉的字符（或反过来），就会出现「界面显示 B001、后端查到 B001」这种鬼故事。
 */
test('清理字符集与后端 ScanCodeService 对齐', () => {
  const java = readFileSync(
    join(HERE, '../../qc-server/src/main/java/com/qc/inspect/service/ScanCodeService.java'),
    'utf8'
  )
  // Java 侧：控制字符 + DEL + BOM + 零宽空格 + 不换行空格
  for (const marker of ["c < 0x20", '0x7F', "\\uFEFF", "\\u200B", "\\u00A0"]) {
    assert.ok(java.includes(marker), `后端 clean() 里找不到 ${marker}，前后端口径已经不一致了`)
  }
  assert.ok(java.includes('.trim()'), '后端 clean() 少了 trim')
})
