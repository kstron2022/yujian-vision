/** 流封装格式（key 与后端 buildUrls 的地址 key 对齐, docs/03 §4.1） */
export type StreamFormat = 'wsFlv' | 'flv' | 'ts' | 'hls' | 'rtc'

import type { CaptureResult } from './capture'

/** 播放器错误原始信息（可选, 仅 mpegts 后端回填）: mpegts.js 的 (type, detail) 枚举。
 *  detail='MediaMSEError' = MSE 层拒绝了这条流（解码能力问题, 典型=无 HEVC 硬解）,
 *  PlayerAdapter 据此判定是否降级软解——不走错误文案解析, 也不依赖调用方传 codec */
export interface PlayerErrorInfo {
  type?: string
  detail?: string
}

/** 播放器启动参数 */
export interface PlayerStartOpts {
  onError?: (msg: string, info?: PlayerErrorInfo) => void
  /** true=直播追帧(微调速率); false=回放固定速率(B2 虚拟时钟语义)。HLS/RTC 后端忽略 */
  liveSync?: boolean
  /** 通道视频编码（H264/H265, start 响应与 WS STREAM_READY 携带）:
   *  H265 且无 HEVC 硬解时 PlayerAdapter 据此分发 jessibuca WASM 软解后端 */
  codec?: string
  /** H265 门控假阳性兜底: mpegts 起播失败自动降级软解重挂时回调（场景侧点亮「软解」徽标）。
   *  仅 FLV 系地址可降级（jessibuca 不吃 ts/hls）; 直接命中软解判据时不回调（场景层已自判） */
  onSoftDecode?: () => void
}

/** 播放器后端 SPI: 各传输格式一个实现 */
export interface PlayerBackend {
  start(video: HTMLVideoElement, url: string, opts: PlayerStartOpts): void
  destroy(): void
  /** 软解类后端画面不进 video 元素（videoWidth 恒 0）, 截图走自带能力;
   *  缺省（未实现）= PlayerAdapter 回退 captureFrame(video) */
  capture?(title: string): Promise<CaptureResult>
}
