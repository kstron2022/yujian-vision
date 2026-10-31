/**
 * jessibuca WASM 软解后端（H.265 兜底, 仅 shouldSoftDecode 命中时由 PlayerAdapter 分发）:
 * 画面渲染到向 video.parentElement 注入的独立容器（video 隐藏, 截图走 jessibuca 自带能力）。
 * 三场景画面容器（.cell-screen/.lv-stage/.pb-player）均 position:relative 且高度不依赖
 * video 内容尺寸——注入 div 用 absolute inset:0 不参与布局, 模板零改动。
 * 事件口径对齐 mpegts-backend: 仅 error 触发 onError（首次, 防连发刷屏）;
 * timeout/delayTimeout 显式吞掉（黑洞地址连接挂起不报错, e2e T10/T16 依赖该口径）。
 *
 * 长播自适应（M5-B2 联调: H.265 软解看久了画面 OSD 时间落后, 手动重播即恢复）:
 * 无硬解机器只能单线程 WASM 软解, 解码吞吐低于实时码率时积压越滚越大, 播放器自带追帧
 * （阈值 = videoBuffer + videoBufferDelay）压不住。这里用 jessibuca 每秒一次的 stats 事件
 * 做看门狗（bundle v3.3.27 已核实: buf = worker 实测延迟 ms, ts = 流时间戳; 该秒无渲染帧
 * 时两者归零 = 无数据）, 命中即原地重挂播放器 —— 即"手动重播一下"那一步的自动化
 * （流还在, 不需要重新 INVITE, 黑屏约 0.5~1.5s）。仅直播布防: 回放重挂会跳到流的最新
 * 位置, 破坏暂停/拖拽语义。
 *
 * ⚠️ 重挂的两条硬约束（v3.3.28 真机踩坑, 2026-09-25）:
 *   ① destroy() 必须 await: jessibuca 的 destroy 是 async（内部先 await player.destroy()
 *      关 muxer/worker, 之后才清容器标记与 DOM）。不等它落地就重挂 = 两个 WASM 解码器
 *      同时活着抢 CPU（本机本来就跑不动）; 且旧实例的收尾会去清一个已经换人的 DOM →
 *      removeChild NotFoundError。
 *   ② 容器元素不复用: 构造时在容器上写标记 data-jessibuca（_checkHasCreated 见到即抛
 *      "container has been created and can not be created again"）, 标记要到 destroy
 *      末尾才清 —— 复用 + 不等 = 直接抛错（界面退化成"H.265 软解初始化失败 重试"）。
 *   故结构固定为【持久 wrapper › 每次 mount 新建 stage】: 旧实例的 destroy 只作用于它
 *   自己的 stage, 待其 resolve 后整体移除; 万一 destroy 卡住（2s 超时兜底）也不连累下一次构造。
 */
import { loadJessibuca } from './jessibuca-loader'
import type { JessibucaCtor, JessibucaInstance } from './jessibuca-loader'
import type { CaptureResult } from './capture'
import { safeName, stamp } from './capture'
import type { PlayerBackend, PlayerStartOpts } from './types'

/** jessibuca ERROR 枚举 → 中文（jessibuca.d.ts ERROR） */
const ERROR_TEXT: Record<string, string> = {
  playError: '播放地址无效',
  fetchError: '拉流请求失败',
  websocketError: '拉流连接失败',
  webcodecsH265NotSupport: '硬解不可用',
  mediaSourceH265NotSupport: '硬解不可用',
  wasmDecodeError: '软解失败'
}

const DECODER_URL = '/jessibuca/decoder.js'

/* ---------------- 长播自适应参数（保守起步, 现场按实测调） ---------------- */
/** 看门狗总开关: false = 只保留 jessibuca 原生追帧, 不做原地重挂（画面会重新缓慢漂移） */
const WATCHDOG_ENABLED = true
/** 最大缓冲秒数。注意: 构造参数按【秒】传、内部 ×1000 转 ms, 且 jessibuca 校验
 *  videoBuffer < heartTimeout（否则构造直接抛错）, 改这两个值前先核 bundle 校验 */
const VIDEO_BUFFER = 0.2
/** 追帧宽限秒数: 与 videoBuffer 之和 = 触发追帧的延迟阈值。
 *  2026-09-25 曾收紧到 0.3（阈值 1.2s→0.5s）试图"更早追帧", 242 验收机实测反而
 *  明显跳帧（该机软解吞吐不足, 阈值越低 = 越频繁丢帧到 I 帧）, 已回退默认值 1.0。 */
