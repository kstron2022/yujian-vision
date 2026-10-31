/**
 * 对讲音频推送器（M3-B5 平台直推）:
 * getUserMedia → AudioContext(8kHz) → A 律编码 → WS 二进制(20ms/160B) → 平台 RTP 直发设备。
 * 平台侧 onAudioFrame 打包 pt=8 裸 G.711A（docs/03 §5.x 平台直推设计）。
 */
import { useAuthStore } from '@/stores/auth'

const SAMPLE_RATE = 8000
const CHUNK_MS = 20

/** G.711 A 律编码（Sun Microsystems 参考实现的 TS 移植, 16bit PCM → 8bit A-law） */
const SEG_AEND: readonly number[] = [0x1f, 0x3f, 0x7f, 0xff, 0x1ff, 0x3ff, 0x7ff, 0xfff]

function linearToAlaw(pcmVal: number): number {
  let pcm = pcmVal >> 3; // 16bit → 13bit 量化域
  let aval: number;
  if (pcm >= 0) {
    aval = 0xd5;
  } else {
    aval = 0x55;
    pcm = -pcm - 1;
  }
  let seg = 8;
  for (let i = 0; i < 8; i++) {
    const end = SEG_AEND[i] ?? 0xfff;
    if (pcm <= end) {
      seg = i;
      break;
    }
  }
  if (seg >= 8) return 0x7f;
  return aval | (seg << 4) | ((pcm >> (seg + 3)) & 0xf);
}

export function encodePcm16ToAlaw(samples: Int16Array): Uint8Array {
  const out = new Uint8Array(samples.length);
  for (let i = 0; i < samples.length; i++) {
    out[i] = linearToAlaw(samples[i] ?? 0);
  }
  return out;
}

export type TalkPushState = 'connecting' | 'talking' | 'closed';

export class TalkPusher {
  private ws: WebSocket | null = null;
  private ctx: AudioContext | null = null;
  private processor: ScriptProcessorNode | null = null;
  private source: MediaStreamAudioSourceNode | null = null;
  private stream: MediaStream | null = null;
  private readonly onOpen?: () => void;
  private readonly onClose?: () => void;

  constructor(
    private readonly relPushUrl: string,
    private readonly onStateChange?: (state: TalkPushState) => void,
    hooks?: { onOpen?: () => void; onClose?: () => void },
  ) {
    this.onOpen = hooks?.onOpen;
    this.onClose = hooks?.onClose;
  }

  /** 采集麦克风并连 WS 推流。返回 false = 麦克风不可用/被拒绝 */
  async start(): Promise<boolean> {
    this.onStateChange?.('connecting');
    let stream: MediaStream;
    try {
      stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          channelCount: 1,
          sampleRate: SAMPLE_RATE,
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
        },
      });
    } catch {
      this.onStateChange?.('closed');
      this.onClose?.();
      return false;
    }
    this.stream = stream;
    this.ctx = new AudioContext({ sampleRate: SAMPLE_RATE });
    this.source = this.ctx.createMediaStreamSource(stream);
    this.processor = this.ctx.createScriptProcessor(1024, 1, 1);
    const token = useAuthStore().token;
    const proto = location.protocol === 'https:' ? 'wss' : 'ws';
    const wsUrl = `${proto}://${location.host}${this.relPushUrl}&token=${token}`;
    const ws = new WebSocket(wsUrl);
    ws.binaryType = 'arraybuffer';
    this.ws = ws;
    this.processor.onaudioprocess = (ev) => {
      if (!ws || ws.readyState !== WebSocket.OPEN) return;
      const input = ev.inputBuffer.getChannelData(0);
      const pcm = new Int16Array(input.length);
      for (let i = 0; i < input.length; i++) {
        const s = Math.max(-1, Math.min(1, input[i] ?? 0));
        pcm[i] = s < 0 ? s * 0x8000 : s * 0x7fff;
      }
      const alaw = encodePcm16ToAlaw(pcm);
      ws.send(alaw.buffer as ArrayBuffer);
    };
    this.source.connect(this.processor);
    this.processor.connect(this.ctx.destination); // ScriptProcessor 需挂到图上才驱动
    ws.onopen = () => {
      this.onStateChange?.('talking');
      this.onOpen?.();
    };
    ws.onclose = () => {
      this.onStateChange?.('closed');
      this.onClose?.();
    };
    ws.onerror = () => {
      this.onStateChange?.('closed');
    };
    return true;
  }

  /** 停止采集与 WS（对讲结束/组件卸载时调用, 幂等） */
  stop(): void {
    try {
      this.ws?.close();
    } catch { /* ignore */ }
    try {
      this.processor?.disconnect();
      this.source?.disconnect();
    } catch { /* ignore */ }
    try {
      this.ctx?.close();
    } catch { /* ignore */ }
    this.stream?.getTracks().forEach((t) => t.stop());
    this.ws = null;
    this.processor = null;
    this.source = null;
    this.ctx = null;
    this.stream = null;
  }
}
