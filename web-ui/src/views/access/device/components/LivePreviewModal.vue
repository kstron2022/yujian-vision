<script setup lang="ts">
  import { computed, nextTick, onDeactivated, onUnmounted, ref, watch } from 'vue';
  import { Message } from '@arco-design/web-vue';
  import { errorTextOf } from '@/api/http';
  import { playApi, stopStreamKeepalive } from '@/api/play';
  import type { StreamUrls } from '@/api/play';
  import type { GbChannel } from '@/api/device';
  import { ptzApi } from '@/api/ptz';
  import type { PtzCommand } from '@/api/ptz';
  import PtzJoystick from '@/views/monitor/live/components/PtzJoystick.vue';
  import { useAuthStore } from '@/stores/auth';
  import { copyText } from '@/utils/clipboard';
  import { WsEvent, wsClient } from '@/utils/ws';
  import {
    PlayerAdapter,
    shouldSoftDecode,
    prepareVideoElement,
    rtcSupported
  } from '@/utils/player';
  import type { StreamFormat } from '@/utils/player';

  /**
   * 通道实时播放弹窗（M3-B7 阶段3）: 设备页通道列表行内「播放」→ 弹窗起流。
   *
   * 与实时预览页同一套邀请-就绪链路（play → INVITING 等 WS STREAM_READY → 挂播放器）,
   * 但只维护一路流, 状态收敛成单值而非宫格。关闭即停流（viewer_count 1→0, 不白占设备带宽）。
   * 停流兜底与 live 页一致: 关窗主动 stop, 浏览器卸载用 fetch keepalive（sendBeacon 带不了 Bearer）。
   */
  const props = defineProps<{ visible: boolean; channel: GbChannel | null }>();
  const emit = defineEmits<{ (e: 'update:visible', v: boolean): void }>();

  /** 与后端 15s 收敛窗口一致（镜像 live 页/回放区） */
  const INVITE_TIMEOUT = 15000;
  /** 起播兜底轮询间隔: WS STREAM_READY 事件不持久化(登录瞬间/WS 断线窗口发出即丢),
   *  inviting 期轮询 /play/{sid}/urls 自愈（镜像 live 页） */
  const INVITE_POLL_INTERVAL = 3000;
  /** 本弹窗是否展示 RTC 选项（能力探测一次; http 非安全上下文不可用） */
  const SHOW_RTC = rtcSupported();

  const auth = useAuthStore();

  type Phase = 'inviting' | 'playing' | 'error';
  const phase = ref<Phase>('inviting');
  const error = ref('');
  const hint = ref('');
  const streamId = ref('');
  const videoCodec = ref('');
  const format = ref<StreamFormat>('wsFlv');
  /** jessibuca WASM 软解态: 无 HEVC 硬解自动降级, 控制条显示徽标+禁用传输切换(仅 FLV 可用) */
  const softDecode = ref(false);
  const videoEl = ref<HTMLVideoElement>();

  let player: PlayerAdapter | null = null;
  let unbindWs: (() => void) | undefined;
  let inviteTimer = 0;
  let pollTimer = 0;
  /** 本次会话的地址表（会话期内有效）: 切传输格式靠它重挂, 不必重发点播。
   *  用 ref 而非普通变量——传输格式下拉的可用性靠它判定（H.265 门控报错时要让用户能切到 RTC 自救） */
  const cachedUrls = ref<StreamUrls>({});
  /** 最近一次挂载的地址: B5 迟到补投的 STREAM_READY 同址时只刷新编码, 不重挂播放器 */
  let lastAttachUrl = '';
  /** 弹窗已关闭/组件已卸载: 迟到到达的响应与 WS 事件一律丢弃（并顺手停掉误产生的流） */
  let disposed = false;
  /** 本次邀请的代号: 开窗时 visible 与 channel 在同 tick 变化, 两个 watcher 会各调一次 start(),
   *  不拦就会发两次点播（服务端同一路流记 2 个 viewer, 关窗只停一路 → 流泄漏） */
  let runSeq = 0;
  /** 打开时的 token 快照: 退出登录会先清空 store 再卸载, keepalive 停流要用它带 Bearer */
  let tokenAtOpen = '';
  /** 在途的停流 promise: 关窗立刻重开时, 新一轮 start 必须等它落地——否则后端可能把旧流复用给
   *  新会话, 而迟到的 stop 随即把它杀掉（表现为刚打开就"流已关闭"） */
  let pendingStop: Promise<void> = Promise.resolve();
  /** none_reader 自愈重播（M5-B3, 与宫格同口径）: 30s 窗口内只自愈一次, 用户主动起播重置窗口 */
  let autoRetryAt = 0;
  /** 本轮 start() 是否由自愈触发: 决定提示文案, 并避免自愈自己把窗口重置掉（会变成无限重播） */
  let pendingAutoHint = false;

  const title = computed(() => props.channel?.customName || props.channel?.name || '未知通道');
  const channelNo = computed(() => props.channel?.channelId || '');
  /** 传输格式下拉的可用性: 有流且有地址表才可切（邀请中/起播超时都没有可切的目标）。
   *  不写死 phase==='playing'——H.265 门控与迟到编码都会把 phase 置 error, 但流与地址表都在,
   *  此时下拉必须在, 否则门控文案里的"或切换到 RTC 播放"在本弹窗内无法执行 */
  const canSwitchFormat = computed(
    () => !!streamId.value && !!(cachedUrls.value.wsFlv || cachedUrls.value.flv)
  );

  /** 当前传输格式对应的拉流地址（与下拉所选一致; 该格式无地址时按 attach 的同一口径回落 FLV 主力路径） */
  const currentUrl = computed(() => {
    const urls = cachedUrls.value;
    return urls[format.value] || urls.wsFlv || urls.flv || '';
  });
  /** 复制说明: WS-FLV/WebRTC 是浏览器专用地址, 拿去 VLC 必失败——先说清楚该切哪个格式 */
  const copyTip = computed(() => {
    if (format.value === 'wsFlv') {
      return '复制当前地址（WS-FLV 只有浏览器能播，外部播放器请先切到 HTTP-FLV / HLS / HTTP-TS）';
    }
    if (format.value === 'rtc') {
      return '复制当前地址（WebRTC 交换地址，供浏览器或支持 WebRTC 的客户端使用）';
    }
    return '复制当前地址（带 1 小时有效的播放签名，可直接粘贴到 VLC 等播放器）';
  });

  /** 复制当前格式的播放地址: 方便拿真地址去 VLC/ffmpeg 复现问题（排查链路时比截图有用） */
  async function copyUrl() {
    const url = currentUrl.value;
    if (!url) return;
    const ok = await copyText(url);
    if (ok) Message.success('播放地址已复制');
    else Message.error('复制失败，请手动选中地址复制');
  }

  // ---------- 云台（M5-B2: 与 live 页同口径; 摇杆组件复用, 仅控制指令收口在本弹窗） ----------
  /**
   * 云台入口判定: ptzEnabled=1 强制开启优先（类型漏报的人工兜底）; 自动口径= ptzType>0
   * 且未被禁用（ptzEnabled=0 优先拦截）。后端对 ptzType=0 仅 WARN 放行, 口径一致。
   */
  const canPtz = computed(() => {
    const ch = props.channel;
    if (!ch) return false;
    const allowed = ch.ptzEnabled === 1 || ((ch.ptzType ?? 0) > 0 && ch.ptzEnabled !== 0);
    return allowed && auth.hasPerm('ptz:control');
  });

  /** 1009 提示节流: 摇杆连续指令连发限流, 3 秒内只提示一次（镜像 live 页） */
  const PTZ_LIMIT_TIP_GAP = 3000;
  let ptzLimitTipAt = 0;

  function onPtz(cmd: PtzCommand, speed: number) {
    if (!props.channel) return;
    void ptzApi.control(props.channel.id, cmd, speed).catch((e) => {
      // 仅限流(业务码 1009)给提示, 其余错误维持静默（通用失败提示由 http 拦截器统一弹出）
      if ((e as { code?: number }).code !== 1009) return;
      const now = Date.now();
      if (now - ptzLimitTipAt < PTZ_LIMIT_TIP_GAP) return;
      ptzLimitTipAt = now;
      Message.warning('云台指令发送过于频繁，请稍后再试');
    });
  }

  // ---------- 邀请 / 起播 ----------
  async function start() {
    const ch = props.channel;
    if (!ch) return;
    const seq = ++runSeq;
    await shutdown();
    // 期间又发起了新一轮邀请（重复 watcher / 连点重试 / 换通道）→ 本次作废, 别再发点播
    if (disposed || seq !== runSeq) return;
    phase.value = 'inviting';
    error.value = '';
    // 自愈重播（none_reader 触发的 start）带提示文案; 用户主动起播则清窗口+清提示
    hint.value = pendingAutoHint ? '流被媒体节点回收，正在自动重连…' : '';
    if (!pendingAutoHint) autoRetryAt = 0;
    pendingAutoHint = false;
    softDecode.value = false; // 新会话未定编码, 先撤上一路可能残留的软解徽标
    streamId.value = '';
    videoCodec.value = '';
    lastAttachUrl = '';
    cachedUrls.value = {};
    try {
      const r = await playApi.play(ch.id);
      // 迟到的响应: 弹窗已关闭 / 列表已切到别的通道 / 已改邀新一轮 → 立即停掉这次误产生的流
      if (disposed || seq !== runSeq || props.channel?.id !== ch.id) {
        void playApi.stop(r.streamId).catch(() => {});
        return;
      }
      streamId.value = r.streamId;
      videoCodec.value = r.videoCodec || '';
      if (r.status === 'STREAMING' && (r.urls?.wsFlv || r.urls?.flv)) {
        // 直连复用路径同样要缓存地址表: 切传输格式靠它重挂, 漏存会让"传输"下拉必报"无可播放地址"
        cachedUrls.value = r.urls;
        attach(r.urls);
      } else {
        armTimeout();
      }
    } catch (e) {
      // 与成功路径同款校验: 过期的失败不能覆盖当前健康状态——否则遮罩会盖住其实已经出画的画面
      if (disposed || seq !== runSeq || props.channel?.id !== ch.id) return;
      phase.value = 'error';
      // 业务错误拦截器已换成后端中文 msg; 超时/403 等 axios 原始错误的 message 是英文技术文案
      // （如 "timeout of 15000ms exceeded"）, 对用户无意义, 一律用本地文案兜住
      error.value = errorTextOf(e, '点播失败');
    }
  }

  function armTimeout() {
    disarmTimeout();
    armPolling();
    inviteTimer = window.setTimeout(() => {
      if (phase.value === 'inviting') {
        phase.value = 'error';
        error.value = '起播超时（设备/媒体节点无响应）';
      }
    }, INVITE_TIMEOUT);
  }

  function disarmTimeout() {
    disarmPolling();
    if (inviteTimer) {
      window.clearTimeout(inviteTimer);
      inviteTimer = 0;
    }
  }

  /** inviting 期兜底轮询: 流就绪即使 STREAM_READY 丢失也能拿到地址起播（镜像 live 页） */
  function armPolling() {
    disarmPolling();
    const seq = runSeq;
    pollTimer = window.setInterval(async () => {
      if (phase.value !== 'inviting' || seq !== runSeq || !streamId.value) {
        disarmPolling();
        return;
      }
      try {
        const r = await playApi.urls(streamId.value);
        if (phase.value !== 'inviting' || seq !== runSeq) return;
        if (r.urls?.wsFlv || r.urls?.flv) {
          cachedUrls.value = r.urls;
          attach(r.urls);
        }
      } catch {
        /* 流未就绪(1004)/网络抖动: 下一轮再试, 15s 超时兜底终态 */
      }
    }, INVITE_POLL_INTERVAL);
  }

  function disarmPolling() {
    if (pollTimer) {
      window.clearInterval(pollTimer);
      pollTimer = 0;
    }
  }

  /** 按当前格式解析拉流地址（该格式无地址时回落 FLV 主力路径, 并同步格式避免后端拿到错地址） */
  function resolveStream(urls: StreamUrls): { format: StreamFormat; url: string } | null {
    const fmt = format.value;
    const url = urls[fmt] || urls.wsFlv || urls.flv || '';
    if (!url) return null;
    return { format: url === urls[fmt] ? fmt : 'wsFlv', url };
  }

  /** H.265 软解判据取自 utils/player（与 live 页同口径, 改动只落一处） */
  const softDecodeHit = (fmt: StreamFormat) => shouldSoftDecode(videoCodec.value, fmt)
  /** FLV 系传输 = 软解态唯一可选项（jessibuca 只带 FLV 解封装 + ws/http 取流, 与 live 页同口径） */
  const FLV_FAMILY: string[] = ['wsFlv', 'flv'];

  function attach(urls: StreamUrls) {
    disarmTimeout();
    hint.value = '';
    const resolved = resolveStream(urls);
    if (!resolved) {
      phase.value = 'error';
      error.value = '无可播放地址';
      return;
    }
    // H.265 且无 HEVC 硬解: 自动降级 jessibuca 软解(原「拦截报错」语义)。
    // jessibuca 只吃 FLV → 强制回 wsFlv/flv 地址, format 同步实际值防控制条显示混淆
    if (softDecodeHit(resolved.format)) {
      if (resolved.format !== 'wsFlv' && resolved.format !== 'flv') {
        const softUrl = urls.wsFlv || urls.flv || '';
        if (!softUrl) {
          phase.value = 'error';
          error.value = '无可播放地址';
          return;
        }
        resolved.format = 'wsFlv';
        resolved.url = softUrl;
      }
      softDecode.value = true;
    } else {
      softDecode.value = false;
    }
    destroyPlayer();
    lastAttachUrl = resolved.url;
    format.value = resolved.format;
    phase.value = 'playing';
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
      p.start(video, resolved.format, resolved.url, {
        // 编码随流下发: PlayerAdapter 静态判据与本场景同口径, 运行时软解兜底亦读它
        codec: videoCodec.value || undefined,
        onError: (msg) => {
          if (phase.value === 'playing') {
            phase.value = 'error';
            error.value = msg;
          }
        },
        // HEVC-MSE 假阳性兜底: mpegts 失败自动降级软解重挂, 徽标同步点亮
        onSoftDecode: () => {
          softDecode.value = true;
        }
      });
    });
  }

  function destroyPlayer() {
    player?.destroy();
    player = null;
  }

  /** 停流收尾: 拆播放器 + 停定时器 + 释放服务端会话（失败静默, 拦截器已提示） */
  async function shutdown() {
    destroyPlayer();
    disarmTimeout();
    const id = streamId.value;
    streamId.value = '';
    if (id) pendingStop = playApi.stop(id).catch(() => {});
    // 即便本次没有流要停, 也要等上一轮在途的停流落地（见 pendingStop 声明处的说明）
    await pendingStop;
  }

  function onFormatChange(v: unknown) {
    const fmt = v as StreamFormat;
    // 不判 phase: H.265 门控/迟到编码会停在 error, 但流还在——切 RTC 正是它们给出的自救路径
    if (fmt === format.value || !canSwitchFormat.value) return;
    // 软解态只允许 FLV 系之间切换(下拉选项已置灰, 此处兜住程序化调用)
    if (softDecode.value && !FLV_FAMILY.includes(fmt)) return;
    format.value = fmt;
    // 同一条流换传输方式: 只拆旧播放器按新格式重挂, 不重发点播
    attach(cachedUrls.value);
  }

  // ---------- WS 事件（按本弹窗 streamId 过滤, 与实时预览页互不干扰） ----------
  function onWs(type: string, data: Record<string, unknown>) {
    const sid = data.streamId as string | undefined;
    if (!sid || sid !== streamId.value) return;
    if (type === WsEvent.StreamReady) {
      const urls = (data.urls as StreamUrls) ?? {};
      cachedUrls.value = urls;
      videoCodec.value = (data.videoCodec as string) || videoCodec.value;
      // B5 迟到补投: playing 态同址只补编码信号, 不重挂（避免无谓重缓冲）
      if (phase.value === 'playing' && (urls.wsFlv || urls.flv || '') === lastAttachUrl) {
        // 迟到编码软解复查: 起播时 videoCodec 还空着, 此刻才知道是 H265 → 拆 mpegts 改软解重挂
        // （与 live 页同口径; 此前是 destroy+error, 软解兜底落地后不再给用户死路）
        if (softDecodeHit(format.value)) {
          destroyPlayer();
          attach(cachedUrls.value);
        }
        return;
      }
      attach(urls);
    } else if (type === WsEvent.StreamClosed) {
      destroyPlayer();
      if (phase.value === 'playing' || phase.value === 'inviting') {
        const reason = (data.reason as string) || '';
        // 自愈重播(M5-B3, 与宫格同口径): none_reader 是媒体节点"连续 N 秒 0 读者"判死, 与
        // "用户确实不看了"不等价（起播空窗/断线重连/软解冷加载都命中, 后端已抬到 60s + 关前复核）。
        // 30s 窗口内只自愈一次, 第二次落错误文案, 避免服务端持续判死时无限重播。
        if (reason === 'none_reader' && !disposed && props.channel
            && Date.now() - autoRetryAt > 30000) {
          autoRetryAt = Date.now();
          pendingAutoHint = true;
          void start();
        } else {
          phase.value = 'error';
          error.value = `流已关闭（${reason || '未知原因'}）`;
        }
      }
    } else if (type === WsEvent.StreamFallback) {
      // UDP 收流超时 → 后端已转 TCP re-INVITE: 保持/回到邀请态续期等待, 不报错。
      // error 态也必须接住: 15s 起播超时可能恰好先于本事件触发, 丢掉它就只剩一个假失败
      if (phase.value === 'inviting' || phase.value === 'error') {
        phase.value = 'inviting';
        error.value = '';
        hint.value = 'UDP 收流超时，已自动切换 TCP 重新邀请…';
        armTimeout();
      }
    }
  }

  function onPageHide() {
    if (streamId.value) stopStreamKeepalive(streamId.value, tokenAtOpen);
  }

  // ---------- 全屏(画面区整体, 控制条仍在画面下方) ----------
  const stageEl = ref<HTMLElement>();
  const isFullscreen = ref(false);

  function toggleFullscreen() {
    const el = stageEl.value;
    if (!el) return;
    if (document.fullscreenElement) void document.exitFullscreen();
    else void el.requestFullscreen();
  }

  function onFullscreenChange() {
    isFullscreen.value = document.fullscreenElement === stageEl.value;
  }

  function close() {
    disposed = true;
    void shutdown();
    emit('update:visible', false);
  }

  // 弹窗开关即会话生命周期（unmount-on-close 会重建弹窗内 DOM, 但本组件实例不销毁）
  watch(
    () => props.visible,
    (v) => {
      if (v) {
        tokenAtOpen = auth.token;
        disposed = false;
        void start();
      } else {
        // 关闭即置 disposed: 拦住仍在飞行中的 play 响应, 否则它会起一路没人停的流
        disposed = true;
        void shutdown();
      }
    }
  );

  // 同一弹窗实例换通道（列表连点播放）: 先停旧流再邀请新的, 防两路并存
  watch(
    () => props.channel?.id,
    () => {
      if (props.visible) void start();
    }
  );

  unbindWs = wsClient.on(onWs);
  window.addEventListener('pagehide', onPageHide);
  document.addEventListener('fullscreenchange', onFullscreenChange);

  onUnmounted(() => {
    disposed = true;
    unbindWs?.();
    window.removeEventListener('pagehide', onPageHide);
    document.removeEventListener('fullscreenchange', onFullscreenChange);
    // 已登出（token 被清）时 playApi.stop 带不上 Bearer 必然 401, 退回打开时的 token 快照
    const id = streamId.value;
    if (id && !auth.token && tokenAtOpen) {
      streamId.value = '';
      destroyPlayer();
      stopStreamKeepalive(id, tokenAtOpen);
    } else {
      void shutdown();
    }
  });

  /**
   * 路由离开不停流会一直占着设备上行: 设备页被 keep-alive 缓存（index.vue 用 onActivated）,
   * 后退键离开只失活不卸载 → onUnmounted 不执行、pagehide 也不触发, 而播放器未销毁时
   * 服务端连 none_reader 兜底都不会触发（读端还在）。同项目的缓存页 PlaybackArea 用
   * onDeactivated 处理同一问题, 这里对齐。失活即关会话, 回来后如需再看重新点播放。
   */
  onDeactivated(() => {
    if (!props.visible) return;
    disposed = true;
    void shutdown();
    emit('update:visible', false);
  });
