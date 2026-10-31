import { get, post, put, del } from '@/api/http'

export interface GbDevice {
  id: number
  deviceId: string
  name: string
  deviceType: number
  protocolVersion: string
  /** 接入协议（M4-B1/M5-B1）: GB/ONVIF/RTSP, 缺省按 GB */
  accessProtocol?: string
  /** RTSP 拉流传输 tcp/udp（仅拉流代理行; 出参可回显, 源地址密文不出参） */
  rtspTransport?: string
  /** 常驻拉流 0=按需(默认) 1=常驻（仅拉流代理行） */
  alwaysOn?: number
  /** ONVIF 用户名（仅 ONVIF 行非空, 编辑抽屉回显用; 密码永不出参） */
  onvifUsername?: string
  manufacturer: string
  model: string
  firmware: string
  transport: string
  expires: number
  heartbeatInterval: number
  heartbeatTimeout: number
  mediaTcpMode: number
  ip: string
  port: number
  online: number
  registerTime: string
  keepaliveTime: string
  /** 通道数（设备列表批量填充, 非库字段; 多通道≈NVR/编码器、单通道≈IPC 辅助识别） */
  channelCount?: number
}

export interface PageResult<T> {
  total: number
  list: T[]
}

/** 抓图来源（M3-B7）: MANUAL=手动抓图 AUTO=点播自动 IDLE=空闲抓图（本次临时建流） */
export type SnapshotSource = 'MANUAL' | 'AUTO' | 'IDLE'

/** 来源中文名（列表 tooltip 与预览弹窗共用, 避免两份定义漂移） */
export const SNAPSHOT_SOURCE_TEXT: Record<string, string> = {
  MANUAL: '手动抓图',
  AUTO: '点播自动',
  IDLE: '空闲抓图'
}
/** 来源标签配色: 手动=蓝 自动=绿 空闲=橙（空闲建流过流, 视觉上区分开） */
export const SNAPSHOT_SOURCE_COLOR: Record<string, string> = {
  MANUAL: 'arcoblue',
  AUTO: 'green',
  IDLE: 'orange'
}

/** 通道截图（后端 SnapshotVo）: url/thumbUrl 是带签名的同源相对地址, 可直接作 img src（免登录, TTL 300s） */
export interface ChannelSnapshot {
  url: string
  thumbUrl: string
  /** 抓图时间 yyyy-MM-dd HH:mm:ss */
  time: string
  source: SnapshotSource | string
}

/** 抓图能力开关: 前端据 enabled 决定整列是否展示; idleEnabled 是服务端能力项, 前端不预判
 *  （无流时直接调抓图接口, 由后端按 1014/1013 回处置口径） */
export interface SnapshotConfig {
  enabled: boolean
  idleEnabled: boolean
}

export interface GbChannel {
  id: number
  deviceDbId: number
  channelId: string
  name: string
  customName: string
  manufacturer: string
  model: string
  address: string
  parental: number
  status: number
  ptzType: number
  /** 目录节点（虚拟分组/行政区划/平台根, M5-B2）: 后端按结构判定, 不可点播/抓图 */
  directory?: boolean
  /** 云台开关: null=按 ptzType 自动 1=强制开启 0=禁用（B3 通道级强制开关） */
  ptzEnabled: number | null
  mobile: number
  /** ONVIF 通道: 主码流 Profile 列表 JSON（M4-B2, 分辨率列展示用）; GB 通道无此字段 */
  onvifProfiles?: string
  /** 最新一张截图（B7）: 无图 = null */
  snapshot?: ChannelSnapshot | null
}

export interface SyncProgress {
  running: boolean
  total: number
  synced: number
}

/** 通道树节点（后端 TreeNode: 通道实体 + 子节点; 虚拟分组节点 parental=1 也入树） */
export interface ChannelTreeNode {
  channel: GbChannel
  children: ChannelTreeNode[]
}

