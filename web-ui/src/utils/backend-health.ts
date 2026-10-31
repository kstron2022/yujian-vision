import { ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import { wsClient } from './ws'

/**
 * 后端可达性全局状态（docs/03 §1.2 开发走 Vite 代理、生产走 Nginx 同源）:
 * - 置位: http 网关类失败(502/503/504/网络不可达) 或 WS 非正常断开
 * - 恢复: 任一 HTTP 响应(含 401/500, 能应答即可达) 或 5s 探测成功
 * 置位期间暂停 WS 重连(避免握手失败刷屏), 由探测驱动恢复。
 */
export const backendDown = ref(false)

const GATEWAY_DOWN_STATUS = new Set([502, 503, 504])
const PROBE_INTERVAL = 5000
const PROBE_URL = '/api/auth/me'

let probeTimer: number | undefined
let probing = false

export function isGatewayDown(status?: number): boolean {
  return status !== undefined && GATEWAY_DOWN_STATUS.has(status)
}

/** 后端不可达: 置位提示、暂停 WS 重连、启动探测 */
export function markBackendDown() {
  if (backendDown.value) return
  backendDown.value = true
  wsClient.suspend()
  startProbe()
}

/** 后端恢复: 撤提示、恢复 WS 重连 */
export function markBackendUp() {
  if (!backendDown.value) return
  backendDown.value = false
  stopProbe()
  wsClient.resume()
  Message.success('服务已恢复')
}

/** 单次探测: 能拿到非网关响应即视为可达 */
export async function probeBackend(): Promise<boolean> {
  try {
    const resp = await fetch(PROBE_URL, { cache: 'no-store' })
    return !GATEWAY_DOWN_STATUS.has(resp.status)
  } catch {
    return false
  }
}

/** 立即探测(提示条点击触发) */
export async function probeNow(): Promise<void> {
  if (await probeBackend()) markBackendUp()
}

function startProbe() {
  if (probeTimer) return
  void probeNow()
  probeTimer = window.setInterval(() => void probeNow(), PROBE_INTERVAL)
}

function stopProbe() {
  if (probeTimer) {
    window.clearInterval(probeTimer)
    probeTimer = undefined
  }
}