</script>

<template>
  <a-modal
    :visible="visible"
    :width="880"
    :mask-closable="false"
    unmount-on-close
    @cancel="close"
    @close="close"
  >
    <template #title>
      <span class="dlg-title">
        <icon-play-circle />
        实时播放 · {{ title }}
      </span>
    </template>

    <div ref="stageEl" class="lv-stage" :data-soft="softDecode ? '1' : ''">
      <video ref="videoEl" class="lv-video" playsinline />

      <div v-if="phase === 'inviting'" class="lv-mask">
        <a-spin :size="22" />
        <span>{{ hint || '正在邀请设备推流…' }}</span>
      </div>
      <div v-else-if="phase === 'error'" class="lv-mask">
        <icon-exclamation-circle :size="22" />
        <span class="lv-err-text">{{ error }}</span>
        <a-button size="small" type="primary" @click="start">
          <template #icon><icon-refresh /></template>
          重 试
        </a-button>
      </div>
    </div>

    <!-- 控制条: 深色工具栏贴画面下方(与回放播放器同一视觉语言)——LIVE/通道/编码居左, 云台/传输/复制/全屏居右 -->
    <div class="lv-bar">
      <span class="lv-live"><i class="live-dot" />LIVE</span>
      <span class="lv-no">
        <icon-desktop />
        {{ channelNo || '-' }}
      </span>
      <a-tag v-if="videoCodec" size="small" :color="videoCodec === 'H265' ? 'orange' : 'arcoblue'">
        {{ videoCodec }}
      </a-tag>
      <a-tag
        v-if="softDecode"
        size="small"
        color="orangered"
        title="当前设备无 HEVC 硬解，已自动启用 WASM 软解码（延迟与 CPU 占用略增）"
      >
        软解
      </a-tag>
      <span class="lv-spacer" />
      <a-popover v-if="canPtz" position="top" :content-style="{ padding: '12px' }">
        <a-button size="mini" class="bar-btn">
          <template #icon><icon-robot /></template>
          云台
        </a-button>
        <template #content>
          <PtzJoystick :channel-db-id="channel?.id" @move="onPtz" />
        </template>
      </a-popover>
      <span v-if="canSwitchFormat" class="lv-fmt">
        <!-- 软解态只留 FLV 系可选（WS-FLV / HTTP-FLV）: jessibuca 只带 FLV 解封装 + ws/http
             取流, ts/hls/rtc 在无硬解机器上也走不通 —— 置灰而非整体禁用, 保持 ws↔http 可切换 -->
        <a-tooltip
          content="软解模式仅支持 FLV 传输（WebSocket / HTTP）"
          position="top"
          :disabled="!softDecode"
        >
          <a-select
            :model-value="format"
            size="mini"
            style="width: 110px"
            @change="onFormatChange"
          >
            <a-option value="wsFlv">WS-FLV</a-option>
            <a-option value="flv">HTTP-FLV</a-option>
            <a-option value="ts" :disabled="softDecode">HTTP-TS</a-option>
            <a-option value="hls" :disabled="softDecode">HLS</a-option>
            <a-option v-if="SHOW_RTC" value="rtc" :disabled="softDecode">WebRTC</a-option>
          </a-select>
        </a-tooltip>
        <a-tooltip :content="copyTip" position="top">
          <!-- data-url 供 e2e 断言"复制内容跟随所选格式": 非安全上下文（http+IP 部署）读不了剪贴板 -->
          <a-button size="mini" :data-url="currentUrl" @click="copyUrl">
            <template #icon><icon-copy /></template>
            复制地址
          </a-button>
        </a-tooltip>
      </span>
      <a-tooltip :content="isFullscreen ? '退出全屏' : '全屏'" position="top">
        <a-button size="mini" class="bar-btn" @click="toggleFullscreen">
          <template #icon>
            <icon-fullscreen-exit v-if="isFullscreen" />
            <icon-fullscreen v-else />
          </template>
        </a-button>
      </a-tooltip>
    </div>

    <!-- footer: 规范要求弹窗有独立的 footer 区（按钮不跟内容滚动）, 信息条只放通道/编码/格式 -->
    <template #footer>
      <a-button @click="close">关 闭</a-button>
    </template>
  </a-modal>
