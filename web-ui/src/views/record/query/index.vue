<script setup lang="ts">
  import { computed, onActivated, onMounted, ref, watch } from 'vue';
  import { Message, Modal } from '@arco-design/web-vue';
  import { useRoute } from 'vue-router';
  import { deviceApi } from '@/api/device';
  import type { ChannelTreeNode, GbChannel } from '@/api/device';
  import { queryRecord } from '@/api/record';
  import type { RecordQueryResult } from '@/api/record';
  import { cloudRecordApi } from '@/api/cloudRecord';
  import type { CloudRecord } from '@/api/cloudRecord';
  import { useAuthStore } from '@/stores/auth';
  import { fmtDateTime } from '@/utils/format';
  import PlaybackArea from './components/PlaybackArea.vue';
  import RecordPlanDrawer from './components/RecordPlanDrawer.vue';

  /**
   * 录像检索（M5-B2 体验批重构）: 左树右内容工作台, 与实时预览页同范式。
   * 左栏=通道树(搜索/在线点/目录灰标, 双击通道查询); 右栏=条件栏 → 回放播放器(主体)
   * → 时间轴(操作中枢, 设备绿/云端橙叠加) → 折叠明细(设备/云端两页签)。
   * 时间筛选升级为日期+时间(到分钟; 后端 /record/query 本就按秒级时间段查询)。
   * 页面五态: idle 未查询 / loading / done / done 空结果 / error(alert 呈现)。
   */

  // ---------- 纯函数: 时间换算 ----------
  const pad = (n: number) => String(n).padStart(2, '0');
  const DAY_MS = 86_400_000;
  const WEEK = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];

  /** "yyyy-MM-dd HH:mm:ss" → 本地毫秒值（非法输入返回 NaN） */
  function parseTime(s: string): number {
    if (!s) return Number.NaN;
    return new Date(s.replace(' ', 'T')).getTime();
  }

  /** 毫秒值 → 当天 0 点 */
  function dayStartOf(t: number): number {
    const d = new Date(t);
    d.setHours(0, 0, 0, 0);
    return d.getTime();
  }

  /** yyyy-MM-dd */
  function fmtDay(d: Date): string {
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  }

  /** 当天时刻 HH:mm:ss */
  function fmtClock(t: number): string {
    const d = new Date(t);
    return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
  }

  /** 秒 → "1 小时 0 分" / "45 分钟" / "30 秒" */
  function fmtDuration(sec: number): string {
    if (sec < 60) return `${Math.max(0, Math.round(sec))} 秒`;
    const min = Math.round(sec / 60);
    if (min < 60) return `${min} 分钟`;
    return `${Math.floor(min / 60)} 小时 ${min % 60} 分`;
  }

  // ---------- 左侧通道树（直播页同范式手写树: 目录行折叠 + 通道行状态点 + 双击查询） ----------
  interface RTreeNode {
    channel: GbChannel;
    children: RTreeNode[];
  }
  /** 拍平后的可见行（含层级深度, 模板按 depth 缩进） */
  interface TreeRow {
    node: RTreeNode;
    depth: number;
  }

  const treeRows = ref<RTreeNode[]>([]);
  const treeLoading = ref(false);
  const keyword = ref('');
  /** 展开的目录节点(通道库 id)集合; 搜索时自动全展开 */
  const expandedIds = ref<Set<number>>(new Set());
  /** 选中的通道叶子(通道库 id); 目录节点不可选 */
  const selectedId = ref<number | null>(null);

  function mapTree(nodes: ChannelTreeNode[]): RTreeNode[] {
    return nodes.map((n) => ({
      channel: n.channel,
      children: mapTree(n.children ?? [])
    }));
  }

  /** 目录判定与直播页 isGroup 同口径: 上报 parental=1 或 挂有 children——LiveGBS 对目录节点连 parental 都报 0 */
  function isDirNode(node: RTreeNode): boolean {
    return node.channel.parental === 1 || node.children.length > 0 || node.channel.directory === true;
  }

  function nodeTitle(node: RTreeNode): string {
    return node.channel.customName || node.channel.name || node.channel.channelId;
  }

  const filterText = computed(() => keyword.value.trim().toLowerCase());

  /** 关键字命中本行或任一后代（命中的目录行作为路径保留并强制展开） */
  function matchNode(node: RTreeNode): boolean {
    if (!filterText.value) return true;
    if (nodeTitle(node).toLowerCase().includes(filterText.value)) return true;
    return node.children.some(matchNode);
  }

  const visibleRows = computed<TreeRow[]>(() => {
    const rows: TreeRow[] = [];
    const walk = (nodes: RTreeNode[], depth: number) => {
      for (const n of nodes) {
        if (!matchNode(n)) continue;
        rows.push({ node: n, depth });
        if (n.children.length && (filterText.value || expandedIds.value.has(n.channel.id))) {
          walk(n.children, depth + 1);
        }
      }
    };
    walk(treeRows.value, 0);
    return rows;
  });

  function toggleDir(node: RTreeNode) {
    const id = node.channel.id;
    const next = new Set(expandedIds.value);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    expandedIds.value = next;
  }

  function onNodeClick(node: RTreeNode) {
    if (isDirNode(node)) {
      toggleDir(node);
      return;
    }
    // 单击只选中; 查询由「查询」按钮或双击触发（RecordInfo 需设备应答, 防误点就发 15s 查询）
    selectedId.value = node.channel.id;
  }

  function onNodeDblClick(node: RTreeNode) {
    if (isDirNode(node)) return;
    selectedId.value = node.channel.id;
    void doQuery();
  }

  /** 选中通道显示名（回放信息行用） */
  const channelLabel = computed(() => {
    const find = (nodes: RTreeNode[]): string => {
      for (const n of nodes) {
        if (n.channel.id === selectedId.value) return nodeTitle(n);
        const hit = find(n.children);
        if (hit) return hit;
      }
      return '';
    };
    return find(treeRows.value);
  });

  function findNode(nodes: RTreeNode[], id: number): RTreeNode | null {
    for (const n of nodes) {
      if (n.channel.id === id) return n;
      const hit = findNode(n.children, id);
      if (hit) return hit;
    }
    return null;
  }

  async function loadTree(silent = false) {
    if (!silent) treeLoading.value = true;
    try {
      treeRows.value = mapTree(await deviceApi.channelTree());
      // 默认展开全部目录层级（通道树体量小, 展开即所见; 搜索时本就强制展开）
      const all = new Set<number>();
      const walk = (nodes: RTreeNode[]) => {
        for (const n of nodes) {
          if (n.children.length) {
            all.add(n.channel.id);
            walk(n.children);
          }
        }
      };
      walk(treeRows.value);
      expandedIds.value = all;
      // 通道列表「录像」入口跳转预选: /record/query?channelDbId=123 → 选中并按当前条件查一次
      const preset = Number(route.query.channelDbId);
      if (preset && !selectedId.value && findNode(treeRows.value, preset)) {
        selectedId.value = preset;
        void doQuery();
      }
    } catch {
      // 业务错误提示由 http 拦截器弹出
    } finally {
      treeLoading.value = false;
    }
  }

  // ---------- 时间范围（升级: 日期+时间到分钟, picker 直接产出秒级值） ----------
  type QuickKey = 'today' | 'yesterday' | 'week';
  const todayStr = () => fmtDay(new Date());
  const shiftDay = (n: number) => {
    const d = new Date();
    d.setDate(d.getDate() + n);
    return fmtDay(d);
  };

  const range = ref<string[]>([`${todayStr()} 00:00:00`, `${todayStr()} 23:59:59`]);
  /** 高亮当前快捷段; 手工改时间后取消高亮 */
  const quick = ref<QuickKey | undefined>('today');

  function applyQuick(k: QuickKey) {
    if (k === 'today') range.value = [`${todayStr()} 00:00:00`, `${todayStr()} 23:59:59`];
    else if (k === 'yesterday') range.value = [`${shiftDay(-1)} 00:00:00`, `${shiftDay(-1)} 23:59:59`];
    else range.value = [`${shiftDay(-6)} 00:00:00`, `${todayStr()} 23:59:59`];
  }

  function onQuickChange(v: unknown) {
    if (v !== 'today' && v !== 'yesterday' && v !== 'week') return;
    quick.value = v;
    applyQuick(v);
    // 已选通道则立即查询（未选通道时按钮本就禁用）
    if (selectedId.value) void doQuery();
  }

  function onRangeChange(v: unknown) {
    const arr = v as string[] | undefined;
    if (arr && arr.length === 2 && arr[0] && arr[1]) {
      quick.value = undefined;
      range.value = arr;
      return;
    }
    // 清空 → 回到今天, 保持查询可用
    quick.value = 'today';
    range.value = [`${todayStr()} 00:00:00`, `${todayStr()} 23:59:59`];
  }

  /** 禁选未来日期 */
  const disableFuture = (current: Date) => current.getTime() > Date.now();

  // ---------- 查询 ----------
  type Phase = 'idle' | 'loading' | 'done' | 'error';
  const phase = ref<Phase>('idle');
  const result = ref<RecordQueryResult | null>(null);
  const errMsg = ref('');

  // 云端录像来源（B3）: 与设备录像检索同窗并行, 失败不阻塞设备录像结果
  const cloudList = ref<CloudRecord[]>([]);
  const cloudTotal = ref(0);

  /** 来源筛选（前端过滤时间轴, 不发新请求） */
  const sourceFilter = ref<'all' | 'device' | 'cloud'>('all');

  const route = useRoute();
  const canQuery = computed(() => !!selectedId.value);
  const queryTip = computed(() => (!selectedId.value ? '请先在左侧选择通道' : ''));

  async function doQuery() {
    if (!canQuery.value || phase.value === 'loading') return;
    const [start, end] = range.value;
    if (!start || !end) return;
    // 重新查询 → 片段集合整体失效, 打开中的回放先停流收起(仅一路回放)
    closePlayback();
    phase.value = 'loading';
    const params = {
      channelDbId: selectedId.value as number,
      start,
      end
    };
    // 并行发起: 设备录像(慢, 需设备应答) 与 云端录像(本地库) 各自收敛
    const devicePromise = queryRecord(params);
    const cloudPromise = cloudRecordApi.page({
      channelDbId: params.channelDbId,
      start: params.start,
      end: params.end,
      page: 1,
      count: 500
    });
    try {
      result.value = await devicePromise;
      phase.value = 'done';
    } catch (e) {
      errMsg.value = (e as Error).message || '录像查询失败';
      phase.value = 'error';
      cloudList.value = [];
      cloudTotal.value = 0;
      // 设备录像失败提前返回也必须消费云端 promise, 否则产生 unhandled rejection（其失败提示已由拦截器统一弹出）
      cloudPromise.catch(() => {});
      return;
    }
    try {
      const c = await cloudPromise;
      cloudList.value = c.list ?? [];
      cloudTotal.value = c.total ?? 0;
    } catch {
      // 云端检索失败不影响设备录像展示, 仅置空并提示
      cloudList.value = [];
      cloudTotal.value = 0;
      Message.warning('云端录像查询失败, 仅展示设备录像');
    }
  }

  /** 按当前窗重查云端录像（删除后刷新用） */
  async function reloadCloud() {
    if (!selectedId.value) return;
    try {
      const c = await cloudRecordApi.page({
        channelDbId: selectedId.value,
        start: range.value[0],
        end: range.value[1],
        page: 1,
        count: 500
      });
      cloudList.value = c.list ?? [];
      cloudTotal.value = c.total ?? 0;
    } catch {
      cloudList.value = [];
      cloudTotal.value = 0;
    }
  }

  // ---------- 回放区: 时间轴色块 / 明细「回放」→ 片段在主体播放器内嵌播放 ----------
  const auth = useAuthStore();
  /** 回放按钮/色块可点的前提（后端 playback 接口同权限点） */
  const canPlay = computed(() => auth.hasPerm('record:play'));
  /** 当前回放片段（null = 播放器显示占位引导） */
  const pbTarget = ref<{ startTime: string; endTime: string } | null>(null);

  function openPlayback(startTime: string, endTime: string) {
    if (!canPlay.value || !startTime || !endTime) return;
    // 同一片段重复点击不重播, 避免误双击重启流
    if (pbTarget.value?.startTime === startTime && pbTarget.value?.endTime === endTime) return;
    pbTarget.value = { startTime, endTime };
  }

  function closePlayback() {
    // 播放器的销毁与 playback/stop 由 PlaybackArea 卸载钩子负责
    pbTarget.value = null;
  }

  // 换通道 → 片段集合失效, 打开中的回放先停流收起; 云端列表同步失效
  watch(selectedId, () => {
    closePlayback();
    cloudList.value = [];
    cloudTotal.value = 0;
  });

  // ---------- 云端录像操作（下载/删除） ----------
  const downloadingId = ref<number | null>(null);

  async function downloadCloud(row: CloudRecord) {
    downloadingId.value = row.id;
    // 点击即反馈: 下载令牌接口要等后端落库/签名, 不提示会被当成没反应而连点
    Message.info('下载已开始，链接 60 秒内一次性有效');
    try {
      const t = await cloudRecordApi.download(row.id);
      // url 为免登录一次性下载地址（60s）, 直接新开窗口下载
      const win = window.open(t.url, '_blank');
      if (!win) {
        // 被弹窗拦截时地址已随令牌一次性作废, 只能放行弹窗后重取令牌
        Modal.warning({
          title: '下载窗口被拦截',
          content: '浏览器拦截了下载窗口，请允许弹窗后重试',
          okText: '知道了'
        });
      }
    } finally {
      downloadingId.value = null;
    }
  }

  function confirmRemoveCloud(row: CloudRecord) {
    Modal.confirm({
      title: '删除云端录像',
      content: `确认删除云端录像「${row.fileName || row.id}」? 平台侧落盘文件将一并删除, 不可恢复。`,
      okText: '删除',
      cancelText: '取消',
      okButtonProps: { status: 'danger' },
      onOk: async () => {
        await cloudRecordApi.remove(row.id);
        Message.success('云端录像已删除');
        await reloadCloud();
      }
    });
  }

  // ---------- 录像计划抽屉 ----------
  const planVisible = ref(false);

  // ---------- 摘要 ----------
  const totalDurationText = computed(() => {
    let ms = 0;
    for (const s of result.value?.segments ?? []) {
      const st = parseTime(s.startTime);
      const en = parseTime(s.endTime);
      if (Number.isFinite(st) && Number.isFinite(en) && en > st) ms += en - st;
    }
    return fmtDuration(Math.round(ms / 1000));
  });

  // ---------- 时间轴: 每天一行轨道, 片段按当天 24h 占比铺块（设备/云端叠加, 受来源筛选过滤） ----------
  interface TlSegment {
    /** 相对当天 0 点的横向位置/宽度（百分比） */
    left: number;
    width: number;
    /** tooltip: 00:00:00 – 01:00:00 · 60 分钟 */
    tip: string;
    /** 来源: 设备录像(绿)/云端录像(橙) */
    kind: 'device' | 'cloud';
    /** 本块对应的时间段（跨天片段已按天切开）, 设备录像点击回放用 */
    startTime: string;
    endTime: string;
  }
  interface DayRow {
    key: string;
    /** MM-DD 周X */
    label: string;
    segments: TlSegment[];
  }

  /** 设备录像片段 + 云端录像段 → 统一待铺块列表（跨天切片在 dayRows 内做） */
  const allSegments = computed<{ startTime: string; endTime: string; kind: 'device' | 'cloud' }[]>(
    () => {
      const device = (result.value?.segments ?? []).map((s) => ({
        startTime: s.startTime,
        endTime: s.endTime,
        kind: 'device' as const
      }));
      const cloud = cloudList.value.map((c) => ({
        startTime: c.startTime,
        endTime: c.endTime,
        kind: 'cloud' as const
      }));
      if (sourceFilter.value === 'device') return device;
      if (sourceFilter.value === 'cloud') return cloud;
      return [...device, ...cloud];
    }
  );

  const dayRows = computed<DayRow[]>(() => {
    const rows: DayRow[] = [];
    if (!range.value[0] || !range.value[1]) return rows;
    // 枚举范围内的每一天(起点取所选起始日的 0 点), 每天独立铺 24h
    const from = dayStartOf(parseTime(range.value[0]));
    const to = parseTime(range.value[1]);
    if (!Number.isFinite(from) || !Number.isFinite(to)) return rows;
    for (let t = from; t <= to; t += DAY_MS) {
      const d = new Date(t);
      rows.push({
        key: fmtDay(d),
        label: `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${WEEK[d.getDay()]}`,
        segments: []
      });
    }
    const indexOf = new Map(rows.map((r, i) => [r.key, i]));
    for (const seg of allSegments.value) {
      const en = parseTime(seg.endTime);
      let st = parseTime(seg.startTime);
      if (!Number.isFinite(st) || !Number.isFinite(en) || en <= st) continue;
      // 跨天片段按天切开, 各天轨道只画自己那一段
      while (st < en) {
        const ds = dayStartOf(st);
        const pieceEnd = Math.min(en, ds + DAY_MS);
        const i = indexOf.get(fmtDay(new Date(ds)));
        if (i !== undefined) {
          const row = rows[i];
          if (row) {
            row.segments.push({
              left: ((st - ds) / DAY_MS) * 100,
              width: ((pieceEnd - st) / DAY_MS) * 100,
              kind: seg.kind,
              tip:
                seg.kind === 'cloud'
                  ? `云端录像 · ${fmtClock(st)} – ${fmtClock(pieceEnd)} · ${fmtDuration(
                      Math.round((pieceEnd - st) / 1000)
                    )}`
                  : `${fmtClock(st)} – ${fmtClock(pieceEnd)} · ${fmtDuration(
                      Math.round((pieceEnd - st) / 1000)
                    )}`,
              startTime: `${fmtDay(new Date(st))} ${fmtClock(st)}`,
              endTime: `${fmtDay(new Date(pieceEnd))} ${fmtClock(pieceEnd)}`
            });
          }
        }
        st = pieceEnd;
      }
    }
    return rows;
  });

  /** 刻度 0/6/12/18/24 的定位: 首个左对齐, 末个右对齐, 其余居中 */
  function tickStyle(t: number): Record<string, string> {
    if (t === 0) return { left: '0' };
    const left = `${(t / 24) * 100}%`;
    return t === 24
      ? { left, transform: 'translateX(-100%)' }
      : { left, transform: 'translateX(-50%)' };
  }

  // ---------- 明细表（设备录像, 前端分页） ----------
  interface TableRow {
    /** arco TableData 的 key 字段为 string */
    key: string;
    startTime: string;
    endTime: string;
    duration: string;
    type: string;
    name: string;
  }

  const tableRows = computed<TableRow[]>(() =>
    (result.value?.segments ?? []).map((s, i) => {
      const st = parseTime(s.startTime);
      const en = parseTime(s.endTime);
      const sec =
        Number.isFinite(st) && Number.isFinite(en) && en > st ? Math.round((en - st) / 1000) : 0;
      return {
        key: String(i),
        startTime: s.startTime,
        endTime: s.endTime,
        duration: fmtDuration(sec),
        type: s.type,
        name: s.name
      };
    })
  );

  const pagination = computed(() => {
    const total = tableRows.value.length;
    // ≤10 条隐藏分页器（"共 N 段"信息由摘要行呈现）
    if (total <= 10) return false;
    return { total, pageSize: 10, showTotal: true };
  });

  // ---------- 云端录像明细行 ----------
  interface CloudTableRow {
    key: string;
    id: number;
    fileName: string;
    startTime: string;
    endTime: string;
    duration: string;
    size: string;
    state: string;
  }

  /** 秒 → "1.2 GB" / "356 MB" */
  function fmtSize(bytes: number): string {
    if (!bytes || bytes < 0) return '-';
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    let v = bytes;
    let u = 0;
    while (v >= 1024 && u < units.length - 1) {
      v /= 1024;
      u += 1;
    }
    return `${v.toFixed(v >= 100 || u === 0 ? 0 : 1)} ${units[u]}`;
  }

  const cloudRows = computed<CloudTableRow[]>(() =>
    cloudList.value.map((c) => ({
      key: String(c.id),
      id: c.id,
      fileName: c.fileName || '-',
      startTime: fmtDateTime(c.startTime),
      endTime: fmtDateTime(c.endTime),
      duration: fmtDuration(c.duration ?? 0),
      size: fmtSize(c.fileSize ?? 0),
      // V1 建库语义: 0=本地 1=已上传对象存储
      state: c.uploadState === 1 ? '已上传' : '本地存储'
    }))
  );

  const cloudPagination = computed(() => {
    const total = cloudRows.value.length;
    if (total <= 10) return false;
    return { total, pageSize: 10, showTotal: true };
  });

  /** 有任一来源的数据即进入结果态（设备录像为 0 但云端有录像时仍要出时间轴） */
  const hasResult = computed(
    () => (result.value?.count ?? 0) > 0 || cloudRows.value.length > 0
  );

  onMounted(() => void loadTree());

  // 本页被 keep-alive 缓存: 再次进入页签时静默刷新通道树（新增设备/通道后保持可选）
  let everActivated = false;
  onActivated(() => {
    if (!everActivated) {
      everActivated = true;
      return; // 首次进入由 onMounted 负责, 避免重复请求
    }
    void loadTree(true);
  });
