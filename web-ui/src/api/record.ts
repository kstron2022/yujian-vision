import { get } from '@/api/http'

/** 录像片段（GB/T28181 录像目录查询 ITEM, 一条 = 一段连续录像; B1 录像检索 docs/09） */
export interface RecordSegment {
  deviceId: string
  recordId: string
  /** 文件名, 后端可能为空串 */
  name: string
  /** time=定时录像 alarm=移动侦测 其他原样 */
  type: string
  /** yyyy-MM-dd HH:mm:ss */
  startTime: string
  /** yyyy-MM-dd HH:mm:ss */
  endTime: string
}

/** /record/query 返回体（count=片段数, 空结果 count=0 segments=[]） */
export interface RecordQueryResult {
  channelDbId: number
  start: string
  end: string
  count: number
  segments: RecordSegment[]
}

export interface RecordQueryParams {
  channelDbId: number
  /** yyyy-MM-dd HH:mm:ss（空格由 axios 序列化为 %20/+, 与既有 get 封装一致, 无需手工编码） */
  start: string
  end: string
}

/** 录像检索（B2 回放另行接入, 本页仅查询展示）。timeout 20s > 后端 15s 收敛窗口, 让后端 1010 友好文案先于 axios 超时到达 */
export function queryRecord(params: RecordQueryParams): Promise<RecordQueryResult> {
  return get<RecordQueryResult>('/record/query', params, { timeout: 20000 })
}
