import { get, post, put, del } from '@/api/http'

/** 上级平台配置（本平台作为下级, M3-B1） */
export interface CascadeUpper {
  id: number
  name: string
  upperDeviceId: string
  localDeviceId: string
  upperIp: string
  upperPort: number
  transport: string
  expires: number
  keepaliveInterval: number
  catalogMode: number
  autoPushCatalog: number
  ptzForward: number
  status: number
  online: number
  registerTime: string
  createTime: string
}

/** 级联 UA 运行态快照（upperId → state/backoffSeconds） */
export type CascadeSessions = Record<string, { state: string; backoffSeconds: number }>

export interface CascadePage {
  total: number
  list: CascadeUpper[]
  sessions: CascadeSessions
}

export interface SharedChannelRow {
  id: number
  channelDbId: number
  virtualDeviceId: string
  enabled: number
  channelId: string
  name: string
  status: number
}

export interface SharedChannelBinding {
  channelDbId: number
  virtualDeviceId: string
}

export const cascadeApi = {
  page: (params: { keyword?: string; page: number; count: number }) => get<CascadePage>('/cascade/uppers', params),
  detail: (id: number) => get<CascadeUpper>(`/cascade/uppers/${id}`),
  create: (data: Partial<CascadeUpper> & { password?: string }) => post<CascadeUpper>('/cascade/uppers', data),
  update: (id: number, data: Partial<CascadeUpper> & { password?: string }) =>
    put<CascadeUpper>(`/cascade/uppers/${id}`, data),
  remove: (id: number) => del<void>(`/cascade/uppers/${id}`),
  enable: (id: number, enabled: number) => post<void>(`/cascade/uppers/${id}/enable?enabled=${enabled}`),
  pushCatalog: (id: number) => post<void>(`/cascade/uppers/${id}/catalog/push`),
  sharedChannels: (id: number) => get<SharedChannelRow[]>(`/cascade/uppers/${id}/channels`),
  replaceShared: (id: number, bindings: SharedChannelBinding[]) =>
    post<number>(`/cascade/uppers/${id}/channels`, { bindings })
}
