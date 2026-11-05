/**
 * 「输入或扫一个码，找到对应的批次」——追溯、出货登记、客诉登记都要用这一段。
 *
 * 抽出来不是因为短，而是因为**匹配规则错一次就查到别人的批次**：
 * 后端 `/batches?keyword=` 是 LIKE 模糊匹配，搜 B001 会把 B0011、B0012 一起带出来。
 * 三个页面各写一遍，只要有一处忘了再精确比一次，就会出现「登记在别的批次上」
 * 这种事，而它不会报错，只有对账的时候才发现。
 */
import { api } from './api.js'
import { batchStatusText } from './dict.js'
import { normalizeCode } from './code.js'

export { normalizeCode }

/**
 * 按批次号 / 工单号找批次，返回**精确匹配**的数组。
 *
 * 用 `===` 再筛一次是必须的：后端给的是模糊结果，
 * 而模糊结果里第一行不一定是用户要找的那一批。
 */
export async function matchBatches(code) {
  const keywords = normalizeCode(code)
  if (!keywords) return []
  const list = await api.batchByKeyword(keywords)
  return (list || []).filter(b => b.batchNo === keywords || b.workOrderNo === keywords)
}

/**
 * 一个工单号可能挂多个批次（分批投产），这时让人自己选，别替他猜第一个。
 * 返回 Promise<batch | null>，用户取消给 null。
 */
export function pickBatch(list, emptyTip) {
  const rows = (list || []).slice(0, 6)
  if (!rows.length) {
    uni.showToast({ title: emptyTip || '没找到批次', icon: 'none' })
    return Promise.resolve(null)
  }
  if (rows.length === 1) return Promise.resolve(rows[0])
  return new Promise(resolve => {
    uni.showActionSheet({
      itemList: rows.map(b => `${b.batchNo}（${b.qty} 件 · ${batchStatusText(b.status)}）`),
      success: res => resolve(rows[res.tapIndex] || null),
      fail: () => resolve(null)
    })
  })
}

/** 扫码。http 下 H5 用不了（要求安全上下文），给出提示而不是静默失败 */
export function scanCode(onResult) {
  uni.scanCode({
    success: res => {
      const code = String(res.result || '').trim()
      if (code) onResult(code)
    },
    fail: err => {
      const m = (err && err.errMsg) || ''
      // 用户主动取消的 errMsg 是 "scanCode:fail cancel"，不该弹提示
      if (/not support|不支持|无法使用/i.test(m)) {
        uni.showToast({ title: '当前环境不支持扫码，请手输单号', icon: 'none' })
      }
    }
  })
}

/** uni.showModal 的 Promise 包装，成功才算确认 */
export function confirm(content, title) {
  return new Promise(resolve => {
    uni.showModal({
      title: title || '确认',
      content,
      success: res => resolve(!!res.confirm),
      fail: () => resolve(false)
    })
  })
}
