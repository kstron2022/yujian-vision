import { get, post } from '@/api/http'

/** 对讲会话信息（TalkSessionService.StartInfo 契约, docs/03 §5.x） */
export interface TalkStartResult {
  talkId: number
  streamId: string
  status: 'INVITING' | 'STREAMING' | string
  channelId: string
  /** 音频推送 WS（相对地址 /ws/talk?talkId=, 前端补 ws(s) 前缀与 token） */
  pushWsUrl: string
  reason?: string
}

export const talkApi = {
  /** 发起对讲: 建会话 + INVITE s=Talk */
  start: (channelDbId: number) => post<TalkStartResult>('/talk/start', { channelDbId }),
  /** 开始对讲: 等设备应答后置 STREAMING（平台开始向设备转发音频） */
  begin: (talkId: number) => post<TalkStartResult>('/talk/begin', { talkId }),
  /** 结束对讲 */
  stop: (talkId: number) => post<void>('/talk/stop', { talkId }),
  /** 会话状态 */
  status: (talkId: number) => get<TalkStartResult>('/talk/status', { talkId })
}
