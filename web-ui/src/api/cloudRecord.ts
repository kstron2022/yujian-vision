import { get, post, put, del } from '@/api/http'

/**
 * 云端录像 / 录像计划（docs/09 M2 B3 #3）。
 * 权限: 列表 = record:query; 下载 = cloud-record:download（V13）; 删除 = cloud-record:delete; 计划保存 = record-plan:manage。
 */

/** 云端录像行（后端 GbCloudRecordEntity, 出参不含 filePath） */
export interface CloudRecord {
  id: number
  serverId: string
  deviceId: string
  channelId: string
  channelDbId: number
  fileName: string
  /** 字节 */
  fileSize: number
  /** 秒 */
  duration: number
  /** ISO / "yyyy-MM-dd HH:mm:ss"（后端默认 ISO, 解析统一走 replace(' ','T') 兜底） */
  startTime: string
  endTime: string
  /** 上传状态: 1=完成 其余为进行中/失败 */
  uploadState: number
}

export interface CloudRecordPage {
  total: number
  list: CloudRecord[]
}

export interface CloudRecordQueryParams {
  channelDbId?: number
  /** yyyy-MM-dd HH:mm:ss */
  start?: string
  end?: string
  page: number
  count: number
}

/** 下载令牌应答: url 为免登录一次性地址（60s）, 直接 window.open */
export interface DownloadTicket {
  url: string
  expire: number
}

/**
 * 录像计划行（planType=1 全局默认 / 2 通道行, 通道行带通道名称）。
 * weekPlan[day][slot]: 30 分钟格（7×48）, true=该格录像。
 */
export interface RecordPlanRow {
  id: number
  planType: number
  channelDbId: number | null
  channelName: string | null
  enable: number
  weekPlan: boolean[][]
}

/** 计划保存入参（planType=1 时 channelDbId 须为空; planType=2 时必填） */
export interface RecordPlanUpsert {
  planType: number
  channelDbId?: number | null
  enable: number
  weekPlan: boolean[][]
}

export const cloudRecordApi = {
  page: (params: CloudRecordQueryParams) => get<CloudRecordPage>('/cloud-records', params),
  download: (id: number) => post<DownloadTicket>(`/cloud-records/${id}/download`),
  remove: (id: number) => del<void>(`/cloud-records/${id}`)
}

export const recordPlanApi = {
  list: () => get<RecordPlanRow[]>('/record-plan'),
  /** upsert 单条: 全局默认行 planType=1 / 通道行 planType=2 + channelDbId */
  save: (data: RecordPlanUpsert) => put<{ id: number }>('/record-plan', data)
}