const VIDEO_BUFFER_DELAY = 1.0
/** 判据①: stats.buf（播放器实测延迟）连续超此值 → 判为积压, 重挂清队列 */
const LAG_MS = 4000
/** 判据②: 画面真实年龄（墙钟 − 流时间戳 − 起播校准偏移）连续超此值 */
const AGE_MS = 5000
/** 起播校准样本数: 取头几个样本 墙钟−流时间戳 的最小值作基准偏移 */
const SKEW_SAMPLES = 3
/** 基准偏移绝对值超此值 → 流时间戳非 1970 基准毫秒（或本机时钟偏离节点）, 判据②自弃 */
const SKEW_TRUST_MS = 120000
/** 连续坏样本数（stats 约每秒一次）达到才动作, 滤毛刺 */
const BAD_SAMPLES = 5
/** 长播兜底: 连续播放超此时长, 且在【测不出画面新鲜度】时无条件重挂一次 */
const RESYNC_MS = 10 * 60 * 1000
/** 两次重挂最小间隔。稳态下重挂频率由它封顶: 吞吐不足的机器 = 每 2 分钟闪一次
 *  （0.5~1.5s 黑屏）, 换来 OSD 时间不再无界漂移。嫌闪得勤就调大它。 */
const RESYNC_GAP_MS = 120 * 1000
/** 单次起播生命周期内重挂次数上限, 超出停手只告警（两个小时的 2 分钟一次） */
const RESYNC_MAX = 60

export class JsbBackend implements PlayerBackend {
  private inner: JessibucaInstance | null = null
  /** 持久外壳（absolute inset:0, 承载所有 stage; 跨重挂保留） */
  private box: HTMLDivElement | null = null
  /** 当前播放器自己的容器元素 —— 每次 mount 换新（见文件头 ⚠️）, 重挂时整体丢弃 */
  private stage: HTMLDivElement | null = null
  private video: HTMLVideoElement | null = null
  private destroyed = false
  private emitted = false
  /** 重挂进行中（旧实例 destroy 是 async, 期间到来的 stats 样本直接忽略） */
  private resyncing = false
  /* 重挂上下文（mount 共用, 自适应重挂不重建外壳与 window 监听） */
  private ctor: JessibucaCtor | null = null
  private playOpts: PlayerStartOpts | null = null
  private url = ''
  private listening = false
  /* 看门狗状态 */
  private startedAt = 0
  private lagBad = 0
  private ageBad = 0
  private skewSamples = 0
  private skew = 0
  private skewReady = false
  private skewTrusted = false
  private resyncAt = 0
  private resyncCount = 0
  private lastBuf = 0
  private lastAge = 0
  private lastFps = 0

  /** 全屏切换/窗口缩放后让 canvas 跟随容器尺寸（jessibuca 内部监听 window resize,
   *  但 fullscreenchange 多数不触发 resize, 显式补一刀） */
  private readonly onRealign = () => {
    try {
      this.inner?.resize()
    } catch {
      /* 个别版本无 resize 方法, 忽略 */
    }
  }

  start(video: HTMLVideoElement, url: string, opts: PlayerStartOpts): void {
    void this.doStart(video, url, opts)
  }

  private async doStart(video: HTMLVideoElement, url: string, opts: PlayerStartOpts): Promise<void> {
    let ctor: JessibucaCtor
    try {
      ctor = await loadJessibuca()
    } catch {
      if (!this.destroyed) opts.onError?.('软解组件加载失败（jessibuca 资源不可达）')
      return
    }
    if (this.destroyed) return // 加载窗口期被销毁（快速切流/关窗）, 不再实例化
    const parent = video.parentElement
    if (!parent) {
      opts.onError?.('软解容器缺失')
      return
    }
    // 定位防御: 容器非 positioned 时补 relative, 否则 absolute 注入层会飞出画面区
    if (getComputedStyle(parent).position === 'static') {
      parent.style.position = 'relative'
      parent.style.overflow = 'hidden'
    }
    const box = document.createElement('div')
    box.style.cssText = 'position:absolute;inset:0;z-index:1;background:#000;'
    parent.appendChild(box)
    video.style.display = 'none'
    this.box = box
    this.video = video
    this.ctor = ctor
    this.playOpts = opts
    this.url = url
    this.mount()
  }