</script>

<template>
  <div class="page-record-query">
    <!-- 左栏: 通道树（直播页同范式, 目录行灰标 + 通道行在线点, 双击查询） -->
    <aside class="rq-side">
      <div class="side-head">
        <a-input-search v-model="keyword" placeholder="搜索通道名称 / 编号" allow-clear />
      </div>
      <div v-if="treeLoading" class="side-empty">
        <a-spin :size="20" />
      </div>
      <div v-else-if="!visibleRows.length" class="side-empty">无匹配通道</div>
      <div v-else class="side-body">
        <template v-for="row in visibleRows" :key="row.node.channel.id">
          <div
            v-if="isDirNode(row.node)"
            class="tree-row dir"
            :style="{ paddingLeft: `${row.depth * 14 + 4}px` }"
            @click="onNodeClick(row.node)"
          >
            <icon-down class="dir-arrow" :class="{ open: expandedIds.has(row.node.channel.id) }" />
            <icon-folder class="dir-icon" />
            <span class="dir-name">{{ nodeTitle(row.node) }}</span>
            <span class="dir-tag">目录</span>
          </div>
          <div
            v-else
            class="tree-row ch"
            :class="{ active: selectedId === row.node.channel.id }"
            :style="{ paddingLeft: `${row.depth * 14 + 18}px` }"
            title="双击查询录像"
            @click="onNodeClick(row.node)"
            @dblclick="onNodeDblClick(row.node)"
          >
            <span class="ch-dot" :class="row.node.channel.status === 1 ? 'on' : 'off'" />
            <span class="ch-name">{{ nodeTitle(row.node) }}</span>
          </div>
        </template>
      </div>
    </aside>

    <!-- 右栏: 条件 → 回放播放器(主体) → 时间轴 → 折叠明细 -->
    <main class="rq-main">
      <div class="rq-toolbar">
        <a-range-picker
          :model-value="range"
          value-format="YYYY-MM-DD HH:mm:ss"
          show-time
          :time-picker-props="{ format: 'HH:mm' }"
          :disabled-date="disableFuture"
          style="width: 390px"
          @change="onRangeChange"
        />
        <a-radio-group :model-value="quick" type="button" size="small" @change="onQuickChange">
          <a-radio value="today">今天</a-radio>
          <a-radio value="yesterday">昨天</a-radio>
          <a-radio value="week">近7天</a-radio>
        </a-radio-group>
        <a-select v-model="sourceFilter" size="small" style="width: 108px">
          <a-option value="all">全部来源</a-option>
          <a-option value="device">设备录像</a-option>
          <a-option value="cloud">云端录像</a-option>
        </a-select>
        <a-tooltip :content="queryTip" :disabled="canQuery">
          <!-- 禁用按钮不吃指针事件, 包一层 span 让 tooltip 的 hover 落在外层 -->
          <span class="query-wrap">
            <a-button
              type="primary"
              :disabled="!canQuery"
              :loading="phase === 'loading'"
              @click="doQuery"
            >
              查询
            </a-button>
          </span>
        </a-tooltip>
        <span class="query-wrap">
          <a-button v-permission="'record-plan:manage'" @click="planVisible = true">
            <template #icon><icon-schedule /></template>
            录像计划
          </a-button>
        </span>
      </div>

      <!-- 查询失败: 后端 msg（设备离线 1001 / 查询超时 1010 / 范围非法 400） -->
      <a-alert v-if="phase === 'error'" type="error" class="err-alert">
        {{ errMsg }}
      </a-alert>

      <!-- 回放主体: 未回放时占位引导（播放器位置稳定不跳动） -->
      <div class="rq-stage">
        <PlaybackArea
          v-if="pbTarget"
          :channel-db-id="selectedId as number"
          :channel-name="channelLabel"
          :start-time="pbTarget.startTime"
          :end-time="pbTarget.endTime"
          @close="closePlayback"
        />
        <div v-else class="stage-empty">
          <template v-if="phase === 'loading'">
            <a-spin :size="24" />
            <span>正在查询录像目录…（设备应答最长约 15 秒，请稍候）</span>
          </template>
          <template v-else-if="selectedId">
            <icon-play-circle :size="30" />
            <span>点击下方时间轴片段或明细「回放」开始播放</span>
          </template>
          <template v-else>
            <icon-play-circle :size="30" />
            <span>在左侧双击通道检索录像</span>
          </template>
        </div>
      </div>

      <!-- 结果区: 摘要 + 时间轴 + 折叠明细 -->
      <template v-if="phase === 'done'">
        <div v-if="hasResult" class="rq-result">
          <div class="summary">
            <span>
              设备录像 {{ result?.count ?? 0 }} 段 · 总时长 {{ totalDurationText }}
              <template v-if="cloudTotal"> · 云端 {{ cloudTotal }} 条</template>
            </span>
            <span class="legend">
              <i class="legend-block" />设备录像
              <i class="legend-block cloud" />云端录像
            </span>
          </div>

          <div class="timeline">
            <!-- 刻度行: 与轨道对齐 -->
            <div class="tl-row tl-ruler">
              <div class="tl-date" />
              <div class="tl-track tl-ruler-track">
                <span v-for="t in [0, 6, 12, 18, 24]" :key="t" class="tl-tick" :style="tickStyle(t)">
                  {{ t }}
                </span>
              </div>
            </div>

            <div class="tl-body">
              <div v-for="d in dayRows" :key="d.key" class="tl-row">
                <div class="tl-date">{{ d.label }}</div>
                <div class="tl-track">
                  <a-tooltip
                    v-for="(s, i) in d.segments"
                    :key="i"
                    :content="s.kind === 'device' && canPlay ? `点击回放 · ${s.tip}` : s.tip"
                  >
                    <div
                      class="tl-seg"
                      :class="[{ cloud: s.kind === 'cloud' }, { clickable: s.kind === 'device' && canPlay }]"
                      :style="{ left: `${s.left}%`, width: `${s.width}%` }"
                      @click.stop="s.kind === 'device' && openPlayback(s.startTime, s.endTime)"
                    />
                  </a-tooltip>
                </div>
              </div>
            </div>
          </div>

          <!-- 折叠明细: 设备/云端两页签, 默认收起（时间轴已是主视图） -->
          <a-collapse :default-expanded-keys="[]" :bordered="false" class="detail-collapse">
            <a-collapse-item key="detail">
              <template #header>
                <span class="dc-head">
                  <icon-list />
                  明细列表
                  <span class="dc-sub">设备 {{ tableRows.length }} 段 · 云端 {{ cloudRows.length }} 条</span>
                </span>
              </template>
              <a-tabs default-active-key="device" size="small">
                <a-tab-pane key="device" title="设备录像">
                  <a-table
                    :data="tableRows"
                    :pagination="pagination"
                    row-key="key"
                    size="medium"
                    class="detail-table"
                  >
                    <template #columns>
                      <a-table-column title="开始时间" :width="170">
                        <template #cell="{ record }">{{ fmtDateTime(record.startTime) }}</template>
                      </a-table-column>
                      <a-table-column title="结束时间" :width="170">
                        <template #cell="{ record }">{{ record.endTime }}</template>
                      </a-table-column>
                      <a-table-column title="时长" :width="120">
                        <template #cell="{ record }">{{ record.duration }}</template>
                      </a-table-column>
                      <a-table-column title="类型" :width="110">
                        <template #cell="{ record }">
                          <a-tag v-if="record.type === 'time'" size="small" color="arcoblue">定时录像</a-tag>
                          <a-tag v-else-if="record.type === 'alarm'" size="small" color="orangered">
                            移动侦测
                          </a-tag>
                          <span v-else>{{ record.type || '—' }}</span>
                        </template>
                      </a-table-column>
                      <a-table-column title="文件名">
                        <template #cell="{ record }">
                          <span :title="record.name">{{ record.name || '—' }}</span>
                        </template>
                      </a-table-column>
                      <a-table-column v-if="canPlay" title="操作" :width="90">
                        <template #cell="{ record }">
                          <a-link @click="openPlayback(record.startTime, record.endTime)">回放</a-link>
                        </template>
                      </a-table-column>
                    </template>
                  </a-table>
                </a-tab-pane>
                <a-tab-pane key="cloud" title="云端录像">
                  <a-empty v-if="!cloudRows.length" description="该时间段无云端录像" />
                  <a-table
                    v-else
                    :data="cloudRows"
                    :pagination="cloudPagination"
                    row-key="key"
                    size="medium"
                    :scroll="{ x: 760 }"
                    class="cloud-table"
                  >
                    <template #columns>
                      <a-table-column title="文件名" :width="220">
                        <template #cell="{ record }">
                          <span :title="record.fileName">{{ record.fileName }}</span>
                        </template>
                      </a-table-column>
                      <a-table-column title="开始时间" :width="170">
                        <template #cell="{ record }">{{ record.startTime }}</template>
                      </a-table-column>
                      <a-table-column title="结束时间" :width="170">
                        <template #cell="{ record }">{{ record.endTime }}</template>
                      </a-table-column>
                      <a-table-column title="时长" :width="100">
                        <template #cell="{ record }">{{ record.duration }}</template>
                      </a-table-column>
                      <a-table-column title="大小" :width="90">
                        <template #cell="{ record }">{{ record.size }}</template>
                      </a-table-column>
                      <a-table-column title="状态" :width="90">
                        <template #cell="{ record }">
                          <a-tag size="small" :color="record.state === '已上传' ? 'green' : 'arcoblue'">
                            {{ record.state }}
                          </a-tag>
                        </template>
                      </a-table-column>
                      <a-table-column title="操作" :width="110" fixed="right">
                        <template #cell="{ record }">
                          <a-space :size="2">
                            <a-button
                              v-permission="'cloud-record:download'"
                              type="text"
                              size="mini"
                              :loading="downloadingId === record.id"
                              @click="downloadCloud(record)"
                            >
                              下载
                            </a-button>
                            <a-button
                              v-permission="'cloud-record:delete'"
                              type="text"
                              size="mini"
                              status="danger"
                              @click="confirmRemoveCloud(record)"
                            >
                              删除
                            </a-button>
                          </a-space>
                        </template>
                      </a-table-column>
                    </template>
                  </a-table>
                </a-tab-pane>
              </a-tabs>
            </a-collapse-item>
          </a-collapse>
        </div>

        <!-- 空结果 -->
        <div v-else class="state-block">
          <a-empty description="该时间段无录像" />
        </div>
      </template>
    </main>

    <RecordPlanDrawer v-model:visible="planVisible" />
  </div>
