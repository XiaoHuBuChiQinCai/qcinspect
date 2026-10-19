import axios from 'axios'
import { ElMessage } from 'element-plus'

const http = axios.create({
  baseURL: '/api',
  timeout: 15000
})

http.interceptors.response.use(
  res => {
    const body = res.data
    if (body && body.code !== 0) {
      ElMessage.error(body.msg || '请求失败')
      return Promise.reject(new Error(body.msg))
    }
    return body.data
  },
  err => {
    ElMessage.error(err.message || '网络异常')
    return Promise.reject(err)
  }
)

export default http
