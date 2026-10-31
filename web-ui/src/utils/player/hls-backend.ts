import Hls from 'hls.js'
import type { PlayerBackend, PlayerStartOpts } from './types'

/** HLS 后端（B5: hls.js MSE; Safari 走原生 HLS, 其余浏览器不支持时报错） */
export class HlsBackend implements PlayerBackend {
  private hls: Hls | null = null
  /** Safari 原生路径(直接 video.src), destroy 时清 src */
  private native = false
  private video: HTMLVideoElement | null = null

  start(video: HTMLVideoElement, url: string, opts: PlayerStartOpts) {
    this.video = video
    if (Hls.isSupported()) {
      const hls = new Hls({ enableWorker: true, lowLatencyMode: false })
      this.hls = hls
      hls.on(Hls.Events.ERROR, (_evt, data) => {
        if (!data.fatal) return
        opts.onError?.(`HLS 播放失败(${data.type}: ${data.details})`)
      })
      hls.attachMedia(video)
      hls.loadSource(url)
    } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
      this.native = true
      video.src = url
      void video.play().catch(() => {})
    } else {
      opts.onError?.('浏览器不支持 HLS 播放')
    }
  }

  destroy() {
    if (this.hls) {
      this.hls.destroy()
      this.hls = null
    }
    if (this.native && this.video) {
      this.video.removeAttribute('src')
      this.video.load()
    }
    this.video = null
    this.native = false
  }
}
