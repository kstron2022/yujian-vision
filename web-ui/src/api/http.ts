import axios from 'axios'
import { Message } from '@arco-design/web-vue'
import { useAuthStore } from '@/stores/auth'
import {
  isGatewayDown,
  markBackendDown,
  markBackendUp
} from '@/utils/backend-health'

/** 统一 http 封装：携带 Bearer、解响应包络 {code,msg,data}、401/403 统一处理（docs/03 §1.1） */
const instance = axios.create({ baseURL: '/api', timeout: 15000 })

instance.interceptors.request.use((config) => {
  const auth = useAuthStore()
  if (auth.token) {
    config.headers.Authorization = `Bearer ${auth.token}`
  }
  return config
})

instance.interceptors.response.use(
  (resp) => {
    // 能应答即后端可达: 顺带解除全局"服务中断"提示
    markBackendUp()
    const body = resp.data
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 0) return body.data
      if (body.code === 401) {
        useAuthStore().reset()
        if (!location.pathname.startsWith('/login')) location.href = '/login'
      } else {
        Message.error(body.msg || '请求失败')
      }
      // 业务错误: code 挂在 error 上供调用方分支处理（如云台 1009 限流提示）, message 仍为后端 msg
      const bizErr = Object.assign(new Error(body.msg || `code=${body.code}`), {
        code: body.code as number
      })
      return Promise.reject(bizErr)
    }
    return body
  },
  (err) => {
    const status = err.response?.status
    if (status === 401) {
      markBackendUp()
      useAuthStore().reset()
      if (!location.pathname.startsWith('/login')) location.href = '/login'
    } else if (status === 403) {
      markBackendUp()
      Message.error('无权限操作')
    } else if (
      err.code === 'ECONNABORTED' ||
      err.code === 'ETIMEDOUT'
    ) {
      // 超时≠不可达, 不触发全局提示
      Message.error('请求超时, 请稍后重试')
    } else if (isGatewayDown(status) || !err.response) {
      // 后端不可达(网关 502/503/504 或网络失败): 全局提示条统一示意, 不逐请求弹错刷屏
      markBackendDown()
      return Promise.reject(new Error('服务连接中断, 请稍后重试'))
    } else {
      markBackendUp()
      Message.error(err.response?.data?.msg || err.message || '网络错误')
    }
    return Promise.reject(err)
  }
)

/**
 * 把 reject 出来的错误转成可直接展示的中文文案。
 *
 * 拦截器 reject 的有三类: 业务错误（数字 code + 后端中文 msg）、网关中断（本地中文 Error）、
 * axios 原始错误（超时 "timeout of 15000ms exceeded" / 403 "Request failed with status code 403"
 * 这类英文技术文案）。前两类能直接给用户看, 第三类必须由调用方给本地文案兜住。
 */
export function errorTextOf(e: unknown, fallback: string): string {
  const err = e as { code?: unknown; response?: unknown; message?: string } | null
  if (typeof err?.code === 'number') return err.message || fallback;
  if (!err?.response && !err?.code) return err?.message || fallback;
  return fallback;
}

/** 拦截器已解包 data, 这里仅修正类型; config 可覆写 timeout 等（慢接口专用, 如录像检索 20s 让后端 1010 文案先到） */
export function get<T>(url: string, params?: object, config?: { timeout?: number }): Promise<T> {
  return instance.get(url, { params, ...config }) as Promise<T>
}
/** config 可覆写 timeout（慢接口专用, 如手动抓图最坏 8s 建流 + 15s 抓帧 = 23s） */
export function post<T>(url: string, data?: object, config?: { timeout?: number }): Promise<T> {
  return instance.post(url, data, config) as Promise<T>
}
export function put<T>(url: string, data?: object): Promise<T> {
  return instance.put(url, data) as Promise<T>
}
export function del<T>(url: string): Promise<T> {
  return instance.delete(url) as Promise<T>
}
export default instance
