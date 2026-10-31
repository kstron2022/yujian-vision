<script setup lang="ts">
import { computed, onActivated, onMounted, onUnmounted, reactive, ref } from 'vue'
import { Message, Modal } from '@arco-design/web-vue'
import { deviceApi } from '@/api/device'
import type { GbDevice } from '@/api/device'
import { useAuthStore } from '@/stores/auth'
import { WsEvent, wsClient } from '@/utils/ws'
import ChannelsDrawer from './components/ChannelsDrawer.vue'
import CreateDrawer from './components/CreateDrawer.vue'
import EditDrawer from './components/EditDrawer.vue'
import OnvifEditDrawer from './components/OnvifEditDrawer.vue'
import OnvifCreateDrawer from './components/OnvifCreateDrawer.vue'
import RtspCreateDrawer from './components/RtspCreateDrawer.vue'
import RtspEditDrawer from './components/RtspEditDrawer.vue'

/** 行扩展字段: 目录同步进行中的进度（WS CATALOG_SYNC_PROGRESS 驱动） */
interface DeviceRow extends GbDevice {
  syncTotal?: number
  syncSynced?: number
}

const auth = useAuthStore()
const rows = ref<DeviceRow[]>([])
const total = ref(0)
const loading = ref(false)
const query = reactive({
  keyword: '',
  online: -1,
  /** 协议筛选（M5-B1）: '' = 全部（不传参） */
  accessProtocol: '',
  page: 1,
  count: 10
})

/**
 * 各列宽之和 = 表格的硬下限（Arco 把 scroll.x 写进 <table style="width">, 但列宽/min-width 之和
 * 才是浏览器实际采用的最小宽）。定长列 :width、弹性列只给 :min-width, 两者都要计进来。
 *
 * 操作列 260 是内容下限而不是审美取值: 列内 4 个 text 按钮实测需 254px（内容 222 + 左右 padding 32）,
 * 低于它内容就溢出单元格 → 把整张表恒撑宽 8px → **任何窗口宽度下悬停都会出现横向滚动条**
 * （2026-09-17 实测: 1440/1500/1600/1920 全部恒定溢出 8px, 根因即此）。
 * 协议列 90 同为内容下限: 「ONVIF」tag 实测约 55px(内容), medium 单元格 padding 16x2 后
 * 80px 内容区仅 48px 会溢出——一旦库里有 ONVIF 设备行 smoke T9 守护断言即红(评审 S1)。
 * 类型列 100 同为内容下限: 「下级平台」tag 实测需 66px(内容), min-width 90 时内容区只有 63 仍溢出
 * （2026-09-17 e2e 守护断言在 242 出现下级平台设备后抓到, 见 smoke T9）。
 */
const COL_SUM = 200 + 100 + 90 + 80 + 120 + 60 + 110 + 210 + 170 + 260

const drawerVisible = ref(false)
const drawerDevice = ref<GbDevice | null>(null)
const drawerRef = ref<InstanceType<typeof ChannelsDrawer>>()
const editVisible = ref(false)
const onvifEditVisible = ref(false)
const editDevice = ref<GbDevice | null>(null)
const createVisible = ref(false)
const onvifCreateVisible = ref(false)
const rtspCreateVisible = ref(false)
const rtspEditVisible = ref(false)

let unbindWs: (() => void) | undefined
let reloadTimer: number | undefined

const pagination = computed(() => ({
  total: total.value,
  current: query.page,
  pageSize: query.count,
  showTotal: true,
  showPageSize: true,
  pageSizeOptions: [10, 20, 50]
}))

const percent = (row: DeviceRow) => (row.syncTotal ? Math.min(100, Math.floor(((row.syncSynced ?? 0) / row.syncTotal) * 100)) : 0)

const fmtTime = (v?: string | null) => (v ? v.replace('T', ' ').slice(0, 19) : '-')

async function load(silent = false) {
  if (!silent) loading.value = true
  try {
    const data = await deviceApi.page({
      keyword: query.keyword || undefined,
      online: query.online === -1 ? undefined : query.online,
      accessProtocol: query.accessProtocol || undefined,
      page: query.page,
      count: query.count
    })
    rows.value = data.list
    total.value = data.total
    // 行级补查同步进度(仅当前页): 刷新页面时恢复"同步中"展示
    await Promise.allSettled(
      data.list.map(async d => {
        // ONVIF 目录同步为同步调用(无异步进度会话), 跳过轮询防白花一次请求(评审 Q4)
        if (d.accessProtocol === 'ONVIF') return
        const p = await deviceApi.catalogProgress(d.deviceId)
        if (!p.running) return
        const row = rows.value.find(r => r.id === d.id)
        if (row) {
          row.syncTotal = p.total
          row.syncSynced = p.synced
        }
      })
    )
  } finally {
    loading.value = false
  }
}

