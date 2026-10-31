import { get, post, put, del } from '@/api/http'
import type { PageResult } from '@/api/device'

/** ===== 开放接口应用管理（docs/15 步骤6, openapi:app:*）===== */
export interface OpenApp {
  id: number
  appKey: string
  appName: string
  allowedApis: string
  channelScope: string
  rateLimit: number
  expireTime: string | null
  status: number
  createTime: string
  updateTime: string
}

export interface OpenAppCredential {
  appId: number
  appKey: string
  appSecret: string
}

export interface OpenApiLog {
  id: number
  appId: number | null
  appKey: string
  method: string
  path: string
  param: string
  resultCode: number
  clientIp: string
  costMs: number
  remark: string
  createTime: string
}

export const openApiAppApi = {
  /** 开放接口功能启用状态（探测端点不受 ENABLE 开关条件装配, 未启用时管理页据此显示引导） */
  status: () => get<{ enabled: boolean }>('/openapi-status'),
  page: (params: { page: number; count: number; appName?: string; status?: number }) =>
    get<PageResult<OpenApp>>('/openapi-apps', params),
  detail: (id: number) => get<OpenApp>(`/openapi-apps/${id}`),
  create: (data: {
    appName: string
    allowedApis?: string
    channelScope?: string
    rateLimit?: number
    expireTime?: string
  }) => post<OpenAppCredential>('/openapi-apps', data),
  update: (
    id: number,
    data: { appName?: string; allowedApis?: string; channelScope?: string; rateLimit?: number; expireTime?: string }
  ) => put<void>(`/openapi-apps/${id}`, data),
  resetSecret: (id: number) => post<OpenAppCredential>(`/openapi-apps/${id}/reset-secret`),
  updateStatus: (id: number, status: number) => put<void>(`/openapi-apps/${id}/status?status=${status}`),
  remove: (id: number) => del<void>(`/openapi-apps/${id}`),
  logs: (params: { page: number; count: number; appKey?: string; resultCode?: number }) =>
    get<PageResult<OpenApiLog>>('/openapi-logs', params)
}
