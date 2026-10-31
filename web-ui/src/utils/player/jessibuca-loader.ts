/**
 * jessibuca 动态加载器（H.265 无 HEVC 硬解时的 WASM 软解兜底）:
 * 硬解机器不触碰此路径——只有 PlayerAdapter 按 shouldSoftDecode 命中分发 JsbBackend 后才注入 <script>。
 * 产物为 UMD（public/jessibuca/, 版本 v3.3.28）: 注意全局挂载名是【小写 window.jessibuca】;
 * decoder.js 与 decoder.wasm 必须同目录, 且以绝对路径引用（路由 history 模式下相对路径会 404）。
 */

/** 运行时实例面（官方 jessibuca.d.ts 的子集, 仅列本工程用到的方法） */
export interface JessibucaInstance {
  play(url?: string, options?: Record<string, unknown>): Promise<void> | void
  pause(): Promise<void>
  resize(): void
  setVolume(volume: number): void
  /** type='base64' 时返回 base64（可能带 dataURL 前缀）, 'download' 时直接触发下载 */
  screenshot(filename?: string, format?: string, quality?: number, type?: string): string | undefined
  /** 实为 async（v3.3.28: 内部 await player.destroy() 关 muxer/worker 后才清容器标记与 DOM） */
  destroy(): void | Promise<void>
  on(event: string, callback: (arg?: unknown) => void): void
  off(event: string, callback?: (arg?: unknown) => void): void
}

export type JessibucaCtor = new (config: {
  container: HTMLElement
  /** decoder.js 绝对路径（decoder.wasm 同目录自动加载） */
  decoder: string
  /** 最大缓冲秒数, 播放器自动消除延迟（秒; 内部 ×1000。必须小于 heartTimeout, 否则构造抛错） */
  videoBuffer?: number
  /** 追帧宽限秒数（秒; 内部 ×1000）: 与 videoBuffer 之和 = 触发追帧的延迟阈值 */
  videoBufferDelay?: number
  /** true=等比缩放留黑边（对齐现有 video object-fit:contain 视觉） */
  isResize?: boolean
  /** issue #152: 宽/2 不被 4 整除的分辨率（安防常见）WebGL 取址绿屏防护 */
  openWebglAlignment?: boolean
  timeout?: number
  heartTimeout?: number
  loadingTimeout?: number
  hotKey?: boolean
  showBandwidth?: boolean
  operateBtns?: Record<string, boolean>
}) => JessibucaInstance

declare global {
  interface Window {
    jessibuca?: JessibucaCtor
  }
}

const SCRIPT_URL = '/jessibuca/jessibuca.js'

let loadPromise: Promise<JessibucaCtor> | null = null

/** 单例加载: 全局已存在直接短路; 加载失败重置单例, 允许下次起播重试 */
export function loadJessibuca(): Promise<JessibucaCtor> {
  if (window.jessibuca) return Promise.resolve(window.jessibuca)
  if (loadPromise) return loadPromise
  loadPromise = new Promise<JessibucaCtor>((resolve, reject) => {
    const s = document.createElement('script')
    s.src = SCRIPT_URL
    s.async = true
    s.onload = () => {
      const ctor = window.jessibuca
      if (ctor) {
        resolve(ctor)
      } else {
        loadPromise = null
        reject(new Error('jessibuca 全局对象缺失'))
      }
    }
    s.onerror = () => {
      s.remove()
      loadPromise = null
      reject(new Error('jessibuca.js 加载失败'))
    }
    document.head.appendChild(s)
  })
  return loadPromise
}