/** WS 高频事件触发的列表刷新做合并, 避免同步期间连环请求 */
function reloadSoon() {
  if (reloadTimer) return
  reloadTimer = window.setTimeout(() => {
    reloadTimer = undefined
    void load(true)
  }, 400)
}

function search() {
  query.page = 1
  void load()
}

function reset() {
  query.keyword = ''
  query.online = -1
  search()
}

function onPage(page: number) {
  query.page = page
  void load()
}

function onPageSize(size: number) {
  query.count = size
  query.page = 1
  void load()
}

function openChannels(row: GbDevice) {
  drawerDevice.value = row
  drawerVisible.value = true
}

/** 编辑按接入协议分流: GB/ONVIF/RTSP 表单字段不同, 各自独立抽屉（docs/13 §6.2, docs/14 §5） */
function openEdit(row: GbDevice) {
  editDevice.value = row
  if (row.accessProtocol === 'ONVIF') {
    onvifEditVisible.value = true
  } else if (row.accessProtocol === 'RTSP') {
    rtspEditVisible.value = true
  } else {
    editVisible.value = true
  }
}

async function refreshCatalog(row: DeviceRow) {
  // 进行中(2001)/设备离线(1001)等业务提示由 http 拦截器弹出, 这里吞掉 rejection 避免 unhandled
  try {
    await deviceApi.refreshCatalog(row.deviceId)
  } catch {
    return
  }
  if (row.accessProtocol === 'ONVIF') {
    // ONVIF 目录同步为同步调用(请求返回即全量比对完成), 无 WS 进度事件——置同步中假态
    // 没人收敛会永卡 0/0+按钮禁用(2026-09-25 现场实测), 直接刷列表呈现最新通道
    Message.success(`目录已刷新: ${row.name || row.deviceId}`)
    void load(true)
    return
  }
  row.syncTotal = 0
  row.syncSynced = 0
  Message.success(`已触发目录同步: ${row.name || row.deviceId}`)
}

function confirmDelete(row: GbDevice) {
  Modal.confirm({
    title: '删除设备',
    content: `确认删除设备「${row.name || row.deviceId}」？将同时清除其通道与播放会话。`,
    okText: '删除',
    cancelText: '取消',
    okButtonProps: { status: 'danger' },
    hideCancel: false,
    onOk: async () => {
      try {
        await deviceApi.remove(row.deviceId)
      } catch {
        return // 失败提示已由拦截器弹出, 保持弹窗打开
      }
      Message.success('删除成功')
      void load(true)
    }
  })
}

function onWs(type: string, data: Record<string, unknown>) {
  const deviceId = data.deviceId as string | undefined
  if (!deviceId) return
  if (type === WsEvent.DeviceOnline || type === WsEvent.DeviceOffline) {
    const row = rows.value.find(r => r.deviceId === deviceId)
    if (!row) return
    const online = type === WsEvent.DeviceOnline ? 1 : 0
    if (row.online !== online) {
      row.online = online
      if (online === 1) Message.success(`设备上线: ${row.name || deviceId}`)
      else Message.warning(`设备离线: ${row.name || deviceId}`)
    }
  } else if (type === WsEvent.CatalogSyncProgress) {
    const row = rows.value.find(r => r.deviceId === deviceId)
    if (!row) return
    row.syncTotal = Number(data.total ?? 0)
    row.syncSynced = Number(data.synced ?? 0)
    if (row.syncTotal > 0 && (row.syncSynced ?? 0) >= row.syncTotal) {
      // 收敛完成: 清行内进度并刷新(延时让最后一批通道入库落定)
      window.setTimeout(() => {
        delete row.syncTotal
        delete row.syncSynced
        reloadSoon()
      }, 500)
    }
  } else if (type === WsEvent.ChannelChange) {
    if (drawerVisible.value && drawerDevice.value?.deviceId === deviceId) {
      drawerRef.value?.reload()
    }
  }
}

