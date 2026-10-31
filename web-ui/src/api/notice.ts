import { get, post } from '@/api/http'

/** 站内消息（B1 消息中心, docs/09） */
export interface NoticeItem {
  id: number
  /** 业务类型（如录像异常/系统事件, 决定跳转与展示图标） */
  type: string
  title: string
  content: string
  /** 1=普通 2=重要（重要在列表里挂"重要"标签） */
  level: number
  bizId: string
  /** null=未读 */
  readTime: string | null
  /** yyyy-MM-dd HH:mm:ss */
  createTime: string
}

/** /notices 返回体（后端分页包络: total + 当前页参数 + list） */
export interface NoticePage {
  total: number
  page: number
  count: number
  list: NoticeItem[]
}

export function listNotices(params: {
  /** true=仅未读 false=仅已读 缺省=全部 */
  unread?: boolean
  page: number
  count: number
}): Promise<NoticePage> {
  return get<NoticePage>('/notices', params)
}

/** 未读数（角标展示, 60s 轮询 + WS NOTICE_NEW 增量） */
export function unreadCount(): Promise<number> {
  return get<number>('/notices/unread-count')
}

/** 标记单条已读 */
export function markRead(id: number): Promise<void> {
  return post<void>(`/notices/${id}/read`)
}

/** 全部已读, data=更新条数 */
export function markAllRead(): Promise<number> {
  return post<number>('/notices/read-all')
}
