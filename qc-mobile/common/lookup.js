/**
 * 「输入或扫一个码，找到对应的批次」——追溯、出货登记、客诉登记都要用这一段。
 *
 * 抽出来不是因为短，而是因为**匹配规则错一次就查到别人的批次**：
 * 后端 `/batches?keyword=` 是 LIKE 模糊匹配，搜 B001 会把 B0011、B0012 一起带出来。
 * 三个页面各写一遍，只要有一处忘了再精确比一次，就会出现「登记在别的批次上」
 * 这种事，而它不会报错，只有对账的时候才发现。
 */
import { api, decodeBarcode } from './api.js'
import { batchStatusText } from './dict.js'
import { normalizeCode } from './code.js'
import { classifyScan, cleanScanText } from './scan.js'

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

/** 上传前压到长边 1600：这个尺寸解码绰绰有余，体积能少一个数量级 */
const UPLOAD_MAX_SIDE = 1600
const UPLOAD_QUALITY = 0.82

/**
 * H5 先把图压一遍再传。
 *
 * 手机原图 4000px、三四 MB，车间 WiFi 上传要三四秒，人站在机器旁边等这个很不耐烦；
 * 压到 1600px 一般 150KB 上下，后端本来也要缩到 2400 以内再解，信息没丢。
 * 拿不到 blob（老浏览器、画布被限制、图加载卡住）就原图直传——慢一点比传不上去强。
 *
 * 这里用「有没有 document」来判 H5，而不是条件编译：App 端没有 canvas，
 * 条件编译万一没生效就会在这里碰 undefined，而这条路是扫码的必经之路，
 * 塌了就是整个扫码用不了。
 */
function compressForUpload(path) {
  if (typeof document === 'undefined' || typeof Image === 'undefined') {
    return Promise.resolve(path)
  }
  return new Promise(resolve => {
    let done = false
    const finish = v => {
      if (!done) {
        done = true
        resolve(v)
      }
    }
    try {
      const img = new Image()
      img.onload = () => {
        try {
          const long = Math.max(img.width, img.height)
          const ratio = long > UPLOAD_MAX_SIDE ? UPLOAD_MAX_SIDE / long : 1
          const canvas = document.createElement('canvas')
          canvas.width = Math.round(img.width * ratio)
          canvas.height = Math.round(img.height * ratio)
          canvas.getContext('2d').drawImage(img, 0, 0, canvas.width, canvas.height)
          canvas.toBlob(b => finish(b ? URL.createObjectURL(b) : path), 'image/jpeg', UPLOAD_QUALITY)
        } catch (e) {
          finish(path)
        }
      }
      img.onerror = () => finish(path)
      img.src = path
      // 图加载卡住时别把人一直晾在「识别中」
      setTimeout(() => finish(path), 3000)
    } catch (e) {
      finish(path)
    }
  })
}

/**
 * 扫码。
 *
 * <p>走「拍照 → 后端解码」而不是 {@code uni.scanCode}：那个 API 在 H5 端**根本不支持**
 * （官方平台差异表里 H5 那栏就是 ×，跟 HTTP/HTTPS 没关系），
 * 浏览器原生的 BarcodeDetector 又只有 Chromium 系有、iOS Safari 没有。
 * 后端用 ZXing 解，一维二维都认，跟浏览器和协议都没关系。
 *
 * <p>认出来的东西不一定有用——车间标签旁边常贴着设备点检码、公众号、供应商网址，
 * 所以先过一遍 {@link classifyScan}，是网址就直说，别拿去查批次然后报「批次不存在」。
 */
export function scanCode(onResult) {
  uni.chooseImage({
    count: 1,
    sizeType: ['compressed'],
    sourceType: ['camera', 'album'],
    success: async res => {
      const path = (res.tempFilePaths || [])[0]
      if (!path) return

      uni.showLoading({ title: '识别中', mask: true })
      let result = null
      try {
        result = await decodeBarcode(await compressForUpload(path))
      } catch (e) {
        if (e && e.biz) {
          uni.hideLoading()
          uni.showModal({ title: '没识别出来', content: e.message, showCancel: false })
          return
        }
        // 压缩后的 blob 在个别浏览器上传不出去，退回原图再试一次
        try {
          result = await decodeBarcode(path)
        } catch (e2) {
          uni.hideLoading()
          uni.showModal({
            title: '没识别出来',
            content: e2.message || '重拍一张，让码占满画面、避开反光',
            showCancel: false
          })
          return
        }
      }
      uni.hideLoading()

      const text = cleanScanText(result && result.text)
      const verdict = classifyScan(text)
      if (verdict.kind !== 'code') {
        uni.showModal({
          title: verdict.kind === 'empty' ? '没识别出来' : '扫到的不是单号',
          content: `${verdict.tip}\n\n内容：${text || '（空）'}`,
          showCancel: false
        })
        return
      }
      onResult(text)
    },
    fail: err => {
      // 用户自己取消不提示
      if (/cancel/i.test((err && err.errMsg) || '')) return
      uni.showToast({ title: '没能打开相机或相册', icon: 'none' })
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
