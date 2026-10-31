<script setup lang="ts">
  import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
  import { Message } from '@arco-design/web-vue';
  import { useRouter } from 'vue-router';
  import { deviceApi } from '@/api/device';
  import type { ChannelTreeNode, GbDevice } from '@/api/device';
  import { groupApi } from '@/api/group';
  import type { GroupChannelNode } from '@/api/group';
  import { playApi, stopStreamKeepalive } from '@/api/play';
  import type { StreamUrls } from '@/api/play';
  import { ptzApi } from '@/api/ptz';
  import type { PtzCommand } from '@/api/ptz';
  import { useAuthStore } from '@/stores/auth';
  import { talkApi } from '@/api/talk';
  import { TalkPusher } from '@/utils/talk';
  import { WsEvent, wsClient } from '@/utils/ws';
  import { PlayerAdapter, captureFrame, shouldSoftDecode, prepareVideoElement, rtcSupported } from '@/utils/player';
  import type { StreamFormat } from '@/utils/player';
  import type { GbChannel } from '@/api/device';
  import PtzJoystick from './components/PtzJoystick.vue';

  /**
   * B3 实时预览: 左侧通道树(目录/分组双视图) + 右侧宫格(1/4/9/16)。
   * 起播: play → INVITING 等 WS STREAM_READY / 复用则 STREAMING 直连;
   * 释放: 关窗格/切页签(本页 ignoreCache 销毁)→ stop, 浏览器卸载用 fetch keepalive 兜底(D17)。
   */
  interface ChannelItem {
    id: number;
    channelId: string;
    name: string;
    status: number;
    ptzType: number;
    /** 通道级云台开关: null=自动 1=强制开启 0=禁用 */
    ptzEnabled: number | null;
    deviceName: string;
    /** 目录节点(下级平台虚拟分组): 展示标注, 不可起播 */
    directory: boolean;
  }

  interface Slot {
    key: number;
    status: 'idle' | 'inviting' | 'playing' | 'error';
    channel: ChannelItem | null;
    streamId?: string;
    /** 后端返回的多格式拉流地址(会话期内有效), 切换格式免重发点播 */
    urls?: StreamUrls;
    /** 视频编码(H264/H265, B5): H.265 且无 HEVC-MSE 硬解时走软解 */
    videoCodec?: string;
    /** jessibuca WASM 软解态: 无 HEVC 硬解自动降级, 底条显示徽标+禁用格式切换(仅 FLV 可用) */
    softDecode?: boolean;
    /** 当前播放格式(默认 WS-FLV); B5 起 HLS/WebRTC 可选 */
    format?: StreamFormat;
    error?: string;
    /** 降级提示，收到 STREAM_FALLBACK 时设置，邀请中覆盖文案。 */
    hint?: string;
    /** 点播请求序号: 快速切换通道时丢弃迟到的旧响应(防止错流/状态覆盖) */
    reqSeq: number;
    /** 最近一次 none_reader 自愈重播时刻: 同一格 30s 内只自愈一次, 用户手动重试/停止清除 */
    autoRetryAt?: number;
    /** 对讲状态(M3-B5): idle=未开启; connecting=连接中; talking=推流中。
     *  pusher 实例不放 reactive(类实例私有限定会被 Vue 类型剥掉), 存 talkPushers Map */
    talkState?: 'idle' | 'connecting' | 'talking';
    talkId?: number;
  }

  /** 本页是否展示 RTC 格式选项(能力探测一次) */
  const SHOW_RTC = rtcSupported();
  /** 传输格式下拉选项(宫格底条 dropdown 用; RTC 仅能力可用时出现) */
  const FMT_OPTIONS: Array<{ value: string; label: string }> = [
    { value: 'wsFlv', label: 'WS-FLV' },
    { value: 'flv', label: 'HTTP-FLV' },
    { value: 'ts', label: 'HTTP-TS' },
    { value: 'hls', label: 'HLS' },
    ...(SHOW_RTC ? [{ value: 'rtc', label: 'WebRTC' }] : [])
  ];
  /** 传输格式短名(底条触发按钮显示当前格式, 窄格不挤; 下拉选项保留全名) */
  const FMT_SHORT: Record<string, string> = { wsFlv: 'WS', flv: 'FLV', ts: 'TS', hls: 'HLS', rtc: 'RTC' };
  /** FLV 系传输 = 软解态唯一可选项: jessibuca 的 JS 侧只有 FLV 解封装 + ws/http 两种取流,
   *  ts/hls（需 MPEG-TS 解封装）与 rtc（WebRTC 管线）它都不涉及, 且在无硬解机器上本来也走不通 */
  const FLV_FAMILY: string[] = ['wsFlv', 'flv'];

  interface DeviceGroup {
    device: GbDevice;
    channels: ChannelItem[];
    loaded: boolean;
    loading: boolean;
    /** M3-B3: 下级平台(deviceType=2)的 parental 分组树（channelTree 数据映射） */
    cascadeTree: CascadeNode[];
  }

  /** 级联目录树节点: 分组节点(parental=1)不可播放仅折叠, 通道叶子双击播放 */
  interface CascadeNode {
    key: string;
    title: string;
    isGroup: boolean;
    channel: ChannelItem | null;
    children: CascadeNode[];
  }

  /** 级联目录拍平行结构: 分组行 + 缩进深度, 展开的分组子节点随行输出 */
  interface CascadeRow {
    node: CascadeNode;
    depth: number;
  }

  const LAYOUTS = [1, 4, 9, 16];
  const INVITE_TIMEOUT = 15000;
  /** 起播兜底轮询间隔: WS STREAM_READY 事件不持久化(登录瞬间/WS 断线窗口发出即丢,
   *  242 实测曾 15s 起播超时), inviting 期轮询 /play/{sid}/urls 自愈 */
  const INVITE_POLL_INTERVAL = 3000;
  /** none_reader 自愈重播窗口: 同一格该窗口内只自动重连一次, 第二次落错误文案
   *  （服务端持续判死时避免无限重播打后端; 用户手动重试会重置窗口） */
  const AUTO_RETRY_GAP = 30000;

  const auth = useAuthStore();
  const router = useRouter();
  let slotSeq = 0;
  let reqSeq = 0;
  /** 挂载时的 token 快照: 退出登录会先清空 store 再触发本页卸载,
   *  卸载时的批量停流要用这个快照带 Bearer, 否则流要等 none_reader 超时才关 */
  let tokenAtMount = '';

  const layout = ref(4);
  const slots = ref<Slot[]>([]);
  const activeKey = ref<number | null>(null);

  const groups = ref<DeviceGroup[]>([]);
  const expandedKeys = ref<number[]>([]);
  const keyword = ref('');
  const searchResults = ref<ChannelItem[]>([]);
  /** 级联目录展开态: key = `${deviceId}|${分组 channelId}` */
  const cascadeExpanded = ref(new Set<string>());

  const players = new Map<number, PlayerAdapter>();
  /** 对讲推流器(非响应式: Vue reactive 会剥类实例私有字段) */
  const talkPushers = new Map<number, TalkPusher>();
  const videoEls = new Map<number, HTMLVideoElement>();
  const timeouts = new Map<number, number>();
  const polls = new Map<number, number>();
  let unbindWs: (() => void) | undefined;

  const keywordTrim = computed(() => keyword.value.trim());
  const hasPlaying = computed(() => slots.value.some((s) => s.streamId));

  function makeSlots(n: number): Slot[] {
    return Array.from({ length: n }, () => {
      slotSeq += 1;
      reqSeq += 1;
      return { key: slotSeq, status: 'idle' as const, channel: null, reqSeq };
    });
  }

  // ---------- 通道树 ----------
  function mapChannel(c: GbChannel, deviceName: string): ChannelItem {
    return {
      id: c.id,
      channelId: c.channelId,
      name: c.customName || c.name || c.channelId,
      status: c.status,
      ptzType: c.ptzType,
      ptzEnabled: c.ptzEnabled ?? null,
      deviceName,
      directory: c.directory === true
    };
  }

  async function loadGroups() {
    const data = await deviceApi.page({ page: 1, count: 100 });
    deviceTotal.value = data.total;
    groups.value = data.list.map((d) => ({
      device: d,
      channels: [],
      loaded: false,
      loading: false,
      cascadeTree: []
    }));
    // 已展开的组在设备刷新后保持加载状态
    for (const key of expandedKeys.value) {
      const g = groups.value.find((x) => x.device.id === key);
      if (g) void loadChannels(g);
    }
  }

  async function loadChannels(g: DeviceGroup) {
    g.loading = true;
    try {
      const data = await deviceApi.channels({
        deviceId: g.device.id,
        page: 1,
        count: 500
      });
      g.channels = data.list.map((c) =>
        mapChannel(c, g.device.name || g.device.deviceId)
      );
      // 下级平台设备: 目录按 parental 分组树展示（docs/08 §3）
      if (g.device.deviceType === 2) {
        const tree = await deviceApi.channelTree(g.device.id);
        g.cascadeTree = toCascadeNodes(tree, g.device.name || g.device.deviceId);
        const next = new Set(cascadeExpanded.value);
        for (const n of g.cascadeTree) {
          next.add(`${g.device.id}|${n.key}`);
        }
        cascadeExpanded.value = next;
      }
      g.loaded = true;
    } finally {
      g.loading = false;
    }
  }

  function toCascadeNodes(nodes: ChannelTreeNode[], deviceName: string): CascadeNode[] {
    return nodes.map((n) => {
      // 有子节点即按分组渲染: 部分下级平台(如 LiveGBS)对目录节点报 parental=0 却挂 children,
      // 只认 parental 会把根节点当叶子、cascadeRows 不递归 → 整棵子通道一个都不显示
      const isGroup = n.channel.parental === 1 || (n.children?.length ?? 0) > 0;
      return {
        key: n.channel.channelId,
        title: isGroup ? n.channel.name || n.channel.channelId
          : n.channel.customName || n.channel.name || n.channel.channelId,
        isGroup,
        channel: isGroup ? null : mapChannel(n.channel, deviceName),
        children: toCascadeNodes(n.children || [], deviceName)
      };
    });
  }

  /** 展开态 → 拍平行行（分组行后跟其已展开的子行, 缩进 = 深度×14px） */
  function cascadeRows(g: DeviceGroup): CascadeRow[] {
    const rows: CascadeRow[] = [];
    const walk = (nodes: CascadeNode[], depth: number) => {
      for (const n of nodes) {
        rows.push({ node: n, depth });
        if (n.isGroup && cascadeExpanded.value.has(`${g.device.id}|${n.key}`)) {
          walk(n.children, depth + 1);
        }
      }
    };
    walk(g.cascadeTree, 0);
    return rows;
  }

  function toggleCascade(g: DeviceGroup, node: CascadeNode) {
    const k = `${g.device.id}|${node.key}`;
    const next = new Set(cascadeExpanded.value);
    if (next.has(k)) {
      next.delete(k);
    } else {
      next.add(k);
    }
    cascadeExpanded.value = next;
  }

  function onExpandedChange(keys: (string | number)[]) {
    expandedKeys.value = keys as number[];
    for (const k of expandedKeys.value) {
      const g = groups.value.find((x) => x.device.id === k);
      if (g && !g.loaded && !g.loading) void loadChannels(g);
    }
  }

  // 搜索: 全局通道模糊查询(不带 deviceId)
  let searchTimer: number | undefined;
  watch(keywordTrim, (kw) => {
    window.clearTimeout(searchTimer);
    if (!kw) {
      searchResults.value = [];
      return;
    }
    searchTimer = window.setTimeout(async () => {
      const data = await deviceApi.channels({ query: kw, page: 1, count: 50 });
      searchResults.value = data.list.map((c) => {
        const g = groups.value.find((x) => x.device.id === c.deviceDbId);
        return mapChannel(c, g?.device.name || g?.device.deviceId || '');
      });
    }, 300);
  });

  // ---------- 分组视图（B3: /groups/channel-tree 登录可读, 不 gate 在 group:list 上） ----------
  type TreeView = 'catalog' | 'group';
  const treeView = ref<TreeView>('catalog');

  interface GroupViewNode {
    /** 分组 'g'+id / 通道 'c'+dbId（区分同一通道在目录/分组两棵树里的 key） */
    key: string;
    title: string;
    isGroup: boolean;
    channel?: ChannelItem;
    children?: GroupViewNode[];
  }

  const groupTree = ref<GroupViewNode[]>([]);
  const groupLoading = ref(false);
  /** 已加载过的分组树缓存: 切回分组视图不重复请求 */
  const groupLoaded = ref(false);
  const groupExpanded = ref<Set<string>>(new Set());
  /** 空态原因: none=平台还没建分组 / unauthorized=平台有分组但当前账号未被授权（channel-tree 做数据权限过滤） */
  const groupEmptyReason = ref<'none' | 'unauthorized'>('none');

  /** 「去创建」跳转分组管理（入口权限与页面菜单 perm_code 一致, 无权限则不显示入口） */
  function goGroupManage() {
    void router.push('/access/group');
  }

  function mapGroupNodes(nodes: GroupChannelNode[]): GroupViewNode[] {
    return nodes.map((n) => ({
      key: `g${n.group.id}`,
      title: n.group.groupName,
      isGroup: true,
      children: [
        ...(n.children?.length ? mapGroupNodes(n.children) : []),
        ...(n.channels?.length
          ? n.channels.map((c) => ({
              key: `c${c.id}`,
              title: c.customName || c.name || c.channelId,
              isGroup: false,
              channel: mapChannel(c, '')
            }))
          : [])
      ]
    }));
  }

  async function loadGroupTree(force = false) {
    if (groupLoaded.value && !force) return;
    groupLoading.value = true;
    try {
      groupTree.value = mapGroupNodes(await groupApi.channelTree());
      // 树为空时二次判断原因: /groups/tree 登录可读且不做数据权限过滤,
      // 有分组但通道树为空 = 当前账号未被授权任何分组, 文案与"平台无分组"区分开
      if (!groupTree.value.length) {
        const allGroups = await groupApi.tree();
        groupEmptyReason.value = allGroups.length ? 'unauthorized' : 'none';
      } else {
        groupEmptyReason.value = 'none';
      }
      // 默认展开根分组
      groupExpanded.value = new Set(groupTree.value.map((n) => n.key));
      groupLoaded.value = true;
    } finally {
      groupLoading.value = false;
    }
  }

  function onViewChange(v: unknown) {
    if (v !== 'catalog' && v !== 'group') return;
    treeView.value = v;
    if (v === 'group') void loadGroupTree();
    // 切回目录视图: groups 状态仍在, 不重发接口
  }

  function toggleGroup(node: GroupViewNode) {
    if (!node.isGroup) return;
    const next = new Set(groupExpanded.value);
    if (next.has(node.key)) next.delete(node.key);
    else next.add(node.key);
    groupExpanded.value = next;
  }

  /** 分组视图可见行（拍平渲染, 支持任意层级嵌套） */
  const groupRows = computed<{ node: GroupViewNode; depth: number }[]>(() => {
    const rows: { node: GroupViewNode; depth: number }[] = [];
    const walk = (nodes: GroupViewNode[], depth: number) => {
      for (const n of nodes) {
        rows.push({ node: n, depth });
        if (n.isGroup && groupExpanded.value.has(n.key) && n.children?.length) {
          walk(n.children, depth + 1);
        }
      }
    };
    walk(groupTree.value, 0);
    return rows;
  });

  // ---------- 左侧统计条(目录/分组计数, 底部状态栏) ----------
  const deviceTotal = ref(0);
  const channelTotal = ref<number | null>(null);

  const onlineCount = computed(
    () => groups.value.filter((g) => g.device.online === 1).length
  );

  /** 分组视图计数: 递归统计分组节点与通道绑定条目(与展开状态无关) */
  const groupStats = computed(() => {
    let groupCount = 0;
    let channelCount = 0;
    const walk = (nodes: GroupViewNode[]) => {
      for (const n of nodes) {
        if (n.isGroup) {
          groupCount += 1;
          walk(n.children ?? []);
        } else {
          channelCount += 1;
        }
      }
    };
    walk(groupTree.value);
    return { groups: groupCount, channels: channelCount };
  });

  async function loadChannelTotal() {
    try {
      const data = await deviceApi.channels({ page: 1, count: 1 });
      channelTotal.value = data.total;
    } catch {
      // 统计是辅助信息, 失败静默保持上次值
    }
  }

  // 目录重报(CHANNEL_CHANGE)后 1s 防抖刷新通道总数, 避免目录同步期间连打接口
  let channelTotalTimer: number | undefined;
  function scheduleChannelTotalRefresh() {
    window.clearTimeout(channelTotalTimer);
    channelTotalTimer = window.setTimeout(() => void loadChannelTotal(), 1000);
  }

  // ---------- 起播 / 释放 ----------
  /** 落窗规则: 已选中窗口优先(替换语义, startPlayInto 先停旧流); 无选中→首个空闲; 全满→第一个 */
  function pickSlot(): Slot | null {
    const active = slots.value.find((s) => s.key === activeKey.value);
    if (active) return active;
    return slots.value.find((s) => !s.channel) ?? slots.value[0] ?? null;
  }

  async function playChannel(ch: ChannelItem) {
    const active = slots.value.find((s) => s.key === activeKey.value);
    const existing = slots.value.find((s) => s.channel?.id === ch.id);
    // 同通道已在选中窗口播放(含邀请中) → 无操作, 防误双击开多窗
    if (existing && existing.key === activeKey.value) return;
    // 同通道已在别的窗口播放: 选中的是空闲窗口 → 移过去(关旧开新); 否则仅激活已有窗口
    if (existing) {
      if (active && !active.channel && active.key !== existing.key) {
        await stopSlot(existing);
        await startPlayInto(active, ch);
        return;
      }
      activeKey.value = existing.key;
      return;
    }
    const slot = pickSlot();
    if (slot) await startPlayInto(slot, ch);
  }

  async function startPlayInto(slot: Slot, ch: ChannelItem, auto = false) {
    // 非自愈路径（选通道/换格式/手点重试）视为用户主动起播 → 重置 none_reader 自愈窗口
    if (!auto) slot.autoRetryAt = undefined;
    if (slot.channel && slot.streamId) await stopSlot(slot);
    slot.reqSeq += 1;
    const seq = slot.reqSeq;
    slot.channel = ch;
    slot.status = 'inviting';
    slot.streamId = undefined;
    slot.urls = undefined;
    slot.videoCodec = undefined;
    slot.format = undefined;
    slot.error = undefined;
    slot.hint = undefined;
    activeKey.value = slot.key;
    try {
      const r = await playApi.play(ch.id);
      // 迟到的旧响应: 本窗口已发起新的点播 → 立即停掉这次误产生的流, 丢弃结果
      if (seq !== slot.reqSeq) {
        void playApi.stop(r.streamId).catch(() => {});
        return;
      }
      slot.streamId = r.streamId;
      slot.videoCodec = r.videoCodec || undefined;
      if (r.status === 'STREAMING' && (r.urls?.wsFlv || r.urls?.flv)) {
        slot.urls = r.urls;
        attachPlayer(slot);
      } else {
        armTimeout(slot);
      }
    } catch (e) {
      if (seq !== slot.reqSeq) return;
      slot.status = 'error';
      slot.error = (e as Error).message || '点播失败';
    }
  }

  function armTimeout(slot: Slot) {
    disarmTimeout(slot);
    armPolling(slot);
    timeouts.set(
      slot.key,
      window.setTimeout(() => {
        if (slot.status === 'inviting') {
          slot.status = 'error';
          slot.error = '起播超时（设备/媒体节点无响应）';
        }
      }, INVITE_TIMEOUT)
    );
  }

  function disarmTimeout(slot: Slot) {
    disarmPolling(slot);
    const t = timeouts.get(slot.key);
    if (t) {
      window.clearTimeout(t);
      timeouts.delete(slot.key);
    }
  }

  /** inviting 期兜底轮询: 流就绪即使 STREAM_READY 丢失也能拿到地址起播 */
  function armPolling(slot: Slot) {
    disarmPolling(slot);
    const seq = slot.reqSeq;
    polls.set(
      slot.key,
      window.setInterval(async () => {
        if (slot.status !== 'inviting' || seq !== slot.reqSeq || !slot.streamId) {
          disarmPolling(slot);
          return;
        }
        try {
          const r = await playApi.urls(slot.streamId);
          if (slot.status !== 'inviting' || seq !== slot.reqSeq) return;
          if (r.urls?.wsFlv || r.urls?.flv) {
            slot.urls = r.urls;
            attachPlayer(slot);
          }
        } catch {
          /* 流未就绪(1004)/网络抖动: 下一轮再试, 15s 超时兜底终态 */
        }
      }, INVITE_POLL_INTERVAL)
    );
  }

  function disarmPolling(slot: Slot) {
    const p = polls.get(slot.key);
    if (p) {
      window.clearInterval(p);
      polls.delete(slot.key);
    }
  }

  /** 按 slot 当前格式解析拉流地址（地址缺失时回落 FLV 主力路径） */
  function resolveStream(slot: Slot): { format: StreamFormat; url: string } | null {
    const urls = slot.urls;
    if (!urls) return null;
    const fmt: StreamFormat = slot.format ?? 'wsFlv';
    const url = urls[fmt] || urls.wsFlv || urls.flv || '';
    if (!url) return null;
    // 选中格式无地址而回落 wsFlv/flv 时, 返回实际可用的格式(否则 HLS 后端拿到 FLV 地址必报错)
    return { format: url === urls[fmt] ? fmt : 'wsFlv', url };
  }

  function attachPlayer(slot: Slot) {
    disarmTimeout(slot);
    // 换格式重挂: 先销毁旧播放器(顺带关闭旧 WebSocket/HTTP 连接)
    players.get(slot.key)?.destroy();
    players.delete(slot.key);
    const resolved = resolveStream(slot);
    if (!resolved) {
      slot.status = 'error';
      slot.error = '无可播放地址';
      return;
    }
    // H.265 且无 HEVC-MSE 硬解: 自动降级 jessibuca WASM 软解(原「拦截报错」语义)。
    // jessibuca 只吃 FLV(ts/hls 不可解)→ 强制回 wsFlv/flv 地址, slot.format 同步实际值防底条混淆
    if (shouldSoftDecode(slot.videoCodec, resolved.format)) {
      if (resolved.format !== 'wsFlv' && resolved.format !== 'flv') {
        const softUrl = slot.urls?.wsFlv || slot.urls?.flv || '';
        if (!softUrl) {
          slot.status = 'error';
          slot.error = '无可播放地址';
          return;
        }
        resolved.format = 'wsFlv';
        resolved.url = softUrl;
        slot.format = 'wsFlv';
      }
      slot.softDecode = true;
    } else {
      slot.softDecode = false;
    }
    // 实际挂载格式回写: 选中格式无地址时 resolveStream 已回落 wsFlv, 不回写会让底条显示与实际不符
    slot.format = resolved.format;
    slot.status = 'playing';
    void nextTick(() => {
      const video = videoEls.get(slot.key);
      if (!video) {
        slot.status = 'error';
        slot.error = '播放器未就绪';
        return;
      }
      prepareVideoElement(video);
      const p = new PlayerAdapter();
      players.set(slot.key, p);
      p.start(video, resolved.format, resolved.url, {
        // 编码随流下发: PlayerAdapter 静态判据与本场景同口径, 运行时软解兜底亦读它
        codec: slot.videoCodec,
        onError: (msg) => {
          if (slot.status === 'playing') {
            slot.status = 'error';
            slot.error = msg;
          }
        },
        // HEVC-MSE 假阳性兜底: mpegts 失败自动降级软解重挂, 徽标同步点亮
        onSoftDecode: () => {
          slot.softDecode = true;
        }
      });
    });
  }

  /** 切换拉流格式: 同一条流换传输方式, 只销毁旧播放器重挂, 不动点播会话 */
  function switchFormat(slot: Slot, fmt: StreamFormat) {
    if (!slot.urls || slot.format === fmt) return;
    // 软解态只允许 FLV 系之间切换(下拉已置灰, 此处兜住程序化调用)
    if (slot.softDecode && !FLV_FAMILY.includes(fmt)) return;
    slot.format = fmt;
    attachPlayer(slot);
  }

  function onFormatChange(slot: Slot, val: unknown) {
    void switchFormat(slot, val as StreamFormat);
  }

  async function stopSlot(slot: Slot) {
    disarmTimeout(slot);
    players.get(slot.key)?.destroy();
    players.delete(slot.key);
    const streamId = slot.streamId;
    slot.streamId = undefined;
    slot.urls = undefined;
    if (streamId) {
      await playApi.stop(streamId).catch(() => {});
    }
    // 对讲随通道一起收(防火: pusher 释放 + REST stop 尽力而为)
    const talkPusher = talkPushers.get(slot.key);
    if (slot.talkId || talkPusher) {
      talkPusher?.stop();
      talkPushers.delete(slot.key);
      const talkId = slot.talkId;
      slot.talkState = 'idle';
      slot.talkId = undefined;
      if (talkId) await talkApi.stop(talkId).catch(() => {});
    }
    slot.status = 'idle';
    slot.channel = null;
    slot.error = undefined;
    slot.softDecode = false;
  }

  function closeAll() {
    for (const s of slots.value) void stopSlot(s);
  }

  function replay(slot: Slot) {
    if (slot.channel) void startPlayInto(slot, slot.channel);
  }

  function setLayout(n: number) {
    // 注意: radio 的 v-model 已先更新 layout.value, 此处不可用 n===layout.value 提前返回
    // (那会让分屏切换永远不生效)
    const old = slots.value;
    for (let i = n; i < old.length; i += 1) {
      const s = old[i];
      if (s) void stopSlot(s);
    }
    slots.value = [...old.slice(0, n), ...makeSlots(Math.max(0, n - old.length))];
    layout.value = n;
    if (!slots.value.some((s) => s.key === activeKey.value)) {
      activeKey.value = slots.value[0]?.key ?? null;
    }
  }

  // ---------- 云台 ----------
  /**
   * 云台入口判定: ptzEnabled=1 强制开启优先（类型漏报的人工兜底——LiveGBS 类平台目录不报
   * PTZType, 球机靠通道信息补录类型或此处强制开启; 后端 PtzControlService 对 ptzType=0
   * 仅 WARN 放行, 口径一致）; 自动口径= ptzType>0 且未被禁用（ptzEnabled=0 优先拦截）。
   */
  function canPtz(slot: Slot) {
    const ch = slot.channel;
    if (!ch) return false;
    const allowed = ch.ptzEnabled === 1 || (ch.ptzType > 0 && ch.ptzEnabled !== 0);
    return allowed && auth.hasPerm('ptz:control');
  }

  /** 对讲开关(平台直推): 播放中 + 具备 talk:talk 权限 */
  function canTalk(slot: Slot) {
    return !!slot.channel && slot.status === 'playing' && auth.hasPerm('talk:talk');
  }

  /** 开/关对讲(切换按钮): start → WS 推流 → begin; 再次点击 → stop */
  async function toggleTalk(slot: Slot) {
    if (!slot.channel) return;
    const pusher = talkPushers.get(slot.key);
    if (slot.talkId || pusher) {
      // 结束对讲
      const talkId = slot.talkId;
      pusher?.stop();
      talkPushers.delete(slot.key);
      slot.talkState = 'idle';
      slot.talkId = undefined;
      if (talkId) {
        try {
          await talkApi.stop(talkId);
        } catch { /* 会话可能已被设备 BYE/超时清理 */ }
      }
      return;
    }
    slot.talkState = 'connecting';
    let start;
    try {
      start = await talkApi.start(slot.channel.id);
    } catch (e) {
      slot.talkState = 'idle';
      Message.error((e as Error).message || '对讲发起失败');
      return;
    }
    slot.talkId = start.talkId;
    const np = new TalkPusher(start.pushWsUrl, (state) => {
      // WS 意外断开(设备 BYE/超时): 复位本地状态
      if (state === 'closed' && slot.talkState === 'talking') {
        talkPushers.delete(slot.key);
        slot.talkState = 'idle';
        slot.talkId = undefined;
      }
    });
    talkPushers.set(slot.key, np);
    const ok = await np.start();
    if (!ok) {
      np.stop();
      talkPushers.delete(slot.key);
      slot.talkState = 'idle';
      slot.talkId = undefined;
      Message.warning('麦克风不可用或权限被拒绝');
      try {
        await talkApi.stop(start.talkId);
      } catch { /* ignore */ }
      return;
    }
    try {
      await talkApi.begin(start.talkId);
      slot.talkState = 'talking';
    } catch (e) {
      np.stop();
      talkPushers.delete(slot.key);
      slot.talkState = 'idle';
      slot.talkId = undefined;
      Message.error((e as Error).message || '对讲开始失败');
    }
  }

  /** 树行上的「云台」标记: 同一套开关语义 */
  function showPtzTag(ch: ChannelItem) {
    return ch.ptzType > 0 && ch.ptzEnabled !== 0;
  }

  /** 1009 提示节流: 摇杆连续指令会连发限流, 3 秒内只提示一次 */
  const PTZ_LIMIT_TIP_GAP = 3000;
  let ptzLimitTipAt = 0;

  function onPtz(slot: Slot, cmd: PtzCommand, speed: number) {
    if (!slot.channel) return;
    void ptzApi.control(slot.channel.id, cmd, speed).catch((e) => {
      // 仅限流(业务码 1009)给提示, 其余错误维持静默（通用失败提示由 http 拦截器统一弹出）
      if ((e as { code?: number }).code !== 1009) return;
      const now = Date.now();
      if (now - ptzLimitTipAt < PTZ_LIMIT_TIP_GAP) return;
      ptzLimitTipAt = now;
      Message.warning('云台指令发送过于频繁，请稍后再试');
    });
  }

  // ---------- WS 事件 ----------
  function onWs(type: string, data: Record<string, unknown>) {    if (type === WsEvent.StreamReady) {
      const streamId = data.streamId as string | undefined;
      // inviting 之外还接受 error: 降级续期后第二条 15s 起播定时器先到期把格置 error
      // (慢设备 TCP 连接+首帧超过 15s), 真正的 STREAM_READY 随后才到, 不能被丢弃
      // B5: 后续 schema 注册补投的 STREAM_READY(迟到编码)到达时格子可能已 playing——
      // 三种态都接住: inviting/error 照旧起播, playing 仅按需补做 H265 网关
      const slot = slots.value.find(
        (s) =>
          s.streamId === streamId &&
          (s.status === 'inviting' || s.status === 'error' || s.status === 'playing')
      );
      const wasPlaying = slot?.status === 'playing';
      if (slot) {
        slot.hint = undefined;
        if (slot.status === 'error') {
          slot.error = undefined;
          slot.status = 'inviting';
        }
        slot.urls = (data.urls as StreamUrls) ?? {};
        slot.videoCodec = (data.videoCodec as string) || undefined;
        if (wasPlaying && shouldSoftDecode(slot.videoCodec, slot.format ?? 'wsFlv')) {
          // 迟到编码: 已起播才发现 H265 且无硬解 → 拆 mpegts 改软解重挂(attachPlayer 内部命中软解分支)
          players.get(slot.key)?.destroy();
          players.delete(slot.key);
          attachPlayer(slot);
        } else if (!wasPlaying) {
          attachPlayer(slot);
        }
      }
    } else if (type === WsEvent.StreamClosed) {
      const streamId = data.streamId as string | undefined;
      const slot = slots.value.find((s) => s.streamId === streamId);
      if (slot && (slot.status === 'playing' || slot.status === 'inviting')) {
        players.get(slot.key)?.destroy();
        players.delete(slot.key);
        disarmTimeout(slot);
        const reason = (data.reason as string) || '';
        // 自愈重播(M5-B3): none_reader 是媒体节点"连续 N 秒 0 读者"判死, 与"用户确实不看了"
        // 不等价（起播空窗/断线重连/软解冷加载都会命中; 后端已抬到 60s 并做关前复核, 此处兜住
        // 其余竞态）——把"必须手点重试"改成 2 秒内自愈。后端关流不杀常驻代理, 重播走复用即刻出画。
        if (reason === 'none_reader' && slot.channel
            && Date.now() - (slot.autoRetryAt ?? 0) > AUTO_RETRY_GAP) {
          slot.autoRetryAt = Date.now();
          slot.status = 'inviting';
          slot.error = undefined;
          slot.hint = '流被媒体节点回收，正在自动重连…';
          void startPlayInto(slot, slot.channel, true);
        } else {
          slot.status = 'error';
          slot.error = `流已关闭（${reason || '未知原因'}）`;
        }
      }
    } else if (type === WsEvent.StreamFallback) {
      // UDP 收流超时 → 后端已转 TCP re-INVITE: 保持/回到邀请态续期等待, 不报错
      const streamId = data.streamId as string | undefined;
      const slot = slots.value.find(
        (s) => s.streamId === streamId && (s.status === 'inviting' || s.status === 'error')
      );
      if (slot) {
        // 原 15s 起播超时恰好先于本事件触发: 先恢复邀请态
        if (slot.status === 'error') {
          slot.status = 'inviting';
          slot.error = undefined;
        }
        slot.hint = 'UDP 收流超时，已自动切换 TCP 重新邀请…';
        armTimeout(slot);
      }
    } else if (type === WsEvent.DeviceOnline || type === WsEvent.DeviceOffline) {
      // 设备上下线: 树行在线标记与统计条实时翻转
      const devId = data.deviceId as string | undefined;
      const g = groups.value.find((x) => x.device.deviceId === devId);
      if (g) g.device.online = type === WsEvent.DeviceOnline ? 1 : 0;
    } else if (type === WsEvent.ChannelChange) {
      // 目录增删(重报/同步) → 防抖刷新通道总数
      scheduleChannelTotalRefresh();
    }
  }

  // ---------- 视频元素引用 ----------
  function setVideoRef(key: number) {
    return (el: unknown) => {
      if (el) videoEls.set(key, el as HTMLVideoElement);
      else videoEls.delete(key);
    };
  }

  // ---------- 截图 ----------
  async function snapshot(slot: Slot) {
    const video = videoEls.get(slot.key);
    if (!video) return;
    const title = slot.channel?.name || slot.streamId || 'snapshot';
    // 软解时画面在 jessibuca canvas(video 元素无帧), 统一走 adapter 入口自动分派;
    // adapter 缺位(异常)时回退 video 直截
    const r = await (players.get(slot.key)?.capture(title) ?? captureFrame(video, title));
    if (r.ok) {
      Message.success('截图已保存到浏览器下载目录');
    } else if (r.reason === 'not-ready') {
      Message.warning('视频尚未出画, 暂无法截图');
    } else {
      Message.error('截图失败');
    }
  }

  // ---------- 生命周期 ----------
  function onPageHide() {
    // 浏览器卸载兜底: sendBeacon 带不了 Bearer, 用 fetch keepalive
    for (const s of slots.value) {
      if (s.streamId) stopStreamKeepalive(s.streamId, auth.token);
    }
  }

  onMounted(() => {
    tokenAtMount = auth.token;
    slots.value = makeSlots(layout.value);
    activeKey.value = slots.value[0]?.key ?? null;
    void loadGroups();
    void loadChannelTotal();
    unbindWs = wsClient.on(onWs);
    window.addEventListener('pagehide', onPageHide);
  });

  onUnmounted(() => {
    unbindWs?.();
    window.removeEventListener('pagehide', onPageHide);
    window.clearTimeout(channelTotalTimer);
    // 本页 ignoreCache: 切走页签即销毁, 批量停流(D17 资源最小化)。
    // 用挂载时的 token 快照走 keepalive 停流——退出登录场景下 store 已被 reset, axios 带不上 Bearer
    for (const s of slots.value) {
      disarmTimeout(s);
      players.get(s.key)?.destroy();
      players.delete(s.key);
      if (s.streamId) stopStreamKeepalive(s.streamId, tokenAtMount);
      // 对讲收尾: 释放麦克风(系统级独占资源, 必须显式停)
      talkPushers.get(s.key)?.stop();
      talkPushers.delete(s.key);
      s.talkState = 'idle';
      if (s.talkId) void talkApi.stop(s.talkId).catch(() => {});
      s.talkId = undefined;
    }
  });
