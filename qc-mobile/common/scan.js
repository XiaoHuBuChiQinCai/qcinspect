/**
 * 扫到的那串字，能不能当单号用。
 *
 * 抽出来是为了能被单测钉住，也因为这里判错的代价不小：
 * 车间标签旁边常常还贴着「设备点检二维码」「公司公众号」「供应商网址」，
 * 手机对着哪个都是扫，扫到网址直接拿去查批次，等于白扫一次还得重来。
 *
 * 零 import，Node 能直接跑（见 qc-pc/test/mobileScan.test.mjs）。
 */

/** 单号一般不会超过这个长度；超了基本是网址或一整段 JSON */
const MAX_CODE_LEN = 60

/**
 * 清掉换行、零宽字符、BOM。
 *
 * 后端已经清过一遍，这里再清是因为**压缩重传那条路**：
 * 万一后端新老版本混跑、或将来换了解码库，入口这一层不该依赖对方守规矩。
 */
export function cleanScanText(raw) {
  if (raw === null || raw === undefined) return ''
  return String(raw)
    .replace(/[\u0000-\u001F\u007F\uFEFF\u200B-\u200D\u00A0]/g, '')
    .trim()
}

/**
 * 判断扫到的是不是「本系统的单号」。
 *
 * @returns {{kind: 'code'|'url'|'text'|'empty', tip: string}}
 *   code  可以直接拿去查
 *   url   是网址，多半扫到别的标签了
 *   text  太长，不像单号
 *   empty 什么都没扫到
 */
export function classifyScan(raw) {
  const text = cleanScanText(raw)
  if (!text) {
    return { kind: 'empty', tip: '这张图里没找到条码，让码占满画面再拍一张' }
  }
  if (/^https?:\/\//i.test(text) || /^www\./i.test(text)) {
    return { kind: 'url', tip: '扫到的是网址，不是工件标签上的单号。对准标签上的条码重拍' }
  }
  if (text.length > MAX_CODE_LEN) {
    return { kind: 'text', tip: `内容有 ${text.length} 个字，不像单号，可能扫错了标签` }
  }
  return { kind: 'code', tip: '' }
}
