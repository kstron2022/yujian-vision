import mpegts from 'mpegts.js'
import type { PlayerBackend, PlayerStartOpts } from './types'

/** MPEG-TS/FLV 后端（D9 主力路径: WS-FLV / HTTP-FLV / HTTP-TS, 含 HEVC-MSE 硬解, B5） */
export class MpegtsBackend implements PlayerBackend {
  private inner: ReturnType<typeof mpegts.createPlayer> | null = null

  constructor(private container: 'flv' | 'mpegts') {}

  start(video: HTMLVideoElement, url: string, opts: PlayerStartOpts) {
    this.inner = mpegts.createPlayer(
      { type: this.container, isLive: true, url, cors: true },
      // liveSync: 以微调播放速率方式追延迟(平滑); 原 liveBufferLatencyChasing 按缓冲块丢帧,
      // 画面呈 GOP 粒度跳变(OSD 时间 2~4 秒一跳)
      { enableWorker: false, liveSync: opts.liveSync ?? true }
    )
    this.inner.attachMediaElement(video)
    this.inner.on(mpegts.Events.ERROR, (type: unknown, detail: unknown) => {
      // 回填原始 (type, detail): PlayerAdapter 的软解兜底按 detail=MediaMSEError 判定, 不解析文案
      const info = { type: String(type), detail: String(detail) }
      opts.onError?.(`播放失败(${info.type}: ${info.detail})`, info)
    })
    this.inner.load()
    void this.inner.play()
  }

  destroy() {
    const p = this.inner
    if (!p) return
    this.inner = null
    try {
      p.pause()
      p.unload()
      p.detachMediaElement()
      p.destroy()
    } catch {
      /* 已销毁场景忽略 */
    }
  }
}
