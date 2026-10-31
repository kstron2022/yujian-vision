import { get, put } from '@/api/http'

/** 报警事件（M3-B4, docs/09 M3 #5; 表 gb_alarm_event） */
export interface AlarmItem {
  id: number
  deviceId: string
  /** 空=无关联通道的报警（设备自身故障类） */
  channelId: string | null
  /** 报警方式 1电话 2短信 3视频 4设备联网 5图片 6人工 */
  alarmMethod: number | null
  /** 报警依据类型（国标 alarm_type 字典, 未知值前端原样展示） */
  alarmType: number | null
  /** 1一级 2二级 3三级 4四级 */
  alarmPriority: number
  /** yyyy-MM-dd HH:mm:ss */
  alarmTime: string
  description: string
  /** 0=未处理 1=已处理 */
  handled: number
  handleTime: string | null
  handleUser: string
  createTime: string
}

/** /alarms 返回体（后端分页包络: total + list） */
export interface AlarmPage {
  total: number
  list: AlarmItem[]
}

export interface AlarmPageParams {
  deviceId?: string
  /** 描述/通道/设备模糊匹配 */
  keyword?: string
  /** 0=未处理 1=已处理 缺省=全部 */
  handled?: number
  /** yyyy-MM-dd HH:mm:ss */
  start?: string
  end?: string
  page: number
  count: number
}

export function pageAlarms(params: AlarmPageParams): Promise<AlarmPage> {
  return get<AlarmPage>('/alarms', params)
}

/** 报警处理: 状态流转为已处理（记录处理人=当前用户与处理时间） */
export function handleAlarm(id: number): Promise<void> {
  return put<void>(`/alarms/${id}/handle`)
}