  /** 建播放器实例 + 挂事件口径（起播与自适应重挂共用同一份, 避免两处漂移）。
   *  【每次调用都新建 stage 容器】—— jessibuca 禁止同一元素二次构造（见文件头 ⚠️） */
  private mount(): void {
    const ctor = this.ctor
    const box = this.box
    const opts = this.playOpts
    if (!ctor || !box || !opts) return
    const stage = document.createElement('div')
    stage.style.cssText = 'position:absolute;inset:0;'
    box.appendChild(stage)
    let j: JessibucaInstance
    try {
      j = new ctor({
        container: stage,
        decoder: DECODER_URL,
        videoBuffer: VIDEO_BUFFER,
        videoBufferDelay: VIDEO_BUFFER_DELAY,
        isResize: true,
        openWebglAlignment: true,
        timeout: 10,
        heartTimeout: 10,
        loadingTimeout: 10,
        // 自有 UI 已提供全部控制面（三场景工具栏）, 内置操作钮/快捷键/网速显示全关
        hotKey: false,
        showBandwidth: false,
        operateBtns: { fullscreen: false, screenshot: false, play: false, audio: false, record: false }
      })
    } catch (e) {
      stage.remove()
      this.emitted = true
      console.warn('[软解自适应] 播放器实例化失败', e)
      opts.onError?.('H.265 软解初始化失败')
      return
    }
    this.stage = stage
    this.inner = j
    this.resetWatch()
    j.on('error', (arg?: unknown) => {
      if (this.destroyed || this.emitted) return
      this.emitted = true
      const code = typeof arg === 'string' ? arg : ''
      opts.onError?.(`H.265 软解播放失败(${ERROR_TEXT[code] || code || '未知错误'})`)
    })
    // 超时类事件显式吞掉: 连接挂起（e2e 黑洞地址）不等于播放失败, 与 mpegts-backend 口径对齐
    j.on('timeout', () => {})
    j.on('delayTimeout', () => {})
    // 长播自适应只对直播布防（回放 liveSync=false: 重挂会跳到流最新位置, 破坏暂停/拖拽语义）
    if (WATCHDOG_ENABLED && opts.liveSync !== false) j.on('stats', (s?: unknown) => this.onStats(s))
    if (!this.listening) {
      this.listening = true
      window.addEventListener('resize', this.onRealign)
      document.addEventListener('fullscreenchange', this.onRealign)
    }
    const played = j.play(this.url)
    if (played && typeof played.catch === 'function') played.catch(() => {}) // 失败走 error 事件口径
  }

  /* ---------------- 长播自适应（看门狗） ---------------- */

  /** 清看门狗状态: 起播/重挂后重新校准（startedAt 同时是长播兜底计时起点） */
  private resetWatch(): void {
    this.startedAt = Date.now()
    this.lagBad = 0
    this.ageBad = 0
    this.skewSamples = 0
    this.skew = 0
    this.skewReady = false
    this.skewTrusted = false
  }

  /** 流时间戳 → 1970 基准毫秒; 非 1970 基准（相对时间戳/计数器）返回 0 = 判据②不可用 */
  private tsBase(ts: number): number {
    if (ts > 1.6e12) return ts // 毫秒时间戳
    if (ts > 1.6e9) return ts * 1000 // 秒时间戳
    return 0
  }

  /** 画面新鲜度可否判定: 校准可信且最近一次年龄测量在阈值内（能判定的机器上不做定时重挂） */
  private ageKnownFresh(): boolean {
    return this.skewReady && this.skewTrusted && this.lastAge <= AGE_MS
  }

  /** stats 样本（每秒一次）: 两条判据 + 长播兜底, 命中即原地重挂 */
  private onStats(raw?: unknown): void {
    if (this.destroyed || this.emitted || this.resyncing) return
    const st = (raw ?? {}) as { buf?: number; ts?: number; fps?: number }
    const buf = Number(st.buf) || 0
    const ts = Number(st.ts) || 0
    if (buf > 0) {
      this.lastBuf = buf
      // 判据①: 播放器自己看得见的延迟（worker 队列; 追帧阈值 ≈1.2s, 持续超 4s 即异常）
      this.lagBad = buf > LAG_MS ? this.lagBad + 1 : 0
    }
    const fps = Number(st.fps) || 0
    if (fps > 0) this.lastFps = fps
    // 判据②: 画面真实年龄 = 墙钟 − 流时间戳 − 起播基准偏移。覆盖 buf 看不见的积压:
    // 时间戳按"发出时刻"重写时播放器自认无延迟, 而画面内容已经旧了
    const base = ts > 0 ? this.tsBase(ts) : 0
    if (base > 0) {
      const off = Date.now() - base
      if (!this.skewReady) {
        this.skew = this.skewSamples === 0 ? off : Math.min(this.skew, off)
        if (++this.skewSamples >= SKEW_SAMPLES) {
          this.skewReady = true
          this.skewTrusted = Math.abs(this.skew) < SKEW_TRUST_MS
        }
      } else if (this.skewTrusted) {
        this.lastAge = off - this.skew
        this.ageBad = this.lastAge > AGE_MS ? this.ageBad + 1 : 0
      }
    }
    if (this.lagBad >= BAD_SAMPLES) {
      this.resync('延迟看门狗')
    } else if (this.ageBad >= BAD_SAMPLES) {
      this.resync('画面漂移看门狗')
    } else if (Date.now() - this.startedAt > RESYNC_MS && !this.ageKnownFresh()) {
      // 长播兜底: 只在【测不出画面新鲜度】时兜（能测的机器交给上面两条判据, 不做无谓闪断）
      this.resync('长播兜底')
    }
  }

