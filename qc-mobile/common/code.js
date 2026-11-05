/**
 * 单号归一化：扫码结果、手输、从微信里粘过来的，都会带空格或大小写差异。
 *
 * 单独一个文件是因为它必须能单测：这里少去掉一个空格，下游的 `===` 就匹配不上，
 * 表现成「这批次不存在」——而批次明明在系统里，用户只会觉得系统坏了。
 * 碰 uni API 的那部分在 lookup.js，那份没法在 node 里跑。
 */

export function normalizeCode(raw) {
  return String(raw || '')
    .replace(/\s+/g, '')
    .trim()
    .toUpperCase()
}
