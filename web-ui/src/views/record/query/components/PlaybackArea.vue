<script setup lang="ts">
  import { computed, nextTick, onDeactivated, onMounted, onUnmounted, ref, watch } from 'vue';
  import { playbackApi, stopPlaybackKeepalive } from '@/api/playback';
  import type { PlaybackScale } from '@/api/playback';
  import { playApi } from '@/api/play';
  import type { StreamUrls } from '@/api/play';
  import { useAuthStore } from '@/stores/auth';
  import { WsEvent, wsClient } from '@/utils/ws';
  import { PlayerAdapter, captureFrame, prepareVideoElement, shouldSoftDecode, hevcMseSupported } from '@/utils/player';
  import { Message } from '@arco-design/web-vue';
  import { fmtDateTime } from '@/utils/format';

  /**
   * B2 回放播放区: 片段起止时间 → playback/start 邀请 → WS STREAM_READY 推 urls 起播
   * （与实时预览同一套邀请-就绪链路, 复用 LiveFlvPlayer）。
   * 控制条: 暂停/恢复 + 倍速 + 拖动 SEEK + 虚拟时钟（片段起点 + 本地推算进度, 非真实墙钟）。
   * 生命周期: 由检索页 v-if 控制; 切片段(同实例换 props)先 stop 旧流再邀请新片段;
   * 页签切走(本页 keep-alive)→停流并收起回放区; 浏览器卸载走 fetch keepalive 兜底。
   */
  const props = defineProps<{
    channelDbId: number;
    channelName: string;
    /** 片段起止 yyyy-MM-dd HH:mm:ss */
    startTime: string;
    endTime: string;
  }>();

  const emit = defineEmits<{
    (e: 'close'): void;
  }>();

  const auth = useAuthStore();

  /** 邀请/跳转超时(与后端 15s 收敛窗口一致, 镜像 live 页) */
  const INVITE_TIMEOUT = 15000;
  /** INVITING 兜底轮询间隔(镜像 live 页 B2 修复: WS 事件不持久化, 登录瞬间/断线窗口发出即丢) */
  const INVITE_POLL_INTERVAL = 3000;
  /** SEEK 后的流切换宽限: 拖动=平台内部 re-INVITE(GB)/重建代理(ONVIF), 旧流先收 STREAM_CLOSED 属正常过程 */
  const SEEK_GRACE = 12000;
  /** 倍速档位(后端另支持 0.25, 控制条不暴露) */
  const SCALES: PlaybackScale[] = [0.5, 1, 2, 4];

  type PbPhase = 'inviting' | 'playing' | 'paused' | 'ended' | 'error';
  const phase = ref<PbPhase>('inviting');
  /** 回放控制能力定级(M4-B3): 'sip'=GB 全控 / 'seek'=ONVIF 仅拖动; 默认 'sip' 兼容旧后端 */
  const controlMode = ref<'sip' | 'seek' | string>('sip');
  /** 邀请/跳转遮罩文案(UDP 降级 TCP / 跳转中) */
  const hint = ref('');
  const error = ref('');
  const streamId = ref('');
  const posSec = ref(0);
  const scale = ref<PlaybackScale>(1);
  /** 视频编码（H264/H265）: start 响应/WS STREAM_READY 携带; 决定软解兜底分发 */
  const videoCodec = ref('');
  /** jessibuca WASM 软解态: 无 HEVC 硬解自动降级, 工具栏显示徽标 */
  const softDecode = ref(false);
  const videoEl = ref<HTMLVideoElement>();

  let player: PlayerAdapter | null = null;
  let unbindWs: (() => void) | undefined;
  let disposed = false;
  let inviteTimer = 0;
  /** INVITING 兜底轮询定时器(仅一路, 与 inviteTimer 同生命周期) */
  let pollTimer = 0;
  let tickTimer = 0;
  /** 上次推进进度的墙钟时刻(暂停/跳转期间不累计) */
  let lastTickAt = 0;
  /** 最近一次挂载的拉流地址（B5 补投 STREAM_READY 同址时只刷新编码信号, 不重挂播放器） */
  let lastAttachUrl = '';
  /** 最近一次 SEEK 发出时刻(判定 STREAM_CLOSED 是否属于跳转流程) */
  let seekAt = 0;
  /** 最近一次「控制被拒」提示时刻: 设备对暂停/倍速连环回 400 时 3s 节流防刷屏 */
  let ctrlFailTipAt = 0;
  /** P1 自愈已触发标志: 轮询起播路径拿不到编码, H265 流在无硬解机器上 mpegts 必报错——
   *  onError 里强制按 H265 软解重挂一次（每次新邀请复位） */
  let softRetryDone = false;
  /** 进度条拖动中(指针未松开) */
  let seekPressing = false;
  /** pointerdown 落点在轨道(非把手): 点击轨道的 change 在 click 阶段才到(晚于 pointerup),
   *  up 时值还没更新, 不能按旧值发 SEEK——交给 onSeekChange 的停顿防抖处理 */
  let pressOnTrack = false;
  /** 最近一次 SEEK 发起时刻: 拖动松开后的 click 会冒泡到轨道再触发一次同值 change,
   *  500ms 内的重复 SEEK 直接忽略, 防同位置重开两次会话 */
  let lastDoSeekAt = 0;
  /** 拖动起点: SEEK 失败时回退到该位置(设备并未跳转, 本地时钟不应停在拖动值) */
  let seekFrom = 0;
  let seekIdleTimer = 0;
  /** 挂载时的 token 快照: pagehide 时登录态可能已清空, keepalive 停流要用它带 Bearer */
  let tokenAtMount = '';

  // ---------- 时间换算(与检索页同一套规则, 组件内自持避免跨文件耦合) ----------
  const pad = (n: number) => String(n).padStart(2, '0');

  /** "yyyy-MM-dd HH:mm:ss" → 本地毫秒值（非法输入返回 NaN） */
  function parseTime(s: string): number {
    if (!s) return Number.NaN;
    return new Date(s.replace(' ', 'T')).getTime();
  }

  /** 毫秒值 → 当天时刻 HH:mm:ss */
  function fmtClock(t: number): string {
    const d = new Date(t);
    return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
  }

  /** 毫秒值 → "yyyy-MM-dd HH:mm:ss"（SEEK 入参与回放起点换算共用） */
  function fmtAbs(t: number): string {
    const d = new Date(t);
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${fmtClock(t)}`;
  }

  const segStartMs = computed(() => parseTime(props.startTime));
  const segDurSec = computed(() => {
    const st = segStartMs.value;
    const en = parseTime(props.endTime);
    return Number.isFinite(st) && Number.isFinite(en) && en > st ? Math.round((en - st) / 1000) : 0;
  });

  /** 虚拟时钟: 片段起点 + 已播秒 → HH:mm:ss（回放时间轴语义, 不是真实时间） */
  const clockText = computed(() =>
    Number.isFinite(segStartMs.value) ? fmtClock(segStartMs.value + posSec.value * 1000) : '--:--:--'
  );

  /** 拖动进度条时气泡里的时刻 */
  const fmtTooltip = (v: number | [number, number]) =>
    Number.isFinite(segStartMs.value)
      ? fmtClock(segStartMs.value + (Array.isArray(v) ? v[1] : v) * 1000)
      : '';

  // ---------- 邀请 / 起播 ----------
  async function start(fromSec = 0) {
    // 同一时刻仅一路: 邀请前先停掉旧流(切片段/重试入口), 防两路并存
    await shutdown();
    if (disposed) return;
    phase.value = 'inviting';
    hint.value = '';
    error.value = '';
    softDecode.value = false;
    softRetryDone = false;
    const dur = segDurSec.value;
    posSec.value = dur > 0 ? Math.min(Math.max(0, fromSec), dur - 1) : 0;
    lastTickAt = Date.now();
    try {
      const r = await playbackApi.start(
        props.channelDbId,
        Number.isFinite(segStartMs.value)
          ? fmtAbs(segStartMs.value + posSec.value * 1000)
          : props.startTime,
        props.endTime
      );
      // 迟到的响应: 回放区已被关闭 → 立即停掉这次误产生的流
      if (disposed) {
        void playbackApi.stop(r.streamId).catch(() => {});
        return;
      }
      streamId.value = r.streamId;
      // 控制能力定级: ONVIF 通道仅拖动(暂停/倍速入口隐藏), GB/旧后端缺省全控
      controlMode.value = r.controlMode || 'sip';
      // 编码信号(B5 契约回放同口径): H265 且无硬解时 attach 内部走软解分发
      videoCodec.value = r.videoCodec || '';
      if (r.status === 'STREAMING' && (r.urls?.wsFlv || r.urls?.flv)) {
        attach(r.urls);
      } else {
        armInviteTimeout();
        armPolling();
      }
    } catch (e) {
      if (disposed) return;
      phase.value = 'error';
      error.value = (e as Error).message || '回放邀请失败';
    }
  }

  function armInviteTimeout() {
    disarmInviteTimeout();
    inviteTimer = window.setTimeout(() => {
      if (phase.value === 'inviting') {
        phase.value = 'error';
        error.value = seekAt ? '跳转超时（设备/媒体节点无响应）' : '起播超时（设备/媒体节点无响应）';
      }
    }, INVITE_TIMEOUT);
  }

  function disarmInviteTimeout() {
    if (inviteTimer) {
      window.clearTimeout(inviteTimer);
      inviteTimer = 0;
    }
  }

  /**
   * B2 同款兜底轮询(live 页): WS STREAM_READY 不持久化, 登录瞬间/断线窗口发出即丢——
   * INVITING 期间每 3s 主动探测 /play/{sid}/urls, 就绪即起播（与事件路径幂等竞争）。
   * 权限依赖: 该端点要求 device:play（默认三角色均有）; 自定义角色仅授 record:play 时
   * 轮询恒 403 静默, 起播退化为 WS 事件主路径（评审 B3-P3-5 记录）。
   */
  function armPolling() {
    disarmPolling();
    pollTimer = window.setInterval(async () => {
      if (!streamId.value || phase.value !== 'inviting') {
        disarmPolling();
        return;
      }
      try {
        const r = await playApi.urls(streamId.value);
        if (r.urls?.wsFlv || r.urls?.flv) {
          disarmPolling();
          attach(r.urls);
        }
      } catch {
        /* 1004 未就绪, 继续等 */
      }
    }, INVITE_POLL_INTERVAL);
  }

  function disarmPolling() {
    if (pollTimer) {
      window.clearInterval(pollTimer);
      pollTimer = 0;
    }
  }

  /** 拿到拉流地址(首次 STREAM_READY 或轮询/跳转后的新流)→ 销毁旧播放器重挂。
   *  codecOverride: P1 自愈时强制按 H265 重挂（正常路径读 videoCodec ref） */
  function attach(urls: StreamUrls, codecOverride?: string) {
    disarmInviteTimeout();
    disarmPolling();
    seekAt = 0; // 新流已挂上, 清 SEEK 痕迹(防后续起播超时误显「跳转超时」文案)
    const url = urls.wsFlv || urls.flv || '';
    if (!url) {
      phase.value = 'error';
      error.value = '无可播放地址';
      return;
    }
    const codec = codecOverride ?? videoCodec.value;
    // 软解兜底: H265 且无 HEVC 硬解 → PlayerAdapter 内部分发 jessibuca(倍速/暂停仍是 REST
    // 设备侧变速停推, isLive 到达即播自动跟随, 前端控制面不变)
    softDecode.value = shouldSoftDecode(codec, 'flv');
    player?.destroy();
    player = null;
    lastAttachUrl = url;
    phase.value = 'playing';
    hint.value = '';
    lastTickAt = Date.now();
    void nextTick(() => {
      const video = videoEl.value;
      if (!video) {
        phase.value = 'error';
        error.value = '播放器未就绪';
        return;
      }
      prepareVideoElement(video);
      const p = new PlayerAdapter();
      player = p;
      // liveSync=false: 回放不做追帧变速, 避免虚拟时钟与画面漂移(B2)
      p.start(video, 'flv', url, {
        liveSync: false,
        codec,
        // HEVC-MSE 假阳性兜底: mpegts 失败自动降级软解重挂, 徽标同步点亮
        onSoftDecode: () => {
          softDecode.value = true;
        },
        onError: (msg) => {
          // P1 自愈: WS 丢失→轮询起播的路径编码未知(/play/{sid}/urls 不带 videoCodec),
          // H265 流在无硬解机器上 mpegts 报错——无硬解时强制按 H265 软解重挂一次
          if (
            (phase.value === 'playing' || phase.value === 'paused') &&
            !hevcMseSupported() &&
            !codec &&
            !softRetryDone
          ) {
            softRetryDone = true;
            attach(urls, 'H265'); // attach 内部先销毁旧播放器
            return;
          }
          if (phase.value === 'playing' || phase.value === 'paused') {
            phase.value = 'error';
            error.value = msg;
          }
        }
      });
    });
  }

  function destroyPlayer() {
    player?.destroy();
    player = null;
  }

  /** 停流收尾: 销毁播放器 + 停推进 + 停定时器 + 释放服务端会话(失败静默, 拦截器已提示) */
  async function shutdown() {
    destroyPlayer();
    disarmInviteTimeout();
    disarmPolling();
    const id = streamId.value;
    streamId.value = '';
    if (id) await playbackApi.stop(id).catch(() => {});
  }

  // ---------- 进度推算 ----------
  /** 虚拟时钟本地推进: 上次已知位置 + 经过墙钟 × 当前倍速(1s tick), SEEK/PAUSE 后由各入口校正 */
  function startTick() {
    stopTick();
    tickTimer = window.setInterval(() => {
      if (phase.value !== 'playing') {
        lastTickAt = Date.now(); // 暂停/邀请期间不累计, 恢复时不跳变
        return;
      }
      const now = Date.now();
      posSec.value = Math.min(
        segDurSec.value,
        posSec.value + ((now - lastTickAt) / 1000) * scale.value
      );
      lastTickAt = now;
    }, 1000);
  }

  function stopTick() {
    if (tickTimer) {
      window.clearInterval(tickTimer);
      tickTimer = 0;
    }
  }

  // ---------- 控制条 ----------
  async function togglePause() {
    if (phase.value === 'playing') {
      phase.value = 'paused'; // 乐观更新, 失败回退
      try {
        await playbackApi.control({ streamId: streamId.value, cmd: 'PAUSE' });
      } catch {
        phase.value = 'playing';
      }
    } else if (phase.value === 'paused') {
      phase.value = 'playing';
      try {
        await playbackApi.control({ streamId: streamId.value, cmd: 'RESUME' });
      } catch {
        phase.value = 'paused';
      }
    }
  }

  /** 截图: 当前帧保存 PNG 到浏览器默认下载目录 */
  async function snapshot() {
    if (!videoEl.value) return;
    // 软解时画面在 jessibuca canvas(video 元素无帧), 走 adapter 入口自动分派
    const r = await (player?.capture(`回放_${props.channelName || '视频'}`) ??
      captureFrame(videoEl.value, `回放_${props.channelName || '视频'}`));
    if (r.ok) {
      Message.success('截图已保存到浏览器下载目录');
    } else if (r.reason === 'not-ready') {
      Message.warning('视频尚未出画, 暂无法截图');
    } else {
      Message.error('截图失败');
    }
  }

  // ---------- 全屏(播放器+工具栏整体, 控制栏在全屏态固定底部) ----------
  const shellEl = ref<HTMLElement>();
  const isFullscreen = ref(false);

  function toggleFullscreen() {
    const el = shellEl.value;
    if (!el) return;
    if (document.fullscreenElement) {
      void document.exitFullscreen();
    } else {
      void el.requestFullscreen();
    }
  }

  function onFullscreenChange() {
    isFullscreen.value = document.fullscreenElement === shellEl.value;
  }

  async function onScaleChange(v: unknown) {
    const val = Number(v) as PlaybackScale;
    if (!SCALES.includes(val)) return;
    const prev = scale.value;
    scale.value = val; // 本地时钟随新倍速推进, 与画面保持一致
    // 设备倍速要求会话 STREAMING, 暂停态选择器已禁用, 这里只兜底不发
    if (phase.value !== 'playing' || !streamId.value) return;
    try {
      await playbackApi.control({ streamId: streamId.value, cmd: 'SCALE', scale: val });
    } catch {
      scale.value = prev; // 失败回退(错误提示由 http 拦截器弹出)
    }
  }

  /**
   * 进度条: change 事件拖动中连续触发, 这里只更新本地预览;
   * 松开指针(window pointerup)或停顿 400ms(键盘/点击轨道)才真正发 SEEK——
   * 拖动在后端是重新 INVITE, 不宜拖一次发一次。
   */
  function onSeekPress(e: PointerEvent) {
    seekPressing = true;
    seekFrom = posSec.value;
    pressOnTrack = !(e.target as HTMLElement).classList.contains('arco-slider-btn');
  }

  function onSeekChange(v: unknown) {
    const val = Number(v);
    if (!Number.isFinite(val)) return;
    posSec.value = val;
    window.clearTimeout(seekIdleTimer);
    seekIdleTimer = window.setTimeout(() => {
      if (!seekPressing) void doSeek();
    }, 400);
  }

  function onPointerUp() {
    if (!seekPressing) return;
    seekPressing = false;
    window.clearTimeout(seekIdleTimer);
    seekIdleTimer = 0;
    // 轨道点击: change 在 click 阶段才带着新位置到达, 这里不能按旧值发 SEEK
    if (pressOnTrack) {
      pressOnTrack = false;
      return;
    }
    void doSeek();
  }

  async function doSeek() {
    if (!streamId.value || phase.value !== 'playing') return;
    // 拖动松开后的 click 会冒泡到轨道再触发一次同值 change(onSeekChange 400ms 后到达),
    // 500ms 内的重复 SEEK 直接忽略, 防同位置重开两次会话
    if (Date.now() - lastDoSeekAt < 500) return;
    lastDoSeekAt = Date.now();
    // ONVIF 拉流代理(controlMode='seek', docs/13 §5.3): SEEK=平台侧重建代理, 平台闭环可靠, 保持原路径
    if (controlMode.value === 'seek') {
      seekAt = Date.now();
      try {
        await playbackApi.control({
          streamId: streamId.value,
          cmd: 'SEEK',
          seekTime: fmtAbs(segStartMs.value + posSec.value * 1000)
        });
        // 跳转受理: 旧流必关, 统一回邀请态等新流地址
        phase.value = 'inviting';
        hint.value = '正在跳转…';
        armInviteTimeout();
        armPolling();
      } catch {
        // 失败回退(错误提示由 http 拦截器弹出): 设备未跳转, 本地时钟退回拖动前位置
        posSec.value = seekFrom;
        lastTickAt = Date.now();
      }
      return;
    }
    // GB(含级联): 设备/下级平台对对话内 re-INVITE 是「礼貌假接受」(242 真机 pcap 定案:
    // 回 200 但画面不按新窗口起播, 时间不跳)——改重开式: 停掉当前会话+以拖动点为新起点
    // 全新邀请。全新 INVITE 的时间窗设备是真执行的(与起播同一条已验证可靠链路),
    // 代价只是重新建流的几秒等待(re-INVITE 路径同样要重载画面, 并无额外损失)。
    seekAt = Date.now(); // 超时文案区分「跳转超时」; 新流挂上后 attach 清零
    await start(posSec.value);
  }

  // ---------- WS 事件(按本组件 streamId 过滤, 与 live 页互不干扰) ----------
  function onWs(type: string, data: Record<string, unknown>) {
    const sid = data.streamId as string | undefined;
    if (!sid || sid !== streamId.value) return;
    if (type === WsEvent.StreamReady) {
      // 编码信号(轮询路径拿不到, 事件路径补齐): 供软解判定
      const readyCodec = (data.videoCodec as string) || '';
      if (readyCodec) videoCodec.value = readyCodec;
      // 邀请就绪 / 跳转 re-INVITE 换了新地址(同 streamId 复用) → 重挂播放器。
      // B5 迟到补投: playing 态且地址未变(仅编码信号) → 跳过重挂, 避免无谓重缓冲
      if (phase.value === 'playing') {
        const next = (data.urls as StreamUrls) ?? {};
        if ((next.wsFlv || next.flv || '') === lastAttachUrl) {
          // 迟到编码软解复查: 起播时编码未知按 mpegts 挂载, 此刻才知道 H265 且无硬解 → 重挂软解
          if (!softDecode.value && shouldSoftDecode(videoCodec.value, 'flv')) {
            destroyPlayer();
            attach(next);
          }
          return;
        }
      }
      if (phase.value === 'inviting' || phase.value === 'playing' || phase.value === 'error') {
        attach((data.urls as StreamUrls) ?? {});
      }
    } else if (type === WsEvent.StreamClosed) {
      const reason = (data.reason as string) || '';
      destroyPlayer();
      if (phase.value === 'playing' || phase.value === 'paused') {
        // 正常播完: 设备主动 BYE（docs/07 记 finished, B2-a 约定 device_bye, 两者都认）
        if (reason === 'device_bye' || reason === 'finished') {
          posSec.value = segDurSec.value;
          phase.value = 'ended';
        } else if (reason === 'dialog_terminated') {
          // M5-B2: SIP 对话被对端静默终结(真机 LiveGBS 级联回放), 后端已收尾会话——
          // 指引重新回放而不是让用户再点控制连环报错
          phase.value = 'error';
          error.value = '回放会话已结束，请重新开始回放';
        } else if (seekAt && Date.now() - seekAt < SEEK_GRACE) {
          // 拖动 = 重新 INVITE, 旧流先关闭属正常过程 → 回邀请态等新流
          phase.value = 'inviting';
          hint.value = '正在跳转…';
          armInviteTimeout();
        } else {
          phase.value = 'error';
          error.value = `流已关闭（${reason || '未知原因'}）`;
        }
      } else if (phase.value === 'inviting') {
        // SEEK 受理后的旧流关闭属正常过程(GB re-INVITE/ONVIF 重建代理), 宽限窗口内继续等新流;
        // 确定性失败(seek_failed=重建代理失败/no_stream)不等待, 立即出错误态(评审 B3-P3-7)
        const deterministic = reason === 'seek_failed' || reason === 'no_stream';
        if (!deterministic && seekAt && Date.now() - seekAt < SEEK_GRACE) {
          return;
        }
        phase.value = 'error';
        error.value = `回放邀请失败（${reason || '流已关闭'}）`;
      }
    } else if (type === WsEvent.StreamFallback) {
      // UDP 收流超时 → 后端已转 TCP re-INVITE: 保持邀请态续期等待, 不报错
      if (phase.value === 'inviting') {
        hint.value = 'UDP 收流超时，已自动切换 TCP 重新邀请…';
        armInviteTimeout();
      }
    } else if (type === WsEvent.PlaybackControlFailed) {
      // M5-B2: 回放控制 INFO 被设备拒绝(400 等, 真机 LiveGBS 级联 pcap 定案=设备不支持
      // MANSRTSP 对话内控制)——流不受影响继续播, 撤销乐观状态让按钮保持原样, 只弹警告
      if (phase.value === 'playing' || phase.value === 'paused') {
        // PAUSE 被拒: 撤销乐观暂停(「恢复」按钮文字/倍速禁用随之还原)
        if (phase.value === 'paused') phase.value = 'playing';
        // SCALE 被拒: 倍速回 1x, 防本地虚拟时钟按新倍速推进与画面漂移
        if (scale.value !== 1) scale.value = 1;
        const now = Date.now();
        if (now - ctrlFailTipAt > 3000) {
          ctrlFailTipAt = now;
          Message.warning('设备拒绝该回放控制指令（设备不支持暂停/倍速）');
        }
      }
    }
  }

  // ---------- 生命周期 ----------
  function onPageHide() {
    // 浏览器卸载兜底: sendBeacon 带不了 Bearer, 用 fetch keepalive（镜像 live 页 D17）
    if (streamId.value) stopPlaybackKeepalive(streamId.value, tokenAtMount);
  }

  /** 用户点"关闭" / 切片段 / 切页签的统一出口 */
  function close() {
    void shutdown();
    emit('close');
  }

  onMounted(() => {
    tokenAtMount = auth.token;
    unbindWs = wsClient.on(onWs);
    window.addEventListener('pagehide', onPageHide);
    window.addEventListener('pointerup', onPointerUp);
    document.addEventListener('fullscreenchange', onFullscreenChange);
    startTick();
    void start(0);
  });

  // 父级把回放区指到另一片段(同实例换 props): 先停旧流再邀请新片段, 防两路并存
  watch(
    () => [props.startTime, props.endTime, props.channelDbId],
    (nv, ov) => {
      if (ov && (nv[0] !== ov[0] || nv[1] !== ov[1] || nv[2] !== ov[2])) void start(0);
    }
  );

  // 本页 keep-alive 缓存: 切走页签即停流(资源最小化), 并让父级收起回放区
  onDeactivated(() => {
    void shutdown();
    emit('close');
  });

  onUnmounted(() => {
    disposed = true;
    unbindWs?.();
    window.removeEventListener('pagehide', onPageHide);
    window.removeEventListener('pointerup', onPointerUp);
    document.removeEventListener('fullscreenchange', onFullscreenChange);
    window.clearTimeout(seekIdleTimer);
    stopTick();
    void shutdown();
  });
</script>

<template>
  <div ref="shellEl" class="pb-area" :class="{ 'is-fullscreen': isFullscreen }">
    <!-- 信息行: 片段上下文 + 关闭入口 -->
    <div class="pb-head">
      <span class="pb-title">
        回放中：{{ channelName || '未知通道' }} · {{ fmtDateTime(startTime) }} ~ {{ fmtDateTime(endTime) }}
      </span>
      <a-link @click="close">关闭</a-link>
    </div>

    <!-- 播放器: 16:9, 高度不超过 480px(854 = 480 × 16/9) -->
    <div class="pb-player" :data-soft="softDecode ? '1' : ''">
      <video ref="videoEl" class="pb-video" playsinline />

      <div v-if="phase === 'inviting'" class="pb-mask">
        <a-spin :size="20" />
        <span>{{ hint || '正在邀请回放…' }}</span>
      </div>
      <div v-else-if="phase === 'ended'" class="pb-mask">
        <span>片段播放完毕</span>
        <a-link @click="start(0)">重新播放</a-link>
      </div>
      <div v-else-if="phase === 'error'" class="pb-mask">
        <span>{{ error }}</span>
        <a-link @click="start(posSec)">重试</a-link>
      </div>
    </div>

    <!-- 工具栏: 经典播放器风格——画面下方固定深色条(非悬浮), 左播放/截图, 中进度+时钟, 右倍速+全屏。
         controlMode='sip' 全控; 'seek'(ONVIF 拉流代理, docs/13 §5.3 定级)仅截图+拖动 -->
    <div class="pb-ctrl">
      <a-tooltip v-if="controlMode === 'sip'" :content="phase === 'paused' ? '恢复' : '暂停'">
        <a-button
          class="pb-icon-btn"
          size="mini"
          :disabled="phase !== 'playing' && phase !== 'paused'"
          @click="togglePause"
        >
          <template #icon>
            <icon-pause-circle-fill v-if="phase === 'playing'" />
            <icon-play-circle-fill v-else />
          </template>
        </a-button>
      </a-tooltip>

      <a-tooltip content="截图">
        <a-button
          class="pb-icon-btn"
          size="mini"
          :disabled="phase !== 'playing' && phase !== 'paused'"
          @click="snapshot"
        >
          <template #icon>
            <icon-camera />
          </template>
        </a-button>
      </a-tooltip>

      <span
        v-if="softDecode"
        class="pb-soft"
        title="当前设备无 HEVC 硬解，已自动启用 WASM 软解码（延迟与 CPU 占用略增）"
      >软解</span>

      <span class="pb-clock">{{ clockText }}</span>

      <div class="pb-seek" @pointerdown="onSeekPress">
        <a-slider
          :model-value="posSec"
          :min="0"
          :max="Math.max(1, segDurSec)"
          :step="1"
          :disabled="phase !== 'playing'"
          :format-tooltip="fmtTooltip"
          @change="onSeekChange"
        />
      </div>

      <a-dropdown
        v-if="controlMode === 'sip'"
        trigger="click"
        :disabled="phase !== 'playing'"
        @select="onScaleChange"
      >
        <a-button class="pb-icon-btn pb-scale-btn" size="mini" :disabled="phase !== 'playing'">
          {{ scale }}x
          <icon-caret-down />
        </a-button>
        <template #content>
          <a-doption
            v-for="s in SCALES"
            :key="s"
            :value="s"
            :class="{ 'scale-active': s === scale }"
          >
            {{ s }}x
          </a-doption>
        </template>
      </a-dropdown>

      <a-tooltip :content="isFullscreen ? '退出全屏' : '全屏'">
        <a-button class="pb-icon-btn" size="mini" @click="toggleFullscreen">
          <template #icon>
            <icon-fullscreen-exit v-if="isFullscreen" />
            <icon-fullscreen v-else />
          </template>
        </a-button>
      </a-tooltip>
    </div>
  </div>
</template>

<style scoped lang="less">
  .pb-area {
    // 工作台布局中组件位于 rq-stage 顶部, 不再需要旧的分隔线/margin
    margin-top: 0;

    .pb-head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      margin-bottom: 10px;

      .pb-title {
        font-size: 13px;
        font-weight: 500;
        color: var(--color-text-1);
      }
    }
  }

  .pb-player {
    position: relative;
    width: 100%;
    max-width: 854px;
    aspect-ratio: 16 / 9;
    margin: 0 auto;
    overflow: hidden;
    border-radius: 4px 4px 0 0; // 下缘与工具栏衔接
    background: #000;

    .pb-video {
      width: 100%;
      height: 100%;
      object-fit: contain;
    }

    .pb-mask {
      position: absolute;
      inset: 0;
      z-index: 2;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: 8px;
      font-size: 13px;
      color: #fff;
      background: rgba(0, 0, 0, 0.45);
    }
  }

  // 工具栏: 经典播放器深色控制条, 与画面同宽贴合其下方(非悬浮)
  .pb-ctrl {
    display: flex;
    align-items: center;
    gap: 8px;
    width: 100%;
    max-width: 854px;
    margin: 0 auto;
    padding: 6px 10px;
    background: #1d2129;
    border-radius: 0 0 4px 4px;

    .pb-icon-btn {
      flex-shrink: 0;
      color: rgba(255, 255, 255, 0.9);
      background: transparent;
      border: none;

      &:hover {
        color: #fff;
        background: rgba(255, 255, 255, 0.12);
      }

      &:disabled {
        color: rgba(255, 255, 255, 0.35);
        background: transparent;
      }
    }

    .pb-clock {
      flex-shrink: 0;
      min-width: 60px;
      font-size: 12px;
      font-variant-numeric: tabular-nums;
      color: rgba(255, 255, 255, 0.85);
      text-align: center;
    }

    // 软解徽标: H.265 无硬解自动降级 jessibuca WASM 软解（与 live 宫格 cell-soft 同视觉语言）
    .pb-soft {
      flex-shrink: 0;
      padding: 0 4px;
      border: 1px solid rgb(255, 153, 0);
      border-radius: 2px;
      color: rgb(255, 153, 0);
      font-size: 11px;
      line-height: 14px;
      cursor: help;
    }

    .pb-seek {
      flex: 1;
      min-width: 140px;
      padding: 0 4px;
    }

    // 倍速: 自适应宽的深色下拉按钮(与工具栏融为一体, 主流播放器「1x ▾」样式)
    .pb-scale-btn {
      flex-shrink: 0;
      gap: 2px;
      font-size: 12px;
    }
  }

  // 倍速下拉面板当前档高亮(dropdown 面板渲染在 body, 用全局样式)
  :global(.scale-active) {
    color: rgb(var(--arcoblue-6));
    background: rgb(var(--arcoblue-1));
  }

  // 全屏: 画面吃掉剩余高度, 工具栏固定底部, 与播放器同宽铺满
  .pb-area:fullscreen {
    display: flex;
    flex-direction: column;
    width: 100%;
    height: 100%;
    margin: 0;
    padding: 6px;
    border-top: none;
    background: #000;

    .pb-head {
      margin: 0 0 6px;
      padding: 0 6px;
      color: rgba(255, 255, 255, 0.85);

      .pb-title {
        color: rgba(255, 255, 255, 0.85);
      }
    }

    .pb-player {
      flex: 1;
      min-height: 0;
      max-width: none;
      aspect-ratio: auto;
      border-radius: 0;
    }

    .pb-ctrl {
      max-width: none;
      border-radius: 0;
    }
  }
</style>
