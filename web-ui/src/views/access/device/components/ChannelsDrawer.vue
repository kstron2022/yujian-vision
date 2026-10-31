<script setup lang="ts">
  import { computed, reactive, ref, watch } from 'vue';
  import { Message } from '@arco-design/web-vue';
  import { useRouter } from 'vue-router';
  import { deviceApi, SNAPSHOT_SOURCE_TEXT } from '@/api/device';
  import type { ChannelSnapshot, GbChannel, GbDevice } from '@/api/device';
  import { useAuthStore } from '@/stores/auth';
  import { fmtDateTime } from '@/utils/format';
  import SnapshotPreview from './SnapshotPreview.vue';
  import LivePreviewModal from './LivePreviewModal.vue';

  const props = defineProps<{ visible: boolean; device: GbDevice | null }>();
  const emit = defineEmits<{ (e: 'update:visible', v: boolean): void }>();

  const auth = useAuthStore();
  const rows = ref<GbChannel[]>([]);
  const total = ref(0);
  const loading = ref(false);
  const query = reactive({ query: '', page: 1, count: 10 });
  const renameTarget = ref<GbChannel | null>(null);
  const renameName = ref('');
  /** 云台开关（B3 通道级强制开关）: 自动用 -1 哨兵(Arco select 不吃 null), 提交时还原为 null */
  const PTZ_AUTO = -1;
  const renamePtz = ref(PTZ_AUTO);
  /** 云台类型（M5-B2 人工补录）: LiveGBS 类平台目录不报 PTZType, 球机在此补类型出摇杆 */
  const renamePtzType = ref(0);
  const renameSaving = ref(false);

  /** 抓图总开关（B7 阶段3, 抽屉打开时查一次）: enabled=false 则截图列整列不渲染。
   *  注意这是功能开关不是权限——抓图权限由后端 device:play 判（见 SnapshotPreview 的按钮门控） */
  const snapshotEnabled = ref(false);
  /** 缩略图加载失败的行（签名过期/文件被清理任务删）: 退化成占位图, 不显示破图 */
  const brokenThumbs = ref<number[]>([]);
  /** 截图预览/播放弹窗的目标行（null = 关闭）: visible 由目标是否为空派生, 免维护两套状态 */
  const snapshotTarget = ref<GbChannel | null>(null);
  const playTarget = ref<GbChannel | null>(null);
  /** 通道/状态/云台类型/地址/操作 五列宽度下限之和（通道列是 min-width, 其余定长; 截图列 120 只在渲染时计入） */
  const FIXED_COL_WIDTH = 230 + 80 + 100 + 140 + 120;
  /** scroll.x 必须等于实际列宽之和: Arco 表格容器比 scroll.x 宽时会把富余宽按比例摊进所有列,
   *  开关截图列前后若仍写死同一个值, 各列宽度会整体漂移 */
  const tableWidth = computed(() => FIXED_COL_WIDTH + (snapshotEnabled.value ? 120 : 0));

  const snapshotVisible = computed({
    get: () => !!snapshotTarget.value,
    set: (v: boolean) => {
      if (!v) snapshotTarget.value = null;
    }
  });
  const playVisible = computed({
    get: () => !!playTarget.value,
    set: (v: boolean) => {
      if (!v) playTarget.value = null;
    }
  });

  const ptzEnabledOptions = [
    { label: '自动（按云台类型）', value: PTZ_AUTO },
    { label: '强制开启', value: 1 },
    { label: '禁用', value: 0 }
  ];

  const pagination = computed(() => ({
    total: total.value,
    current: query.page,
    pageSize: query.count,
    showTotal: true,
    simple: true
  }));

  const ptzTypeText: Record<number, string> = {
    0: '未知',
    1: '球机',
    2: '半球',
    3: '固定枪机',
    4: '遥控枪机',
    5: '不支持'
  };

  /** 云台类型选项（GB/T28181 目录 PTZType 字段口径; M5-B2 人工补录入口） */
  const ptzTypeOptions = [
    { label: '未知 / 无云台', value: 0 },
    { label: '球机', value: 1 },
    { label: '半球', value: 2 },
    { label: '固定枪机', value: 3 },
    { label: '遥控枪机', value: 4 },
    { label: '不支持', value: 5 }
  ];

  /** ONVIF 通道主码流档分辨率（onvifProfiles JSON 首档; 档位按像素面积降序缓存）; GB 通道无 */
  function mainResolution(record: GbChannel): string {
    if (!record.onvifProfiles) return '';
    try {
      const profiles = JSON.parse(record.onvifProfiles) as Array<{
        width?: number;
        height?: number;
      }>;
      const main = profiles?.[0];
      if (main?.width && main?.height) return `${main.width}×${main.height}`;
      return '';
    } catch {
      return '';
    }
  }

  async function load() {
    if (!props.device) return;
    loading.value = true;
    try {
      const data = await deviceApi.channels({
        deviceId: props.device.id,
        query: query.query || undefined,
        page: query.page,
        count: query.count
      });
      rows.value = data.list;
      total.value = data.total;
      // 每次拉取都带新签名, 旧的"加载失败"标记对这批 URL 已无意义（否则翻页/搜索后
      // 同一通道会一直显示占位图, 直到整个抽屉关掉重开）
      brokenThumbs.value = [];
    } finally {
      loading.value = false;
    }
  }

  function search() {
    query.page = 1;
    void load();
  }

  function onPage(page: number) {
    query.page = page;
    void load();
  }

  function openRename(row: GbChannel) {
    renameTarget.value = row;
    renameName.value = row.customName || '';
    // ptzEnabled 与名称一起全量提交（后端为显式 set, 只传名称会把开关重置为自动）
    renamePtz.value = row.ptzEnabled ?? PTZ_AUTO;
    // ptzType 同样全量提交（后端 null=不改; 回显现值避免把人工补录的类型冲成默认）
    renamePtzType.value = row.ptzType ?? 0;
  }

  async function doRename() {
    if (!renameTarget.value) return;
    renameSaving.value = true;
    try {
      await deviceApi.updateChannel(renameTarget.value.id, {
        customName: renameName.value.trim(),
        ptzEnabled: renamePtz.value === PTZ_AUTO ? null : renamePtz.value,
        ptzType: renamePtzType.value
      });
      Message.success('通道已更新');
      renameTarget.value = null;
      await load();
    } finally {
      renameSaving.value = false;
    }
  }

  // ---------- B7 截图列 / 播放 ----------
  /** 抓图开关（登录即可查）: 查询失败按"不支持抓图"处理, 不阻断通道列表本身 */
  async function loadSnapshotConfig() {
    try {
      const cfg = await deviceApi.snapshotConfig();
      snapshotEnabled.value = cfg.enabled;
    } catch {
      snapshotEnabled.value = false;
    }
  }

  /** 缩略图悬浮说明: 有图给 时间 + 来源, 无图/加载失败给操作引导 */
  function snapshotTip(row: GbChannel) {
    if (!row.snapshot || brokenThumbs.value.includes(row.id)) return '暂无截图，点击可抓图';
    const src = SNAPSHOT_SOURCE_TEXT[row.snapshot.source] ?? row.snapshot.source;
    return `${fmtDateTime(row.snapshot.time)} · ${src}`;
  }

  function markThumbBroken(id: number) {
    if (!brokenThumbs.value.includes(id)) brokenThumbs.value = [...brokenThumbs.value, id];
  }

  function openSnapshot(row: GbChannel) {
    snapshotTarget.value = row;
  }

  function openPlay(row: GbChannel) {
    playTarget.value = row;
  }

  /** 「录像」直达: 跳转录像检索页并预选该通道（检索页按 query.channelDbId 自动查询） */
  const router = useRouter();
  function goRecord(row: GbChannel) {
    void router.push({ path: '/record/query', query: { channelDbId: String(row.id) } });
  }

  /**
   * 抓图成功后只改本行数据（不整页重刷, 免得打断用户浏览位置）, 并清掉旧的加载失败标记。
   * 目标行按 id 找、不用 snapshotTarget: 抓图最坏 20s+, 期间用户可能已关窗或点到别的行,
   * 靠弹窗的当前目标会把结果丢掉（甚至挂错行）。
   */
  function onSnapshotUpdated(payload: { id: number; snapshot: ChannelSnapshot }) {
    const row = rows.value.find((r) => r.id === payload.id);
    if (row) row.snapshot = payload.snapshot;
    brokenThumbs.value = brokenThumbs.value.filter((x) => x !== payload.id);
  }

  watch(
    () => props.visible,
    (v) => {
      if (v) {
        query.page = 1;
        query.query = '';
        brokenThumbs.value = [];
        void loadSnapshotConfig();
        void load();
      }
    }
  );

  defineExpose({ reload: load });
