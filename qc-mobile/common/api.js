// 本地调试时改成电脑内网 IP，例如 http://192.168.1.20:8080/api
// 小程序真机调试需在开发者工具里勾选「不校验合法域名」
const BASE = 'http://localhost:8080/api'

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

export function request(path, { method = 'GET', data } = {}) {
  return new Promise((resolve, reject) => {
    const token = getToken()
    uni.request({
      url: BASE + path,
      method,
      data,
      header: token ? { Authorization: `Bearer ${token}` } : {},
      success: res => {
        if (res.statusCode === 401) {
          uni.showToast({ title: '登录已过期，请重新登录', icon: 'none' })
          goLogin()
          reject(new Error('unauthorized'))
          return
        }
        const body = res.data
        if (body && body.code === 0) {
          resolve(body.data)
        } else {
          const msg = (body && body.msg) || '请求失败'
          uni.showToast({ title: msg, icon: 'none' })
          reject(new Error(msg))
        }
      },
      fail: err => {
        uni.showToast({ title: '网络异常，请检查服务端', icon: 'none' })
        reject(err)
      }
    })
  })
}

export const api = {
  login: (username, password) => request('/auth/login', {
    method: 'POST',
    data: { username, password }
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
  gauges: () => request('/gauges?status=ACTIVE'),
  images: (bizType, bizId) => request(`/images?bizType=${bizType}&bizId=${bizId}`),
  removeImage: id => request(`/images/${id}`, { method: 'DELETE' }),
  report: id => request(`/reports/inspection/${id}`),

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
