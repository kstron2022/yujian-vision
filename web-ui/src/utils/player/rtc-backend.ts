import type { PlayerBackend, PlayerStartOpts } from './types'

/**
 * WebRTC 后端（B5, ZLM 内置 SDP 交换 API: POST offer → JSON answer）。
 * 同网段直连用 host candidates 即可, 不配 STUN/TURN; 交换地址携带 sign/expire,
 * 走 on_play 同一防盗链校验。仅安全上下文可用（当前口径: localhost 验证, 242 暂不上 HTTPS）。
 */
export class RtcBackend implements PlayerBackend {
  private pc: RTCPeerConnection | null = null
  private video: HTMLVideoElement | null = null
  /** destroy 置位: 交换请求迟到失败时不再回调 onError, 防误杀接任的新播放器 */
  private destroyed = false

  async start(video: HTMLVideoElement, url: string, opts: PlayerStartOpts) {
    if (typeof RTCPeerConnection === 'undefined') {
      opts.onError?.('浏览器不支持 WebRTC')
      return
    }
    this.video = video
    const pc = new RTCPeerConnection()
    this.pc = pc
    // 只收不发: ZLM 侧按 offer 的 recvonly 协商收流转发
    pc.addTransceiver('video', { direction: 'recvonly' })
    pc.addTransceiver('audio', { direction: 'recvonly' })
    pc.ontrack = (e) => {
      video.srcObject = e.streams[0] ?? new MediaStream([e.track])
      void video.play().catch(() => {})
    }
    pc.onconnectionstatechange = () => {
      if (pc.connectionState === 'failed') {
        opts.onError?.('WebRTC 连接失败')
      }
    }
    try {
      const offer = await pc.createOffer()
      await pc.setLocalDescription(offer)
      const res = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'text/plain;charset=utf-8' },
        body: pc.localDescription?.sdp ?? ''
      })
      const ans = (await res.json()) as { code: number; msg?: string; sdp?: string; type?: string }
      if (ans.code !== 0 || !ans.sdp) {
        opts.onError?.(`WebRTC 交换失败(code=${ans.code}${ans.msg ? `: ${ans.msg}` : ''})`)
        return
      }
      if (this.pc !== pc) return // destroy 已发生, 丢弃迟到应答
      await pc.setRemoteDescription({ type: 'answer', sdp: ans.sdp })
    } catch (e) {
      if (!this.destroyed) {
        opts.onError?.(`WebRTC 播放失败: ${(e as Error).message}`)
      }
    }
  }

  destroy() {
    this.destroyed = true
    this.pc?.close()
    this.pc = null
    if (this.video) {
      this.video.srcObject = null
      this.video = null
    }
  }
}