</script>

<template>
  <a-drawer
    :visible="visible"
    width="1050px"
    unmount-on-close
    @cancel="emit('update:visible', false)"
    @close="emit('update:visible', false)"
  >
    <template #title>
      <span class="dlg-title">
        <icon-storage />
        通道列表 · {{ device?.name || device?.deviceId || '' }}
      </span>
    </template>

    <div class="drawer-toolbar">
      <a-input-search
        v-model="query.query"
        placeholder="通道名称 / 国标编号"
        allow-clear
        style="width: 240px"
        @search="search"
        @clear="search"
      />
    </div>

    <a-table
      :data="rows"
      :loading="loading"
      :pagination="pagination"
      :scroll="{ x: tableWidth }"
      row-key="id"
      size="small"
      @page-change="onPage"
    >
      <template #columns>
        <!-- 通道名是用户可编辑的(customName), 长度不可预期 → 按列宽惯例用 min-width+ellipsis tooltip,
             不用定长 :width（定长会在名字过长时被硬截断且无提示, 见 docs/09 B5 列宽惯例） -->
        <a-table-column title="通道" :min-width="230" ellipsis tooltip>
          <template #cell="{ record }">
            <div class="ch-name">
              <!-- 目录节点(下级平台行政区划/平台根): 标注类型, 不可播放/抓图(后端 doPlay 同口径拦截) -->
              <a-tag v-if="record.directory" size="small" color="gray" class="ch-dir-tag">
                <icon-folder />
                目录
              </a-tag>
              {{ record.customName || record.name || '-' }}
            </div>
            <div class="ch-sub">{{ record.channelId }}</div>
          </template>
        </a-table-column>
        <a-table-column title="状态" :width="80" align="center">
          <template #cell="{ record }">
            <!-- 目录节点的 ON/OFF 是对端目录上报值, 无业务意义(如"平台"离线/"黄石港区"在线) → 不显示 -->
            <a-tag v-if="record.directory" size="small" color="gray">—</a-tag>
            <a-tag v-else size="small" :color="record.status === 1 ? 'green' : 'gray'">
              {{ record.status === 1 ? '在线' : '离线' }}
            </a-tag>
          </template>
        </a-table-column>
        <a-table-column title="云台类型" :width="100" align="center">
          <template #cell="{ record }">
            <span v-if="record.ptzType && record.ptzType > 0" class="ch-ptz">
              {{ ptzTypeText[record.ptzType] ?? `类型${record.ptzType}` }}
            </span>
            <span v-else class="ch-sub">无</span>
          </template>
        </a-table-column>
        <!-- M4-B2: ONVIF 通道显示主码流档分辨率; GB 通道无档位概念显示 - -->
        <a-table-column title="分辨率" :width="100" align="center">
          <template #cell="{ record }">
            <span v-if="mainResolution(record)" class="ch-sub">{{ mainResolution(record) }}</span>
            <span v-else class="ch-sub">-</span>
          </template>
        </a-table-column>
        <a-table-column title="地址" :width="140">
          <template #cell="{ record }">
            <!-- ONVIF 通道 port=设备 HTTP 口, 与 GB 通道 SIP/媒体口语义混用 → 只显示 IP -->
            <span v-if="record.onvifProfiles && record.ip">{{ record.ip }}</span>
            <span v-else-if="record.ip">{{ record.ip }}:{{ record.port }}</span>
            <span v-else class="ch-sub">{{ record.address || '-' }}</span>
          </template>
        </a-table-column>
        <a-table-column v-if="snapshotEnabled" title="截图" :width="120" align="center">
          <template #cell="{ record }">
            <!-- 目录节点无截图语义(抓图走点播链路, 必败) → 纯文本占位, 不给点击入口 -->
            <span v-if="record.directory" class="ch-sub">—</span>
            <a-tooltip v-else :content="snapshotTip(record)" position="top">
              <!-- 键盘可达: 缩略图是可点击容器而非按钮, 不补 role/tabindex 时 Tab/Enter 都到不了 -->
              <div
                class="ch-snap"
                role="button"
                tabindex="0"
                @click="openSnapshot(record)"
                @keydown.enter="openSnapshot(record)"
                @keydown.space.prevent="openSnapshot(record)"
              >
                <img
                  v-if="record.snapshot && !brokenThumbs.includes(record.id)"
                  class="ch-snap-img"
                  :src="record.snapshot.thumbUrl"
                  alt="通道截图"
                  @error="markThumbBroken(record.id)"
                />
                <div v-else class="ch-snap-empty">
                  <icon-image :size="15" />
                  <span>暂无截图</span>
                </div>
              </div>
            </a-tooltip>
          </template>
        </a-table-column>
        <a-table-column title="操作" :width="120" fixed="right">
          <template #cell="{ record }">
            <a-button
              v-if="auth.hasPerm('device:play') && !record.directory"
              type="text"
              size="mini"
              @click="openPlay(record)"
            >
              播放
            </a-button>
            <a-button
              v-if="auth.hasPerm('record:query') && !record.directory"
              type="text"
              size="mini"
              title="跳转录像检索页查询该通道录像"
              @click="goRecord(record)"
            >
              录像
            </a-button>
            <a-button
              v-if="auth.hasPerm('channel:update')"
              type="text"
              size="mini"
              @click="openRename(record)"
            >
              改名
            </a-button>
          </template>
        </a-table-column>
      </template>
    </a-table>

    <a-modal
      :visible="!!renameTarget"
      :ok-loading="renameSaving"
      :mask-closable="false"
      ok-text="保存"
      cancel-text="取消"
      @ok="doRename"
      @cancel="renameTarget = null"
      @close="renameTarget = null"
    >
      <template #title>
        <span class="dlg-title">
          <icon-edit />
          修改通道
        </span>
      </template>
      <a-form :model="{ customName: renameName, ptzEnabled: renamePtz, ptzType: renamePtzType }" layout="vertical">
        <a-form-item label="自定义名称" help="留空则恢复显示设备上报名称">
          <a-input
            v-model="renameName"
            :placeholder="renameTarget?.name || ''"
            allow-clear
            :max-length="64"
            @keydown.enter="doRename"
          />
        </a-form-item>
        <a-form-item label="云台类型" help="LiveGBS 等平台目录不报云台类型, 球机在此补选「球机」即可出现云台控制">
          <a-select v-model="renamePtzType" :options="ptzTypeOptions" style="width: 220px" />
        </a-form-item>
        <a-form-item label="云台控制" help="禁用后该通道不出现云台操作入口（自动=按云台类型判断; 强制开启=不依赖类型）">
          <a-select v-model="renamePtz" :options="ptzEnabledOptions" style="width: 220px" />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- B7: 截图预览（大图 + 重新抓图）与实时播放弹窗, 目标行清空即关闭 -->
    <SnapshotPreview
      v-model:visible="snapshotVisible"
      :channel="snapshotTarget"
      @updated="onSnapshotUpdated"
    />
    <LivePreviewModal v-model:visible="playVisible" :channel="playTarget" />

    <template #footer>
      <div class="drawer-footer">
        <span class="drawer-total">共 {{ total }} 个通道</span>
        <a-button @click="emit('update:visible', false)">关 闭</a-button>
      </div>
    </template>
  </a-drawer>
