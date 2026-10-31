import { get, post } from '@/api/http'

/** 预置位条目（PresetQueryService.PresetItem, 设备 PresetQuery 应答） */
export interface PtzPreset {
  presetId: string
  presetName: string
}

/** 云台指令名（后端 PtzCommand 枚举, 大小写不敏感, docs/03 §5） */
export type PtzCommand =
  | 'UP'
  | 'DOWN'
  | 'LEFT'
  | 'RIGHT'
  | 'ZOOM_IN'
  | 'ZOOM_OUT'
  | 'FOCUS_NEAR'
  | 'FOCUS_FAR'
  | 'IRIS_OPEN'
  | 'IRIS_CLOSE'
  | 'STOP'

export const ptzApi = {
  control: (channelDbId: number, command: PtzCommand, speed = 50) =>
    post<void>('/ptz/control', { channelDbId, command, speed }),
  /** 预置位列表（设备不支持/离线等场景报错码, 由调用方决定是否静默降级） */
  presetList: (channelDbId: number) => get<PtzPreset[]>('/ptz/preset/list', { channelDbId }),
  presetSet: (channelDbId: number, presetNum: number) =>
    post<void>('/ptz/preset/set', { channelDbId, presetNum }),
  presetGoto: (channelDbId: number, presetNum: number) =>
    post<void>('/ptz/preset/goto', { channelDbId, presetNum }),
  presetDelete: (channelDbId: number, presetNum: number) =>
    post<void>('/ptz/preset/delete', { channelDbId, presetNum })
}