/** 国标接入配置视图（接入信息只读来自配置文件; 默认密码只出"是否已自定义"不出明文） */
export interface GbAccessConfig {
  serverId: string
  domain: string
  /** 服务器 IP（复制对接信息用; 后端未配宣告/具体监听地址时为空串, 前端回落当前访问主机名） */
  sipIp: string
  sipPort: number
  transports: string
  /** 允许自动注册: 1=开放模式 0=白名单模式（仅预建档可注册） */
  allowAutoRegister: number
  defaultPasswordSet: boolean
  defaultPasswordSource: 'db' | 'config'
  updateTime: string | null
}

/** 设备对接信息（含明文密码, 仅 device:update, 供复制发给接入方） */
export interface DeviceAccessInfo {
  serverId: string
  domain: string
  /** 服务器 IP（同 GbAccessConfig.sipIp 口径） */
  sipIp: string
  sipPort: number
  transports: string
  deviceId: string
  deviceName: string
  deviceType: number
}

/** ONVIF 通道预览档位（M4-B1, docs/13 §6.2; 主/子=按分辨率降序） */
export interface OnvifProfileView {
  token: string
  name: string
  width: number
  height: number
  encoding: string
  ptz: boolean
}

/** ONVIF VideoSource = 1 路平台通道 */
export interface OnvifSourceView {
  token: string
  profiles: OnvifProfileView[]
}

/** ONVIF 测试连接视图: 设备信息 + 通道预览一次拿全 */
export interface OnvifTestView {
  manufacturer: string
  model: string
  firmware: string
  serialNumber: string
  sources: OnvifSourceView[]
}

/** ONVIF 同网段探测发现的一台设备（M4-B4; 发现≠接入, registered=该地址已有 ONVIF 档案） */
export interface OnvifDiscoveredDevice {
  ip: string
  port: number
  serviceUrl?: string
  modelHint?: string
  registered: boolean
}

/** 拉流代理批量导入结果汇总（M5-B1） */
export interface RtspBatchImportResult {
  total: number
  success: number
  duplicated: number
  failed: { lineNo: number; line: string; reason: string }[]
}

