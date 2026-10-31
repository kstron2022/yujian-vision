import { useAuthStore } from '@/stores/auth'
import { backendDown, markBackendDown, probeBackend } from './backend-health'

export type WsHandler = (type: string, data: Record<string, unknown>) => void

/** 事件类型常量（docs/03 §10） */
export const WsEvent = {
  DeviceOnline: 'DEVICE_ONLINE',
  DeviceOffline: 'DEVICE_OFFLINE',
  CatalogSyncProgress: 'CATALOG_SYNC_PROGRESS',
  ChannelChange: 'CHANNEL_CHANGE',
  StreamReady: 'STREAM_READY',
  StreamClosed: 'STREAM_CLOSED',
  // UDP 收流超时自动降级 TCP re-INVITE。
  StreamFallback: 'STREAM_FALLBACK',
  // M5-B2 回放控制被设备拒绝（INFO 非 2xx 应答, payload {streamId, code}）: 流不受影响, 仅提示
  PlaybackControlFailed: 'PLAYBACK_CONTROL_FAILED',
  // B1 消息中心: 新站内消息（payload {id,type,title,level,createTime}, 角标 +1）
  NoticeNew: 'NOTICE_NEW',
  // M3-B4 报警: 设备/上级报警 NOTIFY 入库后推送（payload {deviceId, channelId, alarmType, alarmPriority, alarmTime}）
  Alarm: 'ALARM'
} as const

class WsClient {
  private ws: WebSocket | null = null
  private handlers = new Set<WsHandler>()
  private retry = 0
  private timer: number | undefined

  constructor() {
    // e2e 专用: dev 模式允许测试脚本直接注入事件, 走与生产一致的 handler 链路。
    // import.meta.env.DEV 被 vite 静态替换, 生产构建此分支被消除。
    if (import.meta.env.DEV) {
      ;(window as unknown as { __wsInject?: (type: string, data: object) => void }).__wsInject =
        (type, data) => this.handlers.forEach((h) => h(type, data as Record<string, unknown>))
    }
  }

  connect() {
    const auth = useAuthStore()
    // 后端不可达期间冻结重连(探测驱动恢复), 避免握手失败循环刷屏
    if (backendDown.value) return
    if (!auth.token || (this.ws && this.ws.readyState <= WebSocket.OPEN)) return
    this.clearTimer()
    const proto = location.protocol === 'https:' ? 'wss' : 'ws'
    this.ws = new WebSocket(`${proto}://${location.host}/ws/api?token=${auth.token}`)
    this.ws.onopen = () => {
      this.retry = 0
    }
    this.ws.onmessage = (ev) => {
      try {
        const msg = JSON.parse(ev.data as string)
        this.handlers.forEach((h) => h(msg.type, msg.data))
      } catch {
        /* 非 JSON 帧忽略 */
      }
    }
    this.ws.onclose = (ev) => {
      this.ws = null
      // 1008 POLICY_VIOLATION: 服务端吊销会话（强退/改密/停用）→ 不重连, 回登录页
      if (ev.code === 1008) {
        useAuthStore().reset()
        location.href = '/login'
        return
      }
      // 先探测确认: 后端可达→按退避重连(服务端短暂重启); 不可达→交给全局健康状态(提示条+探测恢复)
      void probeBackend().then((up) => {
        if (up) this.scheduleReconnect()
        else markBackendDown()
      })
    }
  }

  private scheduleReconnect() {
    const delay = Math.min(1000 * 2 ** this.retry, 30000)
    this.retry += 1
    this.timer = window.setTimeout(() => this.connect(), delay)
  }

  on(h: WsHandler): () => void {
    this.handlers.add(h)
    return () => this.handlers.delete(h)
  }

  close() {
    this.clearTimer()
    this.retry = 0
    if (this.ws) {
      this.ws.onclose = null
      this.ws.close()
      this.ws = null
    }
  }

  /** 后端不可达: 冻结重连定时器(connect 内仍有 backendDown 兜底) */
  suspend() {
    this.clearTimer()
  }

  /** 后端恢复: 重置退避并立即重连 */
  resume() {
    this.retry = 0
    this.clearTimer()
    this.connect()
  }

  private clearTimer() {
    if (this.timer) {
      window.clearTimeout(this.timer)
      this.timer = undefined
    }
  }
}

export const wsClient = new WsClient()