</script>

<template>
  <div class="live-page">
    <aside class="live-side">
      <div class="side-head">
        <a-input-search v-model="keyword" placeholder="搜索通道" allow-clear />
        <a-radio-group
          v-model="treeView"
          type="button"
          size="small"
          class="view-switch"
          @change="onViewChange"
        >
          <a-radio value="catalog">目录</a-radio>
          <a-radio value="group">分组</a-radio>
        </a-radio-group>
      </div>
      <div class="side-body">
        <template v-if="keywordTrim">
          <div v-if="!searchResults.length" class="side-empty">无匹配通道</div>
          <div
            v-for="ch in searchResults"
            :key="ch.id"
            class="ch-item"
            :class="{ 'ch-item-dir': ch.directory }"
            :data-channel-id="ch.channelId"
            :title="ch.directory ? '目录节点, 不可播放' : '双击播放'"
            @dblclick="!ch.directory && playChannel(ch)"
          >
            <span class="ch-dot" :class="ch.directory ? 'dir' : ch.status === 1 ? 'on' : 'off'" />
            <span class="ch-name">{{ ch.name }}</span>
            <span v-if="ch.directory" class="ch-dir">目录</span>
            <span v-if="showPtzTag(ch)" class="ch-ptz">云台</span>
            <span class="ch-dev">{{ ch.deviceName }}</span>
          </div>
        </template>
        <!-- 分组视图: /groups/channel-tree, 分组节点=文件夹不可播放, 通道叶子与目录视图同款 -->
        <template v-else-if="treeView === 'group'">
          <div v-if="groupLoading" class="side-empty">加载中…</div>
          <div v-else-if="!groupTree.length" class="side-empty side-empty-group">
            <template v-if="groupEmptyReason === 'unauthorized'">
              当前账号未被授权任何分组，请联系管理员
            </template>
            <template v-else>
              <span>暂无分组</span>
              <a-link
                v-if="auth.hasPerm('group:list')"
                class="empty-cta"
                @click="goGroupManage"
              >
                去创建
              </a-link>
            </template>
          </div>
          <div
            v-for="row in groupRows"
            :key="row.node.key"
            class="gt-row"
            :style="{ paddingLeft: `${row.depth * 14 + 4}px` }"
          >
            <template v-if="row.node.isGroup">
              <div class="gt-group" @click="toggleGroup(row.node)">
                <icon-down class="gt-arrow" :class="{ open: groupExpanded.has(row.node.key) }" />
                <icon-folder class="gt-icon" />
                <span class="gt-name">{{ row.node.title }}</span>
                <span class="gt-count">{{ row.node.children?.length || 0 }}</span>
              </div>
            </template>
            <div
              v-else-if="row.node.channel"
              class="ch-item"
              :data-channel-id="row.node.channel.channelId"
              title="双击播放"
              @dblclick="playChannel(row.node.channel)"
            >
              <span class="ch-dot" :class="row.node.channel.status === 1 ? 'on' : 'off'" />
              <span class="ch-name">{{ row.node.channel.name }}</span>
              <span v-if="showPtzTag(row.node.channel)" class="ch-ptz">云台</span>
            </div>
          </div>
        </template>
        <a-collapse
          v-else
          :bordered="false"
          :expanded-keys="expandedKeys"
          expand-icon-position="right"
          @change="onExpandedChange"
        >
          <a-collapse-item
            v-for="g in groups"
            :key="g.device.id"
            :header="g.device.name || g.device.deviceId"
          >
            <template #header>
              <span class="g-name">{{ g.device.name || g.device.deviceId }}</span>
              <a-tag v-if="g.device.deviceType === 2" color="purple" size="small">级联</a-tag>
              <a-tag size="small" :color="g.device.online === 1 ? 'green' : 'gray'">
                {{ g.device.online === 1 ? '在线' : '离线' }}
              </a-tag>
            </template>
            <template #expand-icon="{ active }"><icon-down :class="active ? 'rot' : ''" /></template>
            <div v-if="g.loading" class="side-empty">加载中…</div>
            <template v-else>
              <!-- 下级平台: parental 分组树（分组行折叠, 通道叶子双击播放） -->
              <template v-if="g.device.deviceType === 2">
                <div v-if="!g.cascadeTree.length" class="side-empty">设备无通道</div>
                <template v-for="row in cascadeRows(g)" :key="`${g.device.id}-${row.node.key}`">
                  <div
                    v-if="row.node.isGroup"
                    class="gt-row gt-row-cascade"
                    :style="{ paddingLeft: `${row.depth * 14 + 4}px` }"
                    @click="toggleCascade(g, row.node)"
                  >
                    <div class="gt-group">
                      <icon-down
                        class="gt-arrow"
                        :class="{ open: cascadeExpanded.has(`${g.device.id}|${row.node.key}`) }"
                      />
                      <icon-folder class="gt-icon" />
                      <span class="gt-name">{{ row.node.title }}</span>
                      <span class="gt-count">{{ row.node.children.length }}</span>
                    </div>
                  </div>
                  <div
                    v-else
                    class="ch-item"
                    :style="{ paddingLeft: `${row.depth * 14 + 10}px` }"
                    :data-channel-id="row.node.key"
                    title="双击播放"
                    @dblclick="row.node.channel && playChannel(row.node.channel)"
                  >
                    <span
                      class="ch-dot"
                      :class="row.node.channel?.status === 1 ? 'on' : 'off'"
                    />
                    <span class="ch-name">{{ row.node.title }}</span>
                    <span v-if="row.node.channel && showPtzTag(row.node.channel)" class="ch-ptz">
                      云台
                    </span>
                  </div>
                </template>
              </template>
              <template v-else>
                <div v-if="!g.channels.length" class="side-empty">设备无通道</div>
                <div
                  v-for="ch in g.channels"
                  :key="ch.id"
                  class="ch-item"
                  :data-channel-id="ch.channelId"
                  title="双击播放"
                  @dblclick="playChannel(ch)"
                >
                  <span class="ch-dot" :class="ch.status === 1 ? 'on' : 'off'" />
                  <span class="ch-name">{{ ch.name }}</span>
                  <span v-if="showPtzTag(ch)" class="ch-ptz">云台</span>
                </div>
              </template>
            </template>
          </a-collapse-item>
        </a-collapse>
      </div>
      <!-- 统计条: 状态栏惯例置底, 不与树争垂直空间且树滚动时恒可见 -->
      <div class="side-foot">
        <template v-if="treeView === 'group'">
          <span>分组 <b>{{ groupStats.groups }}</b></span>
          <span>通道 <b>{{ groupStats.channels }}</b></span>
        </template>
        <template v-else>
          <span><span class="sf-dot" />在线 <b>{{ onlineCount }}</b>/{{ deviceTotal || '…' }}</span>
          <span>通道 <b>{{ channelTotal ?? '…' }}</b></span>
        </template>
      </div>
    </aside>

    <main class="live-main">
      <div class="grid-toolbar">
        <a-radio-group v-model="layout" type="button" size="small" @change="setLayout(layout)">
          <a-radio v-for="n in LAYOUTS" :key="n" :value="n">{{ n }} 分屏</a-radio>
        </a-radio-group>
        <a-button size="small" :disabled="!hasPlaying" @click="closeAll">全部关闭</a-button>
      </div>

      <div class="grid" :class="`grid-${layout}`">
        <div
          v-for="slot in slots"
          :key="slot.key"
          class="cell"
          :class="[`st-${slot.status}`, { active: slot.key === activeKey }]"
          :data-state="slot.status"
          :data-stream="slot.streamId || ''"
          :data-soft="slot.softDecode ? '1' : ''"
          @click="activeKey = slot.key"
        >
          <template v-if="slot.channel">
            <div class="cell-screen">
              <video :ref="setVideoRef(slot.key)" class="cell-video" playsinline />

              <!-- 关闭: 固定画面右上角常显(通用窗格交互), 不随底条 -->
              <a-button
                size="mini"
                class="cell-close"
                title="关闭"
                aria-label="关闭"
                @click.stop="stopSlot(slot)"
              >
                <template #icon><icon-close /></template>
              </a-button>

              <div v-if="slot.status === 'inviting'" class="cell-hint">
                <a-spin :size="20" />
                <span>{{ slot.hint || '正在邀请点播…' }}</span>
              </div>
              <div v-else-if="slot.status === 'error'" class="cell-hint">
                <span>{{ slot.error }}</span>
                <a-link @click.stop="replay(slot)">重试</a-link>
              </div>
            </div>

            <!-- 底部控制条: 常显深色(与回放播放器同一视觉语言), 名称居左, 操作 icon 化居右 -->
            <div class="cell-bar" @click.stop>
              <span class="cell-name" :title="slot.channel.name">
                <i v-if="slot.status === 'playing'" class="live-dot" />
                {{ slot.channel.name }}
                <span v-if="slot.videoCodec" class="cell-codec">{{ slot.videoCodec }}</span>
                <span
                  v-if="slot.softDecode"
                  class="cell-soft"
                  title="当前设备无 HEVC 硬解，已自动启用 WASM 软解码（延迟与 CPU 占用略增）"
                >软解</span>
              </span>
              <span class="cell-ops">
                <a-tooltip v-if="slot.status === 'playing'" content="截图保存到下载目录" position="top">
                  <a-button size="mini" class="bar-btn" title="截图保存到下载目录" @click="snapshot(slot)">
                    <template #icon><icon-camera /></template>
                  </a-button>
                </a-tooltip>
                <!-- 传输格式: 软解态只留 FLV 系可选(WS-FLV / HTTP-FLV)——jessibuca 只带 FLV
                     解封装 + ws/http 取流, ts/hls/rtc 在无硬解机器上也走不通, 故置灰防误切 -->
                <a-dropdown
                  v-if="slot.status === 'playing'"
                  trigger="click"
                  @select="onFormatChange(slot, $event)"
                >
                  <a-button
                    size="mini"
                    class="bar-btn fmt-btn"
                    :title="slot.softDecode
                      ? '软解模式仅支持 FLV 传输（WebSocket / HTTP）'
                      : '切换传输格式'"
                  >
                    {{ FMT_SHORT[slot.format ?? 'wsFlv'] ?? 'WS' }}
                    <icon-caret-down />
                  </a-button>
                  <template #content>
                    <a-doption
                      v-for="f in FMT_OPTIONS"
                      :key="f.value"
                      :value="f.value"
                      :disabled="slot.softDecode && !FLV_FAMILY.includes(f.value)"
                      :class="{ 'fmt-active': (slot.format ?? 'wsFlv') === f.value }"
                    >
                      {{ f.label }}
                    </a-doption>
                  </template>
                </a-dropdown>
                <a-popover
                  v-if="canPtz(slot)"
                  position="rt"
                  :content-style="{ padding: '12px' }"
                >
                  <a-button size="mini" class="bar-btn" title="云台">
                    <template #icon><icon-robot /></template>
                  </a-button>
                  <template #content>
                    <PtzJoystick
                      :channel-db-id="slot.channel.id"
                      @move="(cmd, spd) => onPtz(slot, cmd, spd)"
                    />
                  </template>
                </a-popover>
                <a-tooltip
                  v-if="canTalk(slot) || (slot.talkState && slot.talkState !== 'idle')"
                  :content="slot.talkState === 'talking' ? '结束对讲' : '开始对讲(平台向设备喊话)'"
                  position="top"
                >
                  <a-button
                    size="mini"
                    class="bar-btn"
                    :class="{ 'talk-on': slot.talkState === 'talking' }"
                    :loading="slot.talkState === 'connecting'"
                    :aria-label="slot.talkState === 'talking' ? '结束对讲' : '开始对讲'"
                    @click="toggleTalk(slot)"
                  >
                    <template #icon><icon-record /></template>
                  </a-button>
                </a-tooltip>
              </span>
            </div>
          </template>
          <div v-else class="cell-empty">空闲窗口 · 双击左侧通道播放</div>
        </div>
      </div>
    </main>
  </div>
