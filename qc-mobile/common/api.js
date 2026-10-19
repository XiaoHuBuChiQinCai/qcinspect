// 本地调试时改成电脑内网 IP，例如 http://192.168.1.20:8080/api
// 小程序真机调试需在开发者工具里勾选「不校验合法域名」
const BASE = 'http://localhost:8080/api'

export function request(path, { method = 'GET', data } = {}) {
  return new Promise((resolve, reject) => {
    uni.request({
      url: BASE + path,
      method,
      data,
      success: res => {
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
  batches: status => request('/batches' + (status ? `?status=${status}` : '')),
  createInspection: batchId => request('/inspections', {
    method: 'POST',
    data: { batchId, inspectorName: '车间检验员' }
  }),
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
  })
}