onMounted(() => {
  void load()
  unbindWs = wsClient.on(onWs)
})
onActivated(() => void load(true))
onUnmounted(() => unbindWs?.())
</script>

<template>
  <div class="page-devices">
    <a-card :bordered="false" class="general-card">
      <div class="toolbar">
        <a-space wrap>
          <a-input-search v-model="query.keyword" placeholder="设备名称 / 国标编号" allow-clear style="width: 260px" @search="search" @clear="search" />
          <a-radio-group v-model="query.online" type="button" size="small" @change="search">
            <a-radio :value="-1">全部</a-radio>
            <a-radio :value="1">在线</a-radio>
            <a-radio :value="0">离线</a-radio>
          </a-radio-group>
          <a-select v-model="query.accessProtocol" size="small" style="width: 130px" allow-clear
            placeholder="全部协议" @change="search">
            <a-option value="">全部</a-option>
            <a-option value="GB">国标</a-option>
            <a-option value="ONVIF">ONVIF</a-option>
            <a-option value="RTSP">拉流代理</a-option>
          </a-select>
          <a-button size="small" @click="reset">重置</a-button>
        </a-space>
        <a-space>
          <a-button v-if="auth.hasPerm('device:add')" type="primary" size="small" @click="createVisible = true">
            <template #icon><icon-plus /></template>
            添加国标设备
          </a-button>
          <a-button v-if="auth.hasPerm('device:add')" type="outline" size="small" @click="onvifCreateVisible = true">
            <template #icon><icon-plus /></template>
            添加ONVIF设备
          </a-button>
          <a-button v-if="auth.hasPerm('device:add')" type="outline" size="small" @click="rtspCreateVisible = true">
            <template #icon><icon-plus /></template>
            添加拉流代理
          </a-button>
          <a-button type="text" size="small" :loading="loading" @click="load()">
            <template #icon><icon-refresh /></template>
            刷新
          </a-button>
        </a-space>
      </div>

      <a-table :data="rows" :loading="loading" :pagination="pagination" :scroll="{ x: COL_SUM }" row-key="id" size="medium" @page-change="onPage" @page-size-change="onPageSize">
        <template #columns>
          <!-- 列宽惯例(docs/09 B5): 注册时间/设备地址定长 :width; 其余自适应 min-width+ellipsis tooltip。
               改任一列宽都要同步改 COL_SUM, 否则 scroll.x 与真实下限脱钩（见 COL_SUM 注释） -->
          <a-table-column title="设备" :width="200">
            <template #cell="{ record }">
              <a-tooltip position="tl">
                <template #content>
                  <div>{{ record.name || '-' }}</div>
                  <div>{{ record.deviceId }}</div>
                </template>
                <div class="dev-clip">
                  <div class="dev-name">{{ record.name || '-' }}</div>
                  <div class="dev-sub">{{ record.deviceId }}</div>
                </div>
              </a-tooltip>
            </template>
          </a-table-column>
          <a-table-column title="类型" :min-width="100" align="center">
            <template #cell="{ record }">
              <a-tag v-if="record.deviceType === 2" color="purple" size="small">下级平台</a-tag>
              <a-tag v-else color="arcoblue" size="small">设备</a-tag>
            </template>
          </a-table-column>
          <a-table-column title="协议" :width="90" align="center">
            <template #cell="{ record }">
              <a-tag v-if="record.accessProtocol === 'ONVIF'" color="orange" size="small">ONVIF</a-tag>
              <a-tag v-else-if="record.accessProtocol === 'RTSP'" color="cyan" size="small">拉流代理</a-tag>
              <a-tag v-else color="gray" size="small">国标</a-tag>
            </template>
          </a-table-column>
          <!-- 通道数: 多通道≈NVR/编码器、单通道≈IPC 的辅助识别(协议层无可靠字段, 启发式);
               点击直达该设备的通道抽屉(与操作列「通道」按钮同效) -->
          <a-table-column title="通道数" :width="80" align="center">
            <template #cell="{ record }">
              <a-link @click="openChannels(record)">{{ record.channelCount ?? 0 }}</a-link>
            </template>
          </a-table-column>
          <!-- 双行单元格(dev-name/dev-sub): Arco 原生 ellipsis/tooltip 只对纯文本列生效,
               手动单行截断+外层 Tooltip hover 显示全文 -->
          <a-table-column title="厂商 / 型号" :min-width="120">
            <template #cell="{ record }">
              <a-tooltip position="tl">
                <template #content>
                  <div>厂商：{{ record.manufacturer || '-' }}</div>
                  <div>型号：{{ record.model || '-' }}</div>
                </template>
                <div class="dev-clip">
                  <div class="dev-name">{{ record.manufacturer || '-' }}</div>
                  <div class="dev-sub">{{ record.model || '-' }}</div>
                </div>
              </a-tooltip>
            </template>
          </a-table-column>
          <a-table-column title="传输" :min-width="60" align="center">
            <template #cell="{ record }">
              <!-- 传输语义按协议分流: RTSP=RTP收流传输(rtsp_transport); GB=SIP信令传输(transport);
                   ONVIF 无传输概念(行值为建表默认污染)显示 '-' -->
              <a-tag v-if="record.accessProtocol === 'RTSP'" size="small" color="cyan">
                {{ (record.rtspTransport || 'tcp').toUpperCase() }}
              </a-tag>
              <a-tag
                v-else-if="record.accessProtocol === 'GB' && record.transport"
                size="small"
                color="arcoblue"
              >
                {{ record.transport }}
              </a-tag>
              <span v-else>-</span>
            </template>
          </a-table-column>
          <a-table-column title="状态" :min-width="110">
            <template #cell="{ record }">
              <a-space direction="vertical" :size="4">
                <a-tag size="small" :color="record.online === 1 ? 'green' : 'gray'">
                  {{ record.online === 1 ? '在线' : '离线' }}
                </a-tag>
                <template v-if="record.syncTotal !== undefined">
                  <a-progress :percent="percent(record)" size="small" :show-text="false" class="sync-bar" />
                  <span class="dev-sub">目录同步 {{ record.syncSynced ?? 0 }}/{{ record.syncTotal }}</span>
                </template>
              </a-space>
            </template>
          </a-table-column>
          <a-table-column title="设备地址" :width="210">
            <template #cell="{ record }">
              <span v-if="record.ip">{{ record.ip }}:{{ record.port }}</span>
              <span v-else>-</span>
            </template>
          </a-table-column>
          <a-table-column title="注册时间" :width="170">
            <template #cell="{ record }">{{ fmtTime(record.registerTime) }}</template>
          </a-table-column>
          <a-table-column title="操作" :width="260" fixed="right" align="left">
            <template #cell="{ record }">
              <a-space :size="2">
                <a-button type="text" size="mini" @click="openChannels(record)">通道</a-button>
                <a-button v-if="auth.hasPerm('device:refresh') && record.accessProtocol !== 'RTSP'" type="text" size="mini" :disabled="record.syncTotal !== undefined" @click="refreshCatalog(record)"> 刷新目录 </a-button>
                <a-button v-if="auth.hasPerm('device:update')" type="text" size="mini" @click="openEdit(record)"> 编辑 </a-button>
                <a-button v-if="auth.hasPerm('device:delete')" type="text" size="mini" status="danger" @click="confirmDelete(record)"> 删除 </a-button>
              </a-space>
            </template>
          </a-table-column>
        </template>
      </a-table>
    </a-card>

    <ChannelsDrawer v-model:visible="drawerVisible" :device="drawerDevice" ref="drawerRef" />
    <EditDrawer v-model:visible="editVisible" :device="editDevice" @saved="load(true)" />
    <CreateDrawer v-model:visible="createVisible" @saved="load(true)" />
    <OnvifCreateDrawer v-model:visible="onvifCreateVisible" @saved="load(true)" />
    <OnvifEditDrawer v-model:visible="onvifEditVisible" :device="editDevice" @saved="load(true)" />
    <RtspCreateDrawer v-model:visible="rtspCreateVisible" @saved="load(true)" />
    <RtspEditDrawer v-model:visible="rtspEditVisible" :device="editDevice" @saved="load(true)" />
  </div>
</template>

<style scoped lang="less">
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.dev-name {
  color: var(--color-text-1);
}

/* 双行单元格(设备/厂商型号列)单行截断: 内容超宽显省略号, 全文由外层 a-tooltip hover 展示 */
.dev-clip .dev-name,
.dev-clip .dev-sub {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dev-sub {
  font-size: 12px;
  color: var(--color-text-3);
  line-height: 18px;
}

.sync-bar {
  width: 110px;
}
</style>
