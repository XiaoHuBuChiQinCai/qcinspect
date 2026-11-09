// 本地调试时改成电脑内网 IP，例如 http://192.168.1.20:8080/api
// 小程序真机调试需在开发者工具里勾选「不校验合法域名」
//
// H5 端走相对路径 /api：线上由 Nginx 同域反代，本地由 vite.config.js 的 proxy 转 8080，
// 这样换服务器、换域名都不用改代码。小程序 / APP 端没有同域概念，仍用绝对地址。
// #ifdef H5
const BASE = '/api'
// #endif
// #ifndef H5
const BASE = 'http://localhost:8080/api'
// #endif

/**
 * 后端返回的图片地址在本地存储模式下是相对路径（/api/images/1/raw），
 * 拼上 BASE 才能在小程序和 H5 里直接显示。
 */
export function absUrl(url) {
  if (!url) return ''
  if (/^https?:\/\//i.test(url)) return url
  return BASE.replace(/\/api$/, '') + (url.startsWith('/') ? url : '/' + url)
}

const TOKEN_KEY = 'qc_token'
const USER_KEY = 'qc_user'

export function getToken() {
  return uni.getStorageSync(TOKEN_KEY) || ''
}

export function getUser() {
  const raw = uni.getStorageSync(USER_KEY)
  if (!raw) return null
  try {
    return typeof raw === 'string' ? JSON.parse(raw) : raw
  } catch {
    return null
  }
}

export function saveSession(token, user) {
  uni.setStorageSync(TOKEN_KEY, token)
  uni.setStorageSync(USER_KEY, user || null)
}

export function clearSession() {
  uni.removeStorageSync(TOKEN_KEY)
  uni.removeStorageSync(USER_KEY)
}

let redirecting = false

function goLogin() {
  if (redirecting) return
  redirecting = true
  clearSession()
  uni.reLaunch({
    url: '/pages/login/login',
    complete: () => {
      setTimeout(() => {
        redirecting = false
      }, 800)
    }
  })
}

/**
 * @param silent 不在这一层弹提示，把错误原文交给调用方自己展示。
 *               登录页要用：锁定提示是一整句话，toast 只显示一行，后半句「等多久、找谁」会被切掉
 */
export function request(path, { method = 'GET', data, silent = false } = {}) {
  return new Promise((resolve, reject) => {
    const token = getToken()
    uni.request({
      url: BASE + path,
      method,
      data,
      header: token ? { Authorization: `Bearer ${token}` } : {},
      success: res => {
        if (res.statusCode === 401) {
          if (!silent) {
            uni.showToast({ title: '登录已过期，请重新登录', icon: 'none' })
          }
          goLogin()
          reject(new Error('unauthorized'))
          return
        }
        const body = res.data
        if (body && body.code === 0) {
          resolve(body.data)
        } else {
          const msg = (body && body.msg) || '请求失败'
          if (!silent) {
            uni.showToast({ title: msg, icon: 'none' })
          }
          reject(new Error(msg))
        }
      },
      fail: err => {
        if (!silent) {
          uni.showToast({ title: '网络异常，请检查服务端', icon: 'none' })
        }
        reject(err)
      }
    })
  })
}

export const api = {
  login: (username, password) => request('/auth/login', {
    method: 'POST',
    data: { username, password },
    // 错误原文由登录页自己展示：锁定提示太长，toast 装不下
    silent: true
  }),
  batches: status => request('/batches' + (status ? `?status=${status}` : '')),
  createInspection: batchId => request('/inspections', {
    method: 'POST',
    data: { batchId }
  }),
  /** 该批次下已有的检验单，用于中途退出后接着填，而不是再建一张 */
  inspectionsOfBatch: batchId => request(`/inspections/page?batchId=${batchId}&size=5`),
  detail: id => request(`/inspections/${id}`),
  saveDraft: (id, lines) => request(`/inspections/${id}/save`, {
    method: 'POST',
    data: { lines }
  }),
  submit: (id, lines) => request(`/inspections/${id}/submit`, {
    method: 'POST',
    data: { lines }
  }),
  reopen: (id, reason) => request(`/inspections/${id}/reopen?reason=${encodeURIComponent(reason)}`, {
    method: 'POST'
  }),
  /**
   * 全部量具，**不带 status=ACTIVE**。
   *
   * 停用和报废的量具后端提交时一律硬拦（`GaugeService.assertUsable`），
   * 前端要是把它们藏起来，草稿里已选的那把一旦被停用就会显示成「未选择」——
   * 人以为没选，提交却被驳回「已停用」，完全对不上。要让人看见它为什么不能用。
   */
  gauges: () => request('/gauges'),
  gaugeCalibrations: id => request(`/gauges/${id}/calibrations`),
  /** 本月还没点检的量具（只含在用的）。和点检页同源，别在别处再算一遍 */
  gaugePendingChecks: () => request('/gauges/checks/pending'),
  gaugeChecks: id => request(`/gauges/${id}/checks`),
  /** 登记点检。结论由后端算，前端只传标称值、实测值、允差 */
  submitGaugeCheck: (id, body) => request(`/gauges/${id}/check`, { method: 'POST', data: body }),
  images: (bizType, bizId) => request(`/images?bizType=${bizType}&bizId=${bizId}`),
  removeImage: id => request(`/images/${id}`, { method: 'DELETE' }),
  report: id => request(`/reports/inspection/${id}`),

  // ---------- 追溯 ----------
  /** 批次号 / 工单号模糊搜，调用方必须自己再精确比一次 */
  batchByKeyword: kw => request('/batches?keyword=' + encodeURIComponent(kw)),
  traceByBatch: id => request(`/trace/batch/${id}`),
  traceByShipment: no => request(`/trace/shipment/${encodeURIComponent(no)}`),
  /** 按检验单号找单据。keyword 同时匹配单号和批次号，所以要自己筛 */
  inspectionSearch: kw => request(`/inspections/page?keyword=${encodeURIComponent(kw)}&size=5`),

  // ---------- 出货 ----------
  /** 出货前置信息：还剩多少可发、最新检验结论、会被什么拦下 */
  shipmentContext: batchId => request(`/shipments/context/${batchId}`),
  /**
   * 登记出货。force=true 表示确认「例外放行」（不合格 / 无结论 / 有未关闭客诉的批次），
   * 后端会在备注里打上 [例外放行] 标记，别在别处再编一个同义字段
   */
  createShipment: body => request('/shipments', { method: 'POST', data: body }),
  shipmentsOfBatch: batchId => request(`/shipments?batchId=${batchId}`),
  /**
   * 红冲（作废）出货单。理由必填；非管理员只能销自己登记的单。
   *
   * 后端把「谁登记」「能不能销」都自己判，前端 `common/shipment.js` 的
   * `voidBlocker` 只是提前把话说清楚，不是权限——别把它当成唯一一道门。
   */
  voidShipment: (id, reason) => request(`/shipments/${id}/void`, {
    method: 'POST',
    data: { reason }
  }),

  // ---------- 返修 ----------
  /**
   * 未完工的返修票（待返修 + 返修中），超期的排在前面。
   * 服务端已经算好 overdue / overdueDays，别在手机上拿本地时间再判一次
   */
  reworkPending: (limit = 100) => request(`/rework/pending?limit=${limit}`),
  /** 开工：待返修 → 返修中。重复点无副作用 */
  startRework: id => request(`/rework/${id}/start`, { method: 'POST' }),
  /**
   * 完工报数。返好 + 返废 必须等于送修数量（后端和库层 CHECK 都会拦）。
   * 返好 > 0 时后端自动开复检单，件数就是返好件数；全部返废则不良改判报废
   */
  finishRework: (id, body) => request(`/rework/${id}/finish`, { method: 'POST', data: body }),

  // ---------- 客诉 ----------
  /** 登记客诉。批次必填、客户与问题描述必填，日期不能晚于今天 */
  createComplaint: body => request('/complaints', { method: 'POST', data: body }),
  complaintsOfBatch: batchId => request(`/complaints?batchId=${batchId}`),
  /**
   * 客诉分页。open=true 只看未关闭的（待处理 + 处理中）。
   * 不要在前端拉全量再按 status 过滤：客诉是常年累积的，翻到第 3 页就找不到今天这条了
   */
  complaintsPage: (params = {}) => {
    const q = Object.keys(params)
      .filter(k => params[k] !== undefined && params[k] !== null && params[k] !== '')
      .map(k => `${k}=${encodeURIComponent(params[k])}`)
      .join('&')
    return request('/complaints/page' + (q ? '?' + q : ''))
  },
  /** 受理：待处理 → 处理中。重复点无副作用 */
  startComplaint: id => request(`/complaints/${id}/start`, { method: 'POST' }),
  /** 关闭：根因、措施、责任归属三项必填，后端仅 ADMIN / REVIEWER 可调 */
  closeComplaint: (id, body) => request(`/complaints/${id}/close`, { method: 'POST', data: body }),

  // ---------- 待办 ----------
  todoSummary: () => request('/todo/summary'),
  todoReviews: () => request('/todo/reviews'),
  todoDefects: () => request('/todo/defects'),
  todoMine: () => request('/todo/mine'),
  todoGauges: () => request('/todo/gauges'),

  /** 审核：approve=true 通过归档，false 退回草稿（comment 必填） */
  review: (id, approve, comment) => request(`/inspections/${id}/review`, {
    method: 'POST',
    data: { approve, comment }
  }),

  /** 不良处置：disposition 取 REWORK / SCRAP / RETURN，status 取 PROCESSING / DONE */
  dispose: (id, disposition, status, note) => request(`/defects/${id}/dispose`, {
    method: 'POST',
    data: { disposition, status, note }
  }),

  /** 让步接收：理由与授权人必填，后端会校验，DB 还有 CHECK 约束兜底 */
  concession: (id, reason, approvedBy) => request(`/defects/${id}/concession`, {
    method: 'POST',
    data: { reason, approvedBy }
  }),

  // ---------- 通知 ----------
  notices: (unreadOnly, limit) =>
    request(`/notices?unreadOnly=${!!unreadOnly}&limit=${limit || 20}`),
  noticeSummary: () => request('/notices/summary'),
  readNotice: id => request(`/notices/${id}/read`, { method: 'POST' }),
  readAllNotices: () => request('/notices/read-all', { method: 'POST' })
}

/** 单张图片上传，返回 Promise */
export function uploadImage(filePath, bizType, bizId) {
  return new Promise((resolve, reject) => {
    const token = getToken()
    uni.uploadFile({
      url: `${BASE}/images`,
      filePath,
      name: 'file',
      formData: { bizType, bizId: String(bizId) },
      header: token ? { Authorization: `Bearer ${token}` } : {},
      success: res => {
        if (res.statusCode === 401) {
          goLogin()
          reject(new Error('unauthorized'))
          return
        }
        try {
          const body = JSON.parse(res.data)
          if (body.code === 0) resolve(body.data)
          else {
            uni.showToast({ title: body.msg || '上传失败', icon: 'none' })
            reject(new Error(body.msg))
          }
        } catch (e) {
          reject(e)
        }
      },
      fail: err => {
        uni.showToast({ title: '上传失败', icon: 'none' })
        reject(err)
      }
    })
  })
}

/**
 * 拍照识别条码，返回 { text, format, formatText, rotate }。
 *
 * 这里**故意不弹 toast**：认不出条码的原因不止「拍糊了」一种（扫成了网址、
 * 扫到了设备点检码、图里根本没有码），这些必须弹个能看清的说明让人知道下一步怎么做。
 * toast 一闪而过，人只会站在原地反复点同一个按钮。
 */
export function decodeBarcode(filePath) {
  return new Promise((resolve, reject) => {
    const token = getToken()
    uni.uploadFile({
      url: `${BASE}/scan/decode`,
      filePath,
      name: 'file',
      header: token ? { Authorization: `Bearer ${token}` } : {},
      success: res => {
        if (res.statusCode === 401) {
          goLogin()
          reject(new Error('登录已过期，重新登录后再扫'))
          return
        }
        let body = null
        try {
          body = typeof res.data === 'string' ? JSON.parse(res.data) : res.data
        } catch (e) {
          body = null
        }
        if (body && body.code === 0) resolve(body.data)
        else {
          // 打上 biz 标记：调用方据此区分「后端说认不出」和「传不上去」——
          // 前者换张图也没用，后者换个传法还有救
          const err = new Error((body && body.msg) || '识别失败了，重试一次')
          err.biz = true
          reject(err)
        }
      },
      fail: () => reject(new Error('照片没传上去，检查一下网络'))
    })
  })
}