</template>

<style scoped lang="less">
  .drawer-toolbar {
    display: flex;
    align-items: center;
    justify-content: flex-end;
    margin-bottom: 12px;
  }

  .drawer-footer {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  .drawer-total {
    font-size: 13px;
    color: var(--color-text-3);
  }

  .ch-name {
    color: var(--color-text-1);
  }

  // 目录节点 tag: 灰底小号, 与通道名同行
  .ch-dir-tag {
    margin-right: 6px;

    :deep(.arco-icon) {
      vertical-align: -2px;
    }
  }

  .ch-sub {
    font-size: 12px;
    color: var(--color-text-3);
    line-height: 18px;
  }

  .ch-ptz {
    font-size: 12px;
    color: rgb(var(--arcoblue-6));
  }

  // B7 截图列: 缩略图按 16:9 展示（缩略图实宽 160px, 这里显示 80×45）
  .ch-snap {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 80px;
    height: 45px;
    overflow: hidden;
    cursor: pointer;
    background: var(--color-fill-2);
    border: 1px solid var(--color-border-2);
    border-radius: 3px;
    transition: border-color 0.15s;

    &:hover {
      border-color: rgb(var(--arcoblue-6));
    }

    .ch-snap-img {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }

    .ch-snap-empty {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 2px;
      font-size: 11px;
      line-height: 13px;
      color: var(--color-text-3);
    }
  }
</style>
