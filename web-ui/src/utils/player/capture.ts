/**
 * 播放画面截图（公共能力, 各播放器后端通用）:
 * 截取 video 当前帧到 canvas, 以 PNG 下载到浏览器默认下载目录。
 * 三个后端（mpegts/hls 走 MSE, rtc 走 MediaStream）视频帧均不经跨域 <img> 注入,
 * canvas 不会被污染, toBlob 可正常导出。
 */

/** 文件名消毒: 去掉文件系统/下载非法字符（jsb-backend 软解截图复用同一命名规则） */
export function safeName(name: string): string {
  return name.replace(/[\\/:*?"<>|]/g, '_').slice(0, 80)
}

/** 本地时间戳后缀: yyyyMMdd-HHmmss */
export function stamp(): string {
  const d = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  return (
    `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}` +
    `-${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`
  )
}

export interface CaptureResult {
  ok: boolean
  /** 失败原因（ok=false 时有值）: not-ready=无帧可截 / blocked=canvas 被污染(理论上不走该路径) */
  reason?: 'not-ready' | 'blocked'
}

/**
 * 截取 video 当前帧并触发浏览器下载。
 * @param video 播放中的 video 元素
 * @param title 画面标题（通道/回放名）, 作为下载文件名主体
 */
export async function captureFrame(video: HTMLVideoElement, title: string): Promise<CaptureResult> {
  const w = video.videoWidth
  const h = video.videoHeight
  if (!w || !h) {
    return { ok: false, reason: 'not-ready' }
  }
  const canvas = document.createElement('canvas')
  canvas.width = w
  canvas.height = h
  const ctx = canvas.getContext('2d')
  if (!ctx) {
    return { ok: false, reason: 'blocked' }
  }
  ctx.drawImage(video, 0, 0, w, h)
  let blob: Blob | null = null
  try {
    blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/png'))
  } catch {
    return { ok: false, reason: 'blocked' }
  }
  if (!blob) {
    return { ok: false, reason: 'blocked' }
  }
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `${safeName(title)}_${stamp()}.png`
  a.click()
  // 留足下载发起时间后释放, 避免大 blob 常驻内存
  window.setTimeout(() => URL.revokeObjectURL(url), 5000)
  return { ok: true }
}
