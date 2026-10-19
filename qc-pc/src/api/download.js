import { ElMessage } from 'element-plus'
import http from './http'

/**
 * 下载 CSV。
 *
 * 后端出错时返回的是统一 JSON 结构而不是文件流，而 axios 配置了 responseType=blob
 * 后会把那段 JSON 也包成 Blob 交过来。所以这里必须先看 blob 的类型：
 * 是 json 就说明导出失败，解析出来提示，别让浏览器存下一个内容是报错信息的 .csv。
 */
export async function downloadCsv(url, params, prefix) {
  let blob
  try {
    blob = await http.get(url, { params, responseType: 'blob' })
  } catch (e) {
    return
  }
  if (!blob) {
    return
  }

  if (blob.type && blob.type.indexOf('json') >= 0) {
    const text = await blob.text()
    let msg = '导出失败'
    try {
      msg = JSON.parse(text).msg || msg
    } catch (e) {
      /* 解析不出来就用默认提示 */
    }
    ElMessage.error(msg)
    return
  }

  const name = `${prefix}-${stamp()}.csv`
  const href = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = href
  a.download = name
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  // 立刻 revoke 在部分浏览器会让下载中断，放到下一轮事件循环
  setTimeout(() => URL.revokeObjectURL(href), 1000)
  ElMessage.success(`已导出 ${name}`)
}

function stamp() {
  const d = new Date()
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}`
}
