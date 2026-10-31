import { post } from '@/api/http'
import type { StreamUrls } from '@/api/play'

/** 回放控制指令（后端 PlaybackControlCmd, docs/03 §6: PAUSE/RESUME/SCALE 走 INFO, SEEK 平台内部 re-INVITE） */
export type PlaybackCommand = 'PAUSE' | 'RESUME' | 'SCALE' | 'SEEK'

/** 倍速档位（MANSRTSP Scale, docs/07 §3: 0.25/0.5/1/2/4; 前端控制条只暴露 0.5~4） */
export type PlaybackScale = 0.25 | 0.5 | 1 | 2 | 4

export interface PlaybackStartResult {
  streamId: string
  sessionId: number
  status: 'INVITING' | 'STREAMING' | string
  /** INVITING 时可能为空对象, 等 WS STREAM_READY 推送 urls（与点播 /play 同一套链路） */
  urls: StreamUrls
  /** 回放控制能力定级（M4-B3）: "sip"=GB 全控 / "seek"=ONVIF 仅拖动 / 缺省按 "sip"（旧后端兼容） */
  controlMode?: 'sip' | 'seek' | string
  /** 视频编码（H264/H265, 后端 B5 起与点播同口径携带）: H265 且无硬解时走软解兜底 */
  videoCodec?: string
}

export interface PlaybackControlParams {
  streamId: string
  cmd: PlaybackCommand
  /** cmd=SCALE 时必填 */
  scale?: PlaybackScale
  /** cmd=SEEK 时必填, yyyy-MM-dd HH:mm:ss（片段内绝对时刻） */
  seekTime?: string
}

export const playbackApi = {
  /** 邀请回放: 时间段取自录像片段, 响应多为 INVITING, 起播地址靠 WS STREAM_READY */
  start: (channelDbId: number, startTime: string, endTime: string) =>
    post<PlaybackStartResult>('/playback/start', { channelDbId, startTime, endTime }),
  /** 暂停/恢复/倍速/拖动控制; 会话未就绪/参数非法=400, 设备离线=1001 */
  control: (params: PlaybackControlParams) => post<void>('/playback/control', params),
  stop: (streamId: string) => post<void>('/playback/stop', { streamId })
}

/**
 * 页面卸载兜底: fetch keepalive 在 pagehide 里仍可发出（sendBeacon 无法带 Bearer）。
 * 与 api/play 的 stopStreamKeepalive 同款, 差异仅在 stop 需带 JSON body。
 * 失败不抛出——此时页面正在销毁。
 */
export function stopPlaybackKeepalive(streamId: string, token: string) {
  try {
    void fetch('/api/playback/stop', {
      method: 'POST',
      keepalive: true,
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ streamId })
    })
  } catch {
    /* 忽略: 页面卸载阶段 */
  }
}
