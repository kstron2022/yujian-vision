import { get, post } from '@/api/http'

/** 点播响应里的播放地址（PlayUrlService 签名, docs/03 §4.1） */
export interface StreamUrls {
  flv?: string
  wsFlv?: string
  hls?: string
  ts?: string
  rtc?: string
}

export interface PlayResult {
  streamId: string
  sessionId: number
  status: 'INVITING' | 'STREAMING' | string
  /** INVITING 时为空对象, 等 WS STREAM_READY 携带 urls */
  urls: StreamUrls
  /** 视频编码 H264/H265（复用点播/STREAMING 直连时返回; INVITING 时随 STREAM_READY 下发） */
  videoCodec?: string
}

export interface PlaySession {
  streamId: string
  channelId: string
  status: string
  viewerCount?: number
}

export const playApi = {
  play: (channelDbId: number, tcpMode = 1, streamProfile = 1) =>
    post<PlayResult>('/play', { channelDbId, tcpMode, streamProfile }),
  stop: (streamId: string) => post<void>(`/play/${streamId}/stop`),
  sessions: (status?: string) => get<PlaySession[]>('/play/sessions', status ? { status } : undefined),
  /** 会话流就绪后的播放地址: WS STREAM_READY 事件不持久化(登录瞬间/断线窗口发出即丢),
   *  inviting 态兜底轮询此端点自愈 —— 流未就绪时后端 1004, 就绪后返回地址表 */
  urls: (streamId: string) => get<{ streamId: string; urls: StreamUrls }>(`/play/${streamId}/urls`)
}

/**
 * 页面卸载兜底: fetch keepalive 在 beforeunload/pagehide 里仍可发出（sendBeacon 无法带 Bearer 头）。
 * 失败不抛出——此时页面正在销毁。
 */
export function stopStreamKeepalive(streamId: string, token: string) {
  try {
    void fetch(`/api/play/${encodeURIComponent(streamId)}/stop`, {
      method: 'POST',
      keepalive: true,
      headers: { Authorization: `Bearer ${token}` }
    })
  } catch {
    /* 忽略: 页面卸载阶段 */
  }
}