export const deviceApi = {
  /**
   * ONVIF 测试连接（device:add）。后端最坏 3 次 SOAP 调用 × 8s = 24s,
   * 覆写全局 15s 防前端先超时而后端继续跑完(慢接口惯例, 同录像检索/抓图口径)。
   */
  onvifTestConnection: (data: {
    deviceId?: string
    ip: string
    port?: number
    username: string
    password?: string
  }) => post<OnvifTestView>('/devices/onvif/test-connection', data, { timeout: 30000 }),
  /** ONVIF 设备手填添加（建档即目录同步, 失败后端回滚） */
  createOnvif: (data: {
    ip: string
    port?: number
    username: string
    password: string
    name?: string
    probeInterval?: number
    probeTimeout?: number
    // 同上: 建档即目录同步(3 次 SOAP), 30s 覆写
  }) => post<GbDevice>('/devices/onvif', data, { timeout: 30000 }),
  /** ONVIF 同网段探测（WS-Discovery; 后端收响应窗口 4s, 覆写全局 15s 防前端先超时） */
  onvifDiscoveryProbe: () =>
    post<OnvifDiscoveredDevice[]>('/devices/onvif/discovery/probe', undefined, { timeout: 15000 }),
  /** ONVIF 设备参数修改（密码留空不修改） */
  updateOnvif: (
    deviceId: string,
    data: {
      name?: string
      ip?: string
      port?: number
      username?: string
      password?: string
      probeInterval?: number
      probeTimeout?: number
    }
  ) => put<void>(`/devices/onvif/${deviceId}`, data),
  page: (params: { online?: number; keyword?: string; accessProtocol?: string; page: number; count: number }) =>
    get<PageResult<GbDevice>>('/devices', params),
  /** 拉流代理新增（M5-B1: RTSP 流地址接入为设备, 建档即自动建单通道） */
  createRtsp: (data: {
    name?: string
    sourceUrl: string
    rtspTransport?: string
    alwaysOn?: number
    heartbeatInterval?: number
  }) => post<GbDevice>('/devices/rtsp', data),
  /** 拉流代理批量导入（每行 URL 一台设备, 支持「名称,URL」; 结果汇总） */
  batchImportRtsp: (data: { text: string; rtspTransport?: string; alwaysOn?: number }) =>
    post<RtspBatchImportResult>('/devices/rtsp/batch-import', data),
  /** 拉流代理编辑（源地址盲显: 留空不修改） */
  updateRtsp: (
    deviceId: string,
    data: {
      name?: string
      sourceUrl?: string
      rtspTransport?: string
      alwaysOn?: number
      heartbeatInterval?: number
    }
  ) => put<void>(`/devices/rtsp/${deviceId}`, data),
  detail: (deviceId: string) => get<GbDevice>(`/devices/${deviceId}`),
  /** 设备预建档（M3-B3: 下级平台 deviceType=2 先配密码再注册） */
  create: (data: {
    deviceId: string
    name?: string
    password: string
    deviceType: number
    protocolVersion?: string
    mediaTcpMode?: number
  }) => post<GbDevice>('/devices', data),
  update: (
    deviceId: string,
    data: {
      name?: string
      deviceType?: number
      password?: string
      heartbeatInterval?: number
      heartbeatTimeout?: number
      mediaTcpMode?: number
      protocolVersion?: string
    }
  ) => put<void>(`/devices/${deviceId}`, data),
  remove: (deviceId: string) => del<void>(`/devices/${deviceId}`),
  /** 国标接入配置（device:update）：接入信息展示 + 自动注册开关 + 默认接入密码修改 */
  accessConfig: () => get<GbAccessConfig>('/devices/access-config'),
  updateAccessConfig: (data: { defaultPassword?: string; allowAutoRegister?: number }) =>
    put<void>('/devices/access-config', data),
  /** 设备对接信息（device:update）：服务器信息+设备信息含明文密码, 供一键复制发给接入方 */
  deviceAccessInfo: (deviceId: string) =>
    get<DeviceAccessInfo>(`/devices/${deviceId}/access-info`),
  refreshCatalog: (deviceId: string) => post<void>(`/devices/${deviceId}/catalog/refresh`),
  catalogProgress: (deviceId: string) => get<SyncProgress>(`/devices/${deviceId}/catalog/progress`),
  channels: (params: { deviceId?: number; query?: string; status?: number; ptz?: number; page: number; count: number }) =>
    get<PageResult<GbChannel>>('/channels', params),
  /** 通道树（按 parentId 组织, 用于录像检索页的通道选择） */
  channelTree: (deviceId?: number) =>
    get<ChannelTreeNode[]>('/channels/tree', deviceId ? { deviceId } : undefined),
  /**
   * 修改通道（B3: customName + 云台开关; M5-B2: + 云台类型人工补录）。
   * 后端 ptzEnabled 是显式 set（null 也会落库=恢复按 ptzType 自动）,
   * 因此改名场景必须把当前 ptzEnabled 原值一起传回, 只传 customName 会把开关重置为自动。
   * ptzType=null 不改; 0~5 显式设置（LiveGBS 类平台目录不报 PTZType, 球机类型靠人工补录出摇杆）。
   */
  updateChannel: (
    channelDbId: number,
    data: { customName?: string; ptzEnabled?: number | null; ptzType?: number }
  ) => put<void>(`/channels/${channelDbId}`, data),
  /** 抓图能力开关（B7 阶段3）: 抽屉打开时查一次, enabled=false 则整列不渲染 */
  snapshotConfig: () => get<SnapshotConfig>('/channels/snapshot-config'),
  /**
   * 手动抓图（B7）: 复用当前流; 空闲通道需后端开 idle-enabled, 否则业务码 1014。
   * timeout 覆写 28s——后端最坏 = 空闲建流 8s + 抓帧预算 15s = 23s, 默认 15s 会先掐断。
   */
  captureSnapshot: (channelDbId: number) =>
    post<ChannelSnapshot>(`/channels/${channelDbId}/snapshot`, undefined, { timeout: 28000 })
}
