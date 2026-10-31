import { get } from '@/api/http'

/**
 * 运维监控（docs/09 M2 #8, B4）。内存快照直出 —— 平台不落库,
 * 历史曲线/告警阈值走旁路 Prometheus + Grafana。
 */

export interface MetricsNode {
  serverId: string | null
  /** 节点状态（'online'/'offline'，兼容数字 1/0） */
  status?: string | number | null
  /** ZLM HTTP API 探活可达 */
  reachable: boolean
  streams: number
  /** RTP 端口段容量（portUsage.capacity 平铺） */
  capacity: number
  viewers: number
  bandwidthBytesPerSec: number
  ssrcAllocated: number
  /** 端口占用率 0~1 小数 */
  portRatio: number
}

export interface MetricsOverview {
  collectTime: string | null
  sessions: { INIT: number; INVITING: number; STREAMING: number; CLOSING: number }
  devices: { online: number; total: number }
  nodes: MetricsNode[]
}

export const metricsApi = {
  overview: () => get<MetricsOverview>('/metrics/overview')
}