  /** 原地重挂播放器: 连同其连接与缓冲一起换掉 —— 追齐到流的最新位置（= 手动重播那一下） */
  private resync(reason: string): void {
    if (this.destroyed || this.emitted || this.resyncing) return
    const now = Date.now()
    if (now - this.resyncAt < RESYNC_GAP_MS) return // 防抖
    if (this.resyncCount >= RESYNC_MAX) {
      console.warn(`[软解自适应] 重挂次数已达上限 ${RESYNC_MAX}, 停止自愈（${reason}）`)
      return
    }
    this.resyncAt = now
    this.resyncCount++
    console.info(
      `[软解自适应] ${reason} 触发重挂（第 ${this.resyncCount} 次）: ` +
        `buf=${Math.round(this.lastBuf)}ms age=${Math.round(this.lastAge)}ms fps=${Math.round(this.lastFps)}`
    )
    this.resyncing = true
    const old = this.inner
    const oldStage = this.stage
    // 先摘引用: 旧实例销毁途中到来的 stats 不再驱动看门狗
    this.inner = null
    this.stage = null
    const next = () => {
      oldStage?.remove()
      this.resyncing = false
      this.mount() // 新建 stage 重新构造, 绝不复用旧容器元素
    }
    if (!old) {
      next()
      return
    }
    // destroy() 是 async（内部 await player.destroy() 关 muxer/worker 后才清标记与 DOM）:
    // 必须等它落地; 卡住超 2s 则强行继续（晚到的收尾只作用于已摘除的旧 stage, 无害）
    const finished = Promise.resolve()
      .then(() => old.destroy())
      .catch(() => {})
    Promise.race([finished, new Promise((r) => window.setTimeout(r, 2000))]).then(next)
  }

  /** 软解截图: jessibuca 画面在自有 canvas, captureFrame(video) 拿不到帧（videoWidth=0） */
  async capture(title: string): Promise<CaptureResult> {
    const j = this.inner
    if (!j || this.destroyed) return { ok: false, reason: 'not-ready' }
    let b64 = ''
    try {
      b64 = j.screenshot(safeName(title), 'png', 0.92, 'base64') || ''
    } catch {
      return { ok: false, reason: 'not-ready' }
    }
    if (!b64) return { ok: false, reason: 'not-ready' }
    const raw = b64.includes(',') ? b64.slice(b64.indexOf(',') + 1) : b64
    let blob: Blob
    try {
      const resp = await fetch(`data:image/png;base64,${raw}`)
      blob = await resp.blob()
    } catch {
      return { ok: false, reason: 'blocked' }
    }
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${safeName(title)}_${stamp()}.png`
    a.click()
    window.setTimeout(() => URL.revokeObjectURL(url), 5000)
    return { ok: true }
  }

  destroy(): void {
    this.destroyed = true
    if (this.listening) {
      this.listening = false
      window.removeEventListener('resize', this.onRealign)
      document.removeEventListener('fullscreenchange', this.onRealign)
    }
    const old = this.inner
    this.inner = null
    // 容器整体移除即可（旧实例的异步收尾只作用于它自己的 stage, 已随之消失）
    this.stage = null
    this.box?.remove()
    this.box = null
    try {
      const r = old?.destroy()
      if (r && typeof r.catch === 'function') r.catch(() => {})
    } catch {
      /* 已销毁场景忽略（对齐 mpegts-backend destroy 口径） */
    }
    if (this.video) this.video.style.display = ''
    this.video = null
    this.ctor = null
    this.playOpts = null
    this.url = ''
  }
}
