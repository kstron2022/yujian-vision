import { MpegtsBackend } from './mpegts-backend'
import { HlsBackend } from './hls-backend'
import { RtcBackend } from './rtc-backend'
import { JsbBackend } from './jsb-backend'
import { captureFrame } from './capture'
import type { CaptureResult } from './capture'
import type { PlayerBackend, PlayerStartOpts, StreamFormat } from './types'

export type { StreamFormat, PlayerStartOpts, PlayerBackend } from './types'
export { captureFrame } from './capture'
export type { CaptureResult } from './capture'

/**
 * 播放器适配层（D8/B5）: 统一 start/destroy/capture, 按 StreamFormat 与编码能力分发到四个后端。
 * - mpegts: WS-FLV / HTTP-FLV / HTTP-TS（主力, 含 HEVC-MSE 硬解）
 * - hls:    hls.js（Safari 原生兜底; 延迟高于 FLV, 适用移动端/兜底场景）
 * - rtc:    WebRTC（首帧最快; 仅安全上下文可用, 当前口径 localhost 验证）
 * - jsb:    jessibuca WASM 软解（H.265 且无 HEVC 硬解时自动兜底, 画面在自有 canvas）
 */
/** mpegts.js ErrorDetails.MEDIA_MSE_ERROR: MSE 层拒绝该流（addSourceBuffer/appendBuffer
 *  失败）, 即解码能力问题而非网络问题——软解兜底的触发判据（枚举值稳定, 非文案匹配） */
const MSE_ERROR_DETAIL = 'MediaMSEError'

export class PlayerAdapter {
  private backend: PlayerBackend | null = null
  private video: HTMLVideoElement | null = null

  start(video: HTMLVideoElement, format: StreamFormat, url: string, opts?: PlayerStartOpts) {
    this.destroy()
    this.video = video
    const o: PlayerStartOpts = opts ?? {}
    if (shouldSoftDecode(o.codec, format)) {
      // H.265 且无 HEVC 硬解 → jessibuca WASM 软解兜底（画面不进 video, 截图走 backend.capture）
      this.backend = new JsbBackend()
    } else {
      switch (format) {
        case 'hls':
          this.backend = new HlsBackend()
          break
        case 'rtc':
          this.backend = new RtcBackend()
          break
        case 'ts':
          this.backend = new MpegtsBackend('mpegts')
          break
        default:
          // wsFlv/flv 同为 FLV 封装（mpegts.js 按 url scheme 自适应 ws/http 传输）
          this.backend = new MpegtsBackend('flv')
      }
      // 运行时软解兜底（假阳性唯一可靠防线）: isTypeSupported 只反映「MIME 串接受度」,
      // 无解码器机器会假阳性通过, 直到 MSE 拿流里的真实编码串调 addSourceBuffer 才暴露
      // （242 实测: 探测 hvc1.1.6.L153.B0=true, 实流 hvc1.1.1.L90.B0 被拒 → MediaMSEError）。
      // 故 FLV 系 mpegts 一律布防（不依赖调用方传 codec, 漏传即失效是本条的历史缺陷）,
      // 命中「H265 已知」或「MSE 拒绝该流」即降级 jessibuca 重挂一次; 仅 FLV 系可降级
      // （jessibuca 不吃 ts/hls）; 降级后再失败按原 onError 报出, 不循环。
      const mpegts = this.backend
      if ((format === 'wsFlv' || format === 'flv') && mpegts instanceof MpegtsBackend) {
        const fallbackOnError = o.onError
        o.onError = (msg, info) => {
          // adapter 已被销毁/重建（快速切流重挂）→ 本次错误属于旧会话, 丢弃
          if (this.backend !== mpegts) {
            fallbackOnError?.(msg, info)
            return
          }
          // 网络类/拉流类错误原样上报（不白跑一次软解）; MSE 拒绝与编码是否已知无关
          if (o.codec !== 'H265' && info?.detail !== MSE_ERROR_DETAIL) {
            fallbackOnError?.(msg, info)
            return
          }
          o.onSoftDecode?.() // 场景侧点亮「软解」徽标
          mpegts.destroy()
          this.backend = new JsbBackend()
          this.backend.start(video, url, { ...o, onError: fallbackOnError })
        }
      }
    }
    this.backend.start(video, url, o)
  }

  destroy() {
    this.backend?.destroy()
    this.backend = null
    this.video = null
  }

  /** 截图统一入口: 软解后端有画面但 video 元素无帧, 由 backend.capture 接管;
   *  其余后端回退 canvas drawImage(video)（调用方仍持有 video 引用） */
  capture(title: string): Promise<CaptureResult> {
    if (this.backend?.capture) return this.backend.capture(title)
    if (!this.video) return Promise.resolve({ ok: false, reason: 'not-ready' })
    return captureFrame(this.video, title)
  }
}

/**
 * HEVC-MSE 硬解能力探测（宽松 OR 口径, 只做初判）。
 * 静态探测本质不可靠: isTypeSupported 只反映「MIME 串接受度」而非解码能力, 且
 * profile×level 组合空间无法穷举（如 Main10 = hvc1.1.2.Lxxx, 任何固定串集都会漏）。
 * 两种误判的代价不对称——假阳性（漏放, 无解码器机器走到 mpegts 失败）由 PlayerAdapter
 * 的运行时兜底承接（H265+FLV 系失败自动降级软解重挂, 与串无关）; 假阴性（真硬解机器
 * 被误送软解）无兜底可救、长期伤主流机器。故此处保持宽松, 主流形态初判准确即可。
 */
const HEVC_PROBE_CODECS = ['hvc1.1.6.L153.B0', 'hev1.1.6.L153.B0']
let hevcProbe: boolean | null = null

export function hevcMseSupported(): boolean {
  if (hevcProbe === null) {
    hevcProbe =
      typeof MediaSource !== 'undefined' &&
      HEVC_PROBE_CODECS.some((c) => MediaSource.isTypeSupported(`video/mp4; codecs="${c}"`))
  }
  return hevcProbe
}

/** WebRTC 可用性: RTCPeerConnection + 安全上下文（http 页面浏览器会拒; localhost 例外） */
export function rtcSupported(): boolean {
  return typeof RTCPeerConnection !== 'undefined' && window.isSecureContext
}

/** Safari 原生 HLS 可用（不走 MSE, HEVC 可解性随系统）: H265 门网豁免该路径 */
export function nativeHlsSupported(): boolean {
  if (typeof document === 'undefined') return false
  return !!document.createElement('video').canPlayType('application/vnd.apple.mpegurl')
}

/**
 * H.265 软解兜底判据: 无 HEVC-MSE 硬解时, MSE 系格式全不可解 → PlayerAdapter 自动分发
 * jessibuca WASM 软解后端（此前语义为「拦截报错」, 软解兜底落地后改为「自动降级」）。
 * RTC 不经 MSE、Safari 原生 HLS 交给系统解码, 这两条路径保留原样不软解。
 * （原 hevcBlocked 门控判据原样沿用; live 页/设备页弹窗/回放区同口径, 判据只此一处）
 */
export function shouldSoftDecode(codec: string | undefined, format: StreamFormat): boolean {
  return (
    codec === 'H265' &&
    format !== 'rtc' &&
    !hevcMseSupported() &&
    !(format === 'hls' && nativeHlsSupported())
  )
}

/** 静音策略: 浏览器自动播放策略要求 muted 起播 */
export function prepareVideoElement(video: HTMLVideoElement) {
  video.muted = true
  video.autoplay = true
}