</template>

<style scoped lang="less">
  // 工作台布局（fill 页）: 左树固定宽自滚动, 右栏 flex 铺满自滚动, 整页不出滚动条
  .page-record-query {
    display: flex;
    gap: 12px;
    flex: 1;
    min-height: 480px;
  }

  .rq-side {
    display: flex;
    flex-direction: column;
    width: 272px;
    flex-shrink: 0;
    background: var(--color-bg-2);
    border-radius: 4px;
    overflow: hidden;

    .side-head {
      padding: 10px 12px;
      border-bottom: 1px solid var(--color-border);
    }

    .side-empty {
      padding: 40px 0;
      font-size: 13px;
      color: var(--color-text-3);
      text-align: center;
    }

    .side-body {
      flex: 1;
      min-height: 0;
      overflow: auto;
      padding: 4px 6px 12px;

      &::-webkit-scrollbar {
        width: 6px;
      }

      &::-webkit-scrollbar-thumb {
        border-radius: 3px;
        background: var(--color-fill-4);
      }
    }
  }

  .tree-row {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 4px 6px;
    margin-top: 1px;
    font-size: 13px;
    border-radius: 4px;
    cursor: pointer;
    user-select: none;

    &:hover {
      background: var(--color-fill-2);
    }

    &.dir {
      color: var(--color-text-1);

      .dir-arrow {
        flex-shrink: 0;
        font-size: 10px;
        color: var(--color-text-4);
        transition: transform 0.15s ease;

        &.open {
          transform: rotate(-90deg);
        }
      }

      .dir-icon {
        flex-shrink: 0;
        color: var(--color-text-3);
      }

      .dir-name {
        flex: 1;
        min-width: 0;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      .dir-tag {
        flex-shrink: 0;
        font-size: 11px;
        color: var(--color-text-2);
        background: var(--color-fill-3);
        padding: 0 5px;
        border-radius: 2px;
      }
    }

    &.ch {
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
      }

      .ch-name {
        flex: 1;
        min-width: 0;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      &.active {
        background: rgb(var(--arcoblue-1));
        color: rgb(var(--arcoblue-6));
      }
    }
  }

  .rq-main {
    display: flex;
    flex-direction: column;
    flex: 1;
    min-width: 0;
    min-height: 0;
    overflow: auto;
    padding: 15px; // 四周留白(用户口径), 左树与右内容区均受益

    &::-webkit-scrollbar {
      width: 6px;
    }

    &::-webkit-scrollbar-thumb {
      border-radius: 3px;
      background: var(--color-fill-4);
    }
  }

  .rq-toolbar {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 10px;
    margin-bottom: 10px;

    .query-wrap {
      display: inline-flex;
      align-items: center;
    }
  }

  .err-alert {
    margin-bottom: 10px;
  }

  // 回放主体: 收窄居中(页面高度让给下方时间轴/明细), 播放器+工具栏由 PlaybackArea 组件自带样式呈现
  .rq-stage {
    position: relative;
    width: 100%;
    max-width: 720px;
    margin: 0 auto;
    flex-shrink: 0;

    .stage-empty {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: 10px;
      height: 340px;
      font-size: 13px;
      color: rgba(255, 255, 255, 0.75);
      background: #000;
      border-radius: 4px;
    }
  }

  .rq-result {
    margin-top: 12px;
  }

  .state-block {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 10px;
    padding: 40px 0;
  }

  .summary {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 8px;
    font-size: 12px;
    color: var(--color-text-3);

    .legend {
      display: inline-flex;
      align-items: center;
      gap: 6px;
    }

    .legend-block {
      width: 14px;
      height: 8px;
      border-radius: 2px;
      background: var(--color-success-6);

      &.cloud {
        margin-left: 10px;
        background: rgb(var(--orangered-6));
      }
    }
  }

  .timeline {
    margin-bottom: 12px;

    .tl-row {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    // 天数 > 1 时行距 8px
    .tl-body .tl-row + .tl-row {
      margin-top: 8px;
    }

    .tl-date {
      width: 84px;
      flex-shrink: 0;
      font-size: 12px;
      text-align: right;
      color: var(--color-text-1);
    }

    .tl-track {
      position: relative;
      flex: 1;
      height: 26px;
      border-radius: 4px;
      background-color: var(--color-fill-2);
      // 24 根小时刻线
      background-image: repeating-linear-gradient(
        to right,
        rgb(var(--gray-3)) 0,
        rgb(var(--gray-3)) 1px,
        transparent 1px,
        transparent ~'calc(100% / 24)'
      );
    }

    .tl-ruler {
      margin-bottom: 4px;

      .tl-ruler-track {
        height: 14px;
        background-image: none;
      }

      .tl-tick {
        position: absolute;
        top: 0;
        font-size: 11px;
        line-height: 14px;
        color: var(--color-text-4);
      }
    }

    .tl-body {
      max-height: 192px;
      padding-right: 4px;
      overflow: auto;

      &::-webkit-scrollbar {
        width: 6px;
        height: 6px;
      }

      &::-webkit-scrollbar-thumb {
        border-radius: 3px;
        background: var(--color-fill-4);
      }

      &::-webkit-scrollbar-track {
        background: transparent;
      }
    }

    .tl-seg {
      position: absolute;
      top: 3px;
      bottom: 3px;
      min-width: 2px;
      border-radius: 2px;
      background: var(--color-success-6);
      cursor: default;
      transition: filter 0.15s ease;

      &.cloud {
        background: rgb(var(--orangered-6));
      }

      &:hover {
        filter: brightness(1.1);
      }

      // 有回放权限时色块即回放入口
      &.clickable {
        cursor: pointer;
      }
    }
  }

  .detail-collapse {
    background: var(--color-bg-2);

    .dc-head {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      font-size: 13px;
      font-weight: 500;

      .dc-sub {
        font-size: 12px;
        font-weight: 400;
        color: var(--color-text-3);
      }
    }
  }

  .detail-table,
  .cloud-table {
    margin-top: 4px;
  }
</style>
