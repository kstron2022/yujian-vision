import { get, post, put, del } from '@/api/http'
import type { PageResult } from '@/api/device'

/** ===== 用户（docs/03 §6, sys:user:*）===== */
export interface SystemUser {
  id: number
  username: string
  nickname: string
  status: number
  mustChangePwd: boolean
  lastLogin: string
  roleIds: number[]
}

export const userApi = {
  page: (params: { page: number; count: number; keyword?: string; status?: number }) =>
    get<PageResult<SystemUser>>('/users', params),
  create: (data: { username: string; password: string; nickname?: string; roleIds?: number[] }) =>
    post<void>('/users', data),
  update: (id: number, data: { nickname?: string; status?: number; roleIds?: number[] }) =>
    put<void>(`/users/${id}`, data),
  remove: (id: number) => del<void>(`/users/${id}`),
  resetPwd: (id: number, password: string) =>
    put<void>(`/users/${id}/reset-password`, { password })
}

/** ===== 角色（sys:role:*）===== */
export interface RoleOption {
  id: number
  roleCode: string
  roleName: string
}

export interface SysRole extends RoleOption {
  remark: string
  createTime: string
}

export const roleApi = {
  page: (params: { page: number; count: number; keyword?: string }) =>
    get<PageResult<SysRole>>('/roles', params),
  all: () => get<RoleOption[]>('/roles/all'),
  create: (data: { roleCode: string; roleName: string; remark?: string; menuIds?: number[] }) =>
    post<void>('/roles', data),
  update: (id: number, data: { roleName?: string; remark?: string; menuIds?: number[] }) =>
    put<void>(`/roles/${id}`, data),
  remove: (id: number) => del<void>(`/roles/${id}`),
  menuIds: (id: number) => get<number[]>(`/roles/${id}/menus`),
  grant: (id: number, menuIds: number[]) => put<void>(`/roles/${id}/menus`, { menuIds }),
  /** 数据授权回显（B3 通道级授权, perm sys:role:data-scope）: dataScope 1=全部通道 2=按分组 */
  dataScope: (id: number) => get<{ dataScope: number; groupIds: number[] }>(`/roles/${id}/data-scope`),
  /** 保存数据授权（全量覆盖 groupIds; admin 恒全部不依赖此配置） */
  grantDataScope: (id: number, dataScope: number, groupIds: number[]) =>
    put<void>(`/roles/${id}/data-scope`, { dataScope, groupIds })
}

/** ===== 菜单（D22 定稿式: 仅查看树）===== */
export interface MenuTreeItem {
  id: number
  parentId: number
  menuName: string
  menuType: number
  path: string
  component: string
  permCode: string
  icon: string
  sortOrder: number
  visible: number
  status: number
  children: MenuTreeItem[]
}

export const menuApi = {
  tree: () => get<MenuTreeItem[]>('/menus/tree')
}

/** ===== 在线会话（sys:online:*）===== */
export interface OnlineSession {
  tokenId: string
  userId: number
  username: string
  nickname: string
  loginIp: string
  loginTime: number
  idleRemainSeconds: number
}

export const onlineApi = {
  list: () => get<OnlineSession[]>('/auth/online'),
  kick: (tokenId: string) => del<void>(`/auth/online/${tokenId}`)
}

/** ===== 日志（log:oper:list / log:login:list）===== */
export interface LoginLog {
  id: number
  userId: number
  username: string
  clientIp: string
  userAgent: string
  result: number
  msg: string
  createTime: string
}

export interface OperLog {
  id: number
  userId: number
  username: string
  module: string
  operation: string
  bizType: string
  bizId: string
  detail: string
  clientIp: string
  costMs: number
  result: number
  createTime: string
}

export const logApi = {
  login: (params: { page: number; count: number; username?: string; result?: number }) =>
    get<PageResult<LoginLog>>('/auth/login-logs', params),
  oper: (params: {
    page: number
    count: number
    module?: string
    username?: string
    result?: number
  }) => get<PageResult<OperLog>>('/oper-logs', params)
}

/** ===== 流媒体节点（server:*）===== */
export interface MediaServer {
  id: number
  serverId: string
  hostIp: string
  publicIp: string
  httpPort: number
  httpSslPort: number
  rtspPort: number
  rtmpPort: number
  rtcPort: number
  rtpPortStart: number
  rtpPortEnd: number
  hookEnable: number
  zlmVersion: string
  status: number
  streamCount: number
  createTime: string
}

export interface ServerPayload {
  serverId: string
  hostIp: string
  publicIp?: string
  httpPort?: number
  httpSslPort?: number
  rtspPort?: number
  rtmpPort?: number
  rtcPort?: number
  rtpPortStart?: number
  rtpPortEnd?: number
  hookEnable?: number
  signSecret?: string
}

export interface ZlmStream {
  app?: string
  schema?: string
  stream?: string
  readers?: number
  totalReaderCount?: number
  originType?: number
  createTime?: string
  /** ZLM getMediaList 原始字段(unix 秒); createTime 缺失时前端据此转换 */
  createStamp?: number
}

export const serverApi = {
  list: () => get<MediaServer[]>('/servers'),
  create: (p: ServerPayload) => post<void>('/servers', p),
  // 后端为 patch 语义: 留空字段不覆盖（signSecret 留空=不修改）
  update: (serverId: string, p: Partial<ServerPayload>) => put<void>(`/servers/${serverId}`, p),
  remove: (serverId: string) => del<void>(`/servers/${serverId}`),
  streams: (serverId: string) =>
    get<{ code?: number; data?: ZlmStream[] }>(`/servers/${serverId}/streams`)
}