</template>

<style scoped lang="less">
  .live-page {
    display: flex;
    gap: 16px;
    // 路由 meta.fill 打通视口高度链(layout-fill/page-fill)后, 此处 flex:1 精确填满剩余高度:
    // 左侧树多时滚动发生在 .side-body, 整页不出滚动条; min-height 仅作极小窗口下限
    flex: 1;
    min-height: 480px;
  }

  .live-side {
    display: flex;
    flex-direction: column;
    width: 300px;
    flex-shrink: 0;
    background: var(--color-bg-2);
    border-radius: 4px;
    overflow: hidden;

    .side-head {
      padding: 12px;
      border-bottom: 1px solid var(--color-border);

      .view-switch {
        display: flex;
        margin-top: 8px;
      }
    }

    .side-body {
      flex: 1;
      overflow: auto;
      padding: 4px 8px 12px;
    }

    // 统计条: 12px 弱化文字 + 数字微强调(等宽数字防跳动), 与树内容拉开视觉层级
    .side-foot {
      display: flex;
      align-items: center;
      justify-content: space-between;
      flex-shrink: 0;
      padding: 6px 12px;
      border-top: 1px solid var(--color-border);
      font-size: 12px;
      color: var(--color-text-3);

      b {
        font-weight: 500;
        color: var(--color-text-2);
        font-variant-numeric: tabular-nums;
      }

      .sf-dot {
        display: inline-block;
        width: 6px;
        height: 6px;
        margin-right: 4px;
        border-radius: 50%;
        background: rgb(var(--green-6));
      }
    }

    :deep(.arco-collapse-item-content) {
      background: transparent;
      padding: 0;
    }

    :deep(.arco-collapse-item-content-box) {
      padding: 0 0 4px;
    }

    .g-name {
      margin-right: 8px;
    }
  }

  .ch-item {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 6px 8px;
    border-radius: 4px;
    cursor: pointer;

    &:hover {
      background: var(--color-fill-2);
    }

    .ch-dot {
      width: 6px;
      height: 6px;
      border-radius: 50%;
      flex-shrink: 0;

      &.on {
        background: rgb(var(--green-6));
      }

      &.off {
        background: var(--color-text-4);
      }

      // 目录节点: 方形灰标, 与在线状态点区分(目录的 ON/OFF 无业务意义)
      &.dir {
        width: 7px;
        height: 7px;
        border-radius: 1px;
        background: var(--color-text-3);
      }
    }

    .ch-name {
      flex: 1;
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      font-size: 13px;
    }

    // 目录节点标注: 灰底小签, 与"云台"蓝签同形
    .ch-dir {
      flex-shrink: 0;
      font-size: 11px;
      color: var(--color-text-2);
      background: var(--color-fill-3);
      padding: 0 6px;
      border-radius: 2px;
    }

    .ch-ptz {
      flex-shrink: 0;
      font-size: 11px;
      color: rgb(var(--arcoblue-6));
      background: rgb(var(--arcoblue-1));
      padding: 0 6px;
      border-radius: 2px;
    }

    .ch-dev {
      flex-shrink: 0;
      max-width: 80px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      font-size: 12px;
      color: var(--color-text-3);
    }
  }

  .side-empty {
    padding: 12px;
    font-size: 12px;
    color: var(--color-text-3);
    text-align: center;

    // 分组视图空态: 文案 + 可选的「去创建」入口上下排列
    &.side-empty-group {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 4px;
    }

    .empty-cta {
      font-size: 12px;
    }
  }

  // 分组视图行（分组节点 + 缩进的通道叶子）
  .gt-row {
    min-width: 0;
  }

  .gt-group {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 6px 8px;
    border-radius: 4px;
    cursor: pointer;

    &:hover {
      background: var(--color-fill-2);
    }

    .gt-arrow {
      flex-shrink: 0;
      font-size: 12px;
      color: var(--color-text-3);
      transition: transform 0.15s;

      &.open {
        transform: rotate(-180deg);
      }
    }

    .gt-icon {
      flex-shrink: 0;
      font-size: 14px;
      color: rgb(var(--arcoblue-6));
    }

    .gt-name {
      flex: 1;
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      font-size: 13px;
    }

    .gt-count {
      flex-shrink: 0;
      font-size: 11px;
      color: var(--color-text-4);
    }
  }

  .live-main {
    display: flex;
    flex-direction: column;
    flex: 1;
    min-width: 0;
    background: var(--color-bg-2);
    border-radius: 4px;
    padding: 12px;

    .grid-toolbar {
      display: flex;
      align-items: center;
      justify-content: space-between;
      margin-bottom: 12px;
    }
  }

  .grid {
    flex: 1;
    display: grid;
    gap: 8px;
    min-height: 0;
  }

  .grid-1 {
    grid-template-columns: 1fr;
    grid-template-rows: 1fr;
  }

  .grid-4 {
    grid-template-columns: repeat(2, 1fr);
    grid-template-rows: repeat(2, 1fr);
  }

  .grid-9 {
    grid-template-columns: repeat(3, 1fr);
    grid-template-rows: repeat(3, 1fr);
  }

  .grid-16 {
    grid-template-columns: repeat(4, 1fr);
    grid-template-rows: repeat(4, 1fr);
  }

  .cell {
    position: relative;
    display: flex;
    flex-direction: column;
    min-height: 0;
    background: #000;
    border-radius: 4px;
    overflow: hidden;
    border: 2px solid transparent;
    cursor: pointer;

    &.active {
      border-color: rgb(var(--arcoblue-6));
    }

    &.st-idle {
      background: var(--color-fill-2);
    }

    // 画面区: 底条以上全部空间; 邀请/错误提示层只盖画面不盖控制条
    .cell-screen {
      position: relative;
      flex: 1;
      min-height: 0;

      .cell-video {
        width: 100%;
        height: 100%;
        object-fit: contain;
      }

      .cell-hint {
        position: absolute;
        inset: 0;
        z-index: 2;
        display: flex;
        align-items: center;
        justify-content: center;
        gap: 8px;
        color: #fff;
        font-size: 13px;
        background: rgba(0, 0, 0, 0.45);
      }

      // 关闭: 固定画面右上角常显(通用窗格交互), 半透明黑底白 ✕
      .cell-close {
        position: absolute;
        top: 4px;
        right: 4px;
        z-index: 3;
        width: 22px;
        height: 22px;
        padding: 0;
        color: rgba(255, 255, 255, 0.85);
        background: rgba(0, 0, 0, 0.45);
        border: none;

        &:hover {
          color: #fff;
          background: rgba(0, 0, 0, 0.7);
        }
      }
    }

    // 底部控制条: 常显深色(与回放播放器同一视觉语言), 取代旧的顶部 hover 悬浮工具栏——
    // LIVE 红点/名称/编码居左, 截图/传输/云台/对讲/关闭 icon 化居右
    .cell-bar {
      display: flex;
      align-items: center;
      gap: 6px;
      flex-shrink: 0;
      height: 26px;
      padding: 0 6px;
      background: #1d2129;
      color: rgba(255, 255, 255, 0.9);

      .cell-name {
        flex: 1;
        min-width: 0;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        font-size: 12px;

        // B5: 编码徽标(H264/H265), videoCodec 空串(未知)时不显示
        .cell-codec {
          display: inline-block;
          margin-left: 4px;
          padding: 0 4px;
          border: 1px solid rgba(255, 255, 255, 0.5);
          border-radius: 2px;
          font-size: 11px;
          line-height: 14px;
          vertical-align: 1px;
        }

        // 软解徽标: H.265 无硬解自动降级 jessibuca WASM 软解, 橙色警示(与编码白徽标区分)
        .cell-soft {
          display: inline-block;
          margin-left: 4px;
          padding: 0 4px;
          border: 1px solid rgb(255, 153, 0);
          border-radius: 2px;
          color: rgb(255, 153, 0);
          font-size: 11px;
          line-height: 14px;
          vertical-align: 1px;
          cursor: help;
        }
      }

      // 直播红点(playing 态): 呼吸闪烁, 监控语义的「实时」标识
      .live-dot {
        flex-shrink: 0;
        width: 6px;
        height: 6px;
        margin-right: 4px;
        background: #f53f3f;
        border-radius: 50%;
        animation: live-blink 2s ease-in-out infinite;
      }

      .cell-ops {
        display: inline-flex;
        align-items: center;
        flex-shrink: 0;
        gap: 2px;
      }

      .bar-btn {
        padding: 0 4px;
        color: rgba(255, 255, 255, 0.9);
        background: transparent;
        border: none;

        &:hover {
          color: #fff;
          background: rgba(255, 255, 255, 0.12);
        }

        // 对讲中: 呼吸橙提示占用麦克风
        &.talk-on {
          color: #ff7d00;
          animation: talk-pulse 1.2s ease-in-out infinite;
        }
      }
    }

    .cell-empty {
      position: absolute;
      inset: 0;
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 13px;
      color: var(--color-text-3);
      background: var(--color-fill-2);
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

  .rot {
    transform: rotate(-180deg);
  }

  @keyframes talk-pulse {
    0%,
    100% {
      opacity: 1;
    }
    50% {
      opacity: 0.55;
    }
  }
</style>
