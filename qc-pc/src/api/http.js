import axios from 'axios'
import { ElMessage } from 'element-plus'

const TOKEN_KEY = 'qc_token'
const USER_KEY = 'qc_user'

export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function getStoredUser() {
  try {
    return JSON.parse(localStorage.getItem(USER_KEY) || 'null')
  } catch {
    return null
  }
}

export function saveSession(token, user) {
  localStorage.setItem(TOKEN_KEY, token)
  localStorage.setItem(USER_KEY, JSON.stringify(user || null))
}

export function clearSession() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}

const http = axios.create({
  baseURL: '/api',
  timeout: 20000
})

http.interceptors.request.use(cfg => {
  const token = getToken()
  if (token) {
    cfg.headers.Authorization = `Bearer ${token}`
  }
  return cfg
})

http.interceptors.response.use(
  res => {
    // 文件流（导出、图片）不参与业务码判断
    if (res.config.responseType === 'blob') {
      return res.data
    }
    const body = res.data
    if (body && body.code !== 0) {
      // silent：轮询类请求（通知角标）失败不该每分钟弹一次错误框，由调用方自己决定要不要提示
      if (!res.config.silent) {
        ElMessage.error(body.msg || '请求失败')
      }
      return Promise.reject(new Error(body.msg || '请求失败'))
    }
    return body.data
  },
  err => {
    const status = err.response?.status
    if (status === 401) {
      clearSession()
      if (!location.hash.startsWith('#/login')) {
        location.hash = '#/login'
      }
      ElMessage.error(err.response?.data?.msg || '登录已过期，请重新登录')
      return Promise.reject(err)
    }
    if (!err.config?.silent) {
      ElMessage.error(err.response?.data?.msg || err.message || '网络异常')
    }
    return Promise.reject(err)
  }
)

export default http