</template>

<style scoped lang="less">
  .lv-stage {
    position: relative;
    display: flex;
    align-items: center;
    justify-content: center;
    width: 100%;
    aspect-ratio: 16 / 9;
    overflow: hidden;
    background: #000;
    border-radius: 4px 4px 0 0; // 下缘与控制条衔接

    &:fullscreen {
      aspect-ratio: auto;
      width: 100%;
      height: 100%;
      border-radius: 0;
    }

    .lv-video {
      width: 100%;
      height: 100%;
      object-fit: contain;
    }

    .lv-mask {
      position: absolute;
      inset: 0;
      z-index: 2;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: 12px;
      padding: 0 32px;
      font-size: 13px;
      color: #fff;
      text-align: center;
      background: rgba(0, 0, 0, 0.55);

      .lv-err-text {
        max-width: 560px;
        line-height: 20px;
      }
    }
  }

  // 控制条: 深色工具栏(与回放播放器同一视觉语言), 贴画面下方
  .lv-bar {
    display: flex;
    align-items: center;
    gap: 10px;
    margin-top: 0;
    padding: 5px 10px;
    background: #1d2129;
    border-radius: 0 0 4px 4px;

    // 直播徽标: 红点呼吸 + LIVE, 监控语义的「实时」标识
    .lv-live {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      font-size: 11px;
      font-weight: 600;
      letter-spacing: 1px;
      color: #f53f3f;

      .live-dot {
        width: 6px;
        height: 6px;
        background: #f53f3f;
        border-radius: 50%;
        animation: live-blink 2s ease-in-out infinite;
      }
    }

    .lv-no {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      font-size: 12px;
      color: rgba(255, 255, 255, 0.85);
    }

    .bar-btn {
      color: rgba(255, 255, 255, 0.9);
      background: transparent;
      border: none;

      &:hover {
        color: #fff;
        background: rgba(255, 255, 255, 0.12);
      }
    }

    .lv-spacer {
      flex: 1;
    }

    .lv-fmt {
      display: inline-flex;
      align-items: center;
      gap: 6px;
    }
  }

  @keyframes live-blink {
    0%,
    100% {
      opacity: 1;
    }
    50% {
      opacity: 0.35;
    }
  }
</style>
