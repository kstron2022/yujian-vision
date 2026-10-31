import { get, post, put, del } from '@/api/http'
import type { GbChannel } from '@/api/device'

/**
 * 自定义分组（docs/09 M2 B3 #5, 决策 D13: gb_group 为前端主树, Catalog 为原始视图）。
 * 树/通道树登录即可读（预览页分组视图共用, 通道侧由后端按数据权限过滤）;
 * 写操作按按钮权限点 group:add/update/delete/bind-channel 方法级校验。
 */

/** gb_group 行（ancestors/deletedTime 等冗余字段前端不消费, 不入类型） */
export interface GbGroup {
  id: number
  parentId: number
  groupName: string
  sortOrder: number
}

/** 分组树节点（不含通道, 管理页/角色数据授权树共用） */
export interface GroupNode {
  group: GbGroup
  children: GroupNode[]
}

/** 分组 + 通道叶子（预览页分组视图用; channels 已按当前用户数据权限过滤） */
export interface GroupChannelNode {
  group: GbGroup
  children: GroupChannelNode[]
  channels: GbChannel[]
}

/** 新建/编辑分组入参（编辑可挪挂载点; parentId 传 0/null = 根分组） */
export interface GroupSavePayload {
  parentId?: number | null
  groupName: string
  sortOrder?: number
}

/** 绑定视图通道行（单分组约束下 boundGroupIds 至多一个; 已绑其他分组的通道由前端隐藏） */
export interface BindViewChannel {
  id: number
  channelId: string
  name: string
  status: number
  deviceDbId: number
  deviceName: string
  boundGroupIds: number[]
  /** 目录节点(虚拟分组): 绑定树里作级联勾选分组壳, 非绑定对象 */
  directory: boolean
  /** 父节点编码(指向同设备目录节点, 组层级用) */
  parentId?: string
}

export const groupApi = {
  /** 分组树（管理页/角色授权树共用） */
  tree: () => get<GroupNode[]>('/groups/tree'),
  /** 分组 + 通道树（登录可读, 预览页分组视图） */
  channelTree: () => get<GroupChannelNode[]>('/groups/channel-tree'),
  /** 分组内通道（perm group:list, 通道绑定抽屉回显用） */
  channels: (id: number) => get<GbChannel[]>(`/groups/${id}/channels`),
  /** 通道绑定视图（全部可见通道+所属分组, 绑定抽屉数据源） */
  bindView: (id: number) => get<{ groupId: number; channels: BindViewChannel[] }>(`/groups/${id}/bind-view`),
  /** 新建分组, data= { id } */
  create: (data: GroupSavePayload) => post<{ id: number }>('/groups', data),
  /** 编辑分组（改名/排序/挪挂载点） */
  update: (id: number, data: GroupSavePayload) => put<void>(`/groups/${id}`, data),
  /** 删除分组（有子分组/有绑定通道时后端拒绝并给出文案） */
  remove: (id: number) => del<void>(`/groups/${id}`),
  /** 绑定通道到分组（全量覆盖） */
  bindChannels: (id: number, channelDbIds: number[]) =>
    put<void>(`/groups/${id}/channels`, { channelDbIds })
}
