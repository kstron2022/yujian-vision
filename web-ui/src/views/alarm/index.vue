<script setup lang="ts">
import { computed, onActivated, onMounted, onUnmounted, reactive, ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import { handleAlarm, pageAlarms } from '@/api/alarm'
import type { AlarmItem } from '@/api/alarm'
import { WsEvent, wsClient } from '@/utils/ws'
import { fmtDateTime } from '@/utils/format'

/**
 * M3-B4 报警中心: 报警列表 + 处理状态流转。
 * 实时: WS ALARM 事件到达时——仅在第一页且筛选为默认时静默刷新(合并 400ms 防连环请求);
 * 新消息角标/站内信走消息中心, 本页不重复承担。
 */

/** 报警依据类型（国标 alarm_type 字典, 与 V21 迁移种子一致; 未知值原样展示） */
const ALARM_TYPE_LABELS: Record<number, string> = {
  1: '视频报警',
  2: '设备故障',
  3: '围墙门窗',
  4: '入侵报警',
  5: '求助',
  6: '珠宝箱报警',
  7: '火警',
  8: '紧急报警'
}

/** 报警方式 1电话 2短信 3视频 4设备联网 5图片 6人工 */
const ALARM_METHOD_LABELS: Record<number, string> = {
  1: '电话',
  2: '短信',
  3: '视频',
  4: '设备联网',
  5: '图片',
  6: '人工'
}

/** 级别 1一级 2二级 3三级 4四级 → 文案/颜色（color: 1红 2橙 3蓝 4灰） */
const ALARM_PRIORITY_LABELS: Record<number, string> = { 1: '一级', 2: '二级', 3: '三级', 4: '四级' }
function priorityColor(v: number): string {
  return ['orangered', 'orange', 'arcoblue', 'gray'][v - 1] ?? 'gray'
}

const rows = ref<AlarmItem[]>([])
const total = ref(0)
const loading = ref(false)
const query = reactive({
  deviceId: '',
  keyword: '',
  handled: -1,
  page: 1,
  count: 10
})
const range = ref<string[]>([])

/** 选完两日期或清空即查询（value 已格式化为 yyyy-MM-dd; 清空=不过滤时间） */
function onRangeChange(v: unknown) {
  const arr = v as string[] | undefined
  range.value = arr && arr.length === 2 ? arr : []
  search()
}

/** 本会话已提交处理请求的报警 id（防重复提交, 同消息中心 readSet 惯例） */
const handlingSet = new Set<number>()

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

const isDefaultQuery = () =>
  !query.deviceId && !query.keyword && query.handled === -1 && !range.value.length

async function load(silent = false) {
  if (!silent) loading.value = true
  try {
    const data = await pageAlarms({
      deviceId: query.deviceId || undefined,
      keyword: query.keyword || undefined,
      handled: query.handled === -1 ? undefined : query.handled,
      start: range.value[0] ? `${range.value[0]} 00:00:00` : undefined,
      end: range.value[1] ? `${range.value[1]} 23:59:59` : undefined,
      page: query.page,
      count: query.count
    })
    rows.value = data.list
    total.value = data.total
  } finally {
    loading.value = false
  }
}

/** WS ALARM 触发的刷新做合并, 报警风暴时避免连环请求 */
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
  query.deviceId = ''
  query.keyword = ''
  query.handled = -1
  range.value = []
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

/** 处理: popconfirm 确认 → 已处理流转 → 刷新当前页（失败提示由 http 拦截器弹出） */
async function onHandle(row: AlarmItem) {
  if (handlingSet.has(row.id)) return
  handlingSet.add(row.id)
  try {
    await handleAlarm(row.id)
  } catch {
    handlingSet.delete(row.id)
    return
  }
  Message.success('已处理')
  void load(true)
}

function onWs(type: string) {
  if (type !== WsEvent.Alarm) return
  // 只在第一页 + 默认筛选时刷新, 避免打断用户正在看的过滤结果
  if (query.page === 1 && isDefaultQuery()) reloadSoon()
}

onMounted(() => {
  void load()
  unbindWs = wsClient.on(onWs)
})
onActivated(() => void load(true))
onUnmounted(() => unbindWs?.())
</script>

<template>
  <div class="page-alarms">
    <a-card :bordered="false" class="general-card">
      <div class="toolbar">
        <a-space wrap>
          <a-input v-model="query.deviceId" placeholder="设备编码" allow-clear style="width: 190px" @clear="search" @press-enter="search" />
          <a-input-search v-model="query.keyword" placeholder="描述 / 通道 / 设备" allow-clear style="width: 210px" @search="search" @clear="search" />
          <a-select v-model="query.handled" :style="{ width: '108px' }" @change="search">
            <a-option :value="-1">全部</a-option>
            <a-option :value="0">未处理</a-option>
            <a-option :value="1">已处理</a-option>
          </a-select>
          <a-range-picker :model-value="range" value-format="YYYY-MM-DD" style="width: 250px" allow-clear @change="onRangeChange" />
          <a-button type="primary" size="small" @click="search">查询</a-button>
          <a-button size="small" @click="reset">重置</a-button>
        </a-space>
        <a-space>
          <a-button type="text" size="small" :loading="loading" @click="load()">
            <template #icon><icon-refresh /></template>
            刷新
          </a-button>
        </a-space>
      </div>

      <a-table :data="rows" :loading="loading" :pagination="pagination" :scroll="{ x: 1300 }" row-key="id" size="medium" @page-change="onPage" @page-size-change="onPageSize">
        <template #columns>
          <!-- 列宽惯例(docs/09 B5): 报警时间定长 :width; 编码/描述长度不可预期用 min-width+ellipsis tooltip -->
          <a-table-column title="报警时间" :width="170">
            <template #cell="{ record }">{{ fmtDateTime(record.alarmTime) }}</template>
          </a-table-column>
          <a-table-column title="设备编码" :min-width="170" ellipsis tooltip>
            <template #cell="{ record }">{{ record.deviceId }}</template>
          </a-table-column>
          <a-table-column title="通道编码" :min-width="170" ellipsis tooltip>
            <template #cell="{ record }">{{ record.channelId || '-' }}</template>
          </a-table-column>
          <a-table-column title="类型" :width="110" align="center">
            <template #cell="{ record }">
              <a-tag v-if="ALARM_TYPE_LABELS[record.alarmType]" size="small" color="purple">
                {{ ALARM_TYPE_LABELS[record.alarmType] }}
              </a-tag>
              <span v-else-if="record.alarmType !== null && record.alarmType !== undefined">{{ record.alarmType }}</span>
              <span v-else>-</span>
            </template>
          </a-table-column>
          <a-table-column title="级别" :width="90" align="center">
            <template #cell="{ record }">
              <a-tag size="small" :color="priorityColor(record.alarmPriority)">
                {{ ALARM_PRIORITY_LABELS[record.alarmPriority] ?? record.alarmPriority }}
              </a-tag>
            </template>
          </a-table-column>
          <a-table-column title="方式" :width="90" align="center">
            <template #cell="{ record }">{{ ALARM_METHOD_LABELS[record.alarmMethod] ?? '-' }}</template>
          </a-table-column>
          <a-table-column title="描述" :min-width="160" ellipsis tooltip>
            <template #cell="{ record }">{{ record.description || '-' }}</template>
          </a-table-column>
          <a-table-column title="状态" :width="90" align="center">
            <template #cell="{ record }">
              <a-tag size="small" :color="record.handled === 1 ? 'green' : 'red'">
                {{ record.handled === 1 ? '已处理' : '未处理' }}
              </a-tag>
            </template>
          </a-table-column>
          <a-table-column title="处理信息" :min-width="150" ellipsis tooltip>
            <template #cell="{ record }">
              <span v-if="record.handled === 1">{{ record.handleUser }} {{ fmtDateTime(record.handleTime) }}</span>
              <span v-else>-</span>
            </template>
          </a-table-column>
          <a-table-column title="操作" :width="100" fixed="right" align="left">
            <template #cell="{ record }">
              <a-popconfirm
                v-if="record.handled !== 1"
                content="确认将该报警标记为已处理？"
                ok-text="确认"
                cancel-text="取消"
                @ok="onHandle(record)"
              >
                <a-button type="text" size="mini" :disabled="handlingSet.has(record.id)">处理</a-button>
              </a-popconfirm>
              <span v-else>-</span>
            </template>
          </a-table-column>
        </template>
      </a-table>
    </a-card>
  </div>
</template>

<style scoped lang="less">
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
</style>
