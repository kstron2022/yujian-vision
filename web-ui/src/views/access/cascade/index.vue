<template>
  <div class="page-cascade">
    <a-card :bordered="false" class="general-card">
      <a-space direction="vertical" fill style="width: 100%">
        <a-space wrap>
          <a-input-search v-model="keyword" :style="{ width: '260px' }" placeholder="名称/上级编码" allow-clear @search="load" @clear="load" />
          <a-button type="primary" v-if="auth.hasPerm('cascade:config')" @click="openEdit(null)">
            <template #icon><icon-plus /></template>
            新增上级
          </a-button>
          <a-button @click="load">
            <template #icon><icon-refresh /></template>
            刷新
          </a-button>
        </a-space>

        <a-table :data="list" :loading="loading" :pagination="pagination" :scroll="{ x: 1320 }" size="medium" row-key="id">
          <template #columns>
            <!-- 列宽惯例: 编码/时间等定长内容列固定 width; 名称/地址长度不可预期用 min-width+ellipsis
                 tooltip, 宽屏时自动伸展 -->
            <a-table-column title="名称" :min-width="140" ellipsis tooltip>
              <template #cell="{ record }">{{ record.name }}</template>
            </a-table-column>
            <a-table-column title="上级编码" :width="190" ellipsis tooltip>
              <template #cell="{ record }">{{ record.upperDeviceId }}</template>
            </a-table-column>
            <a-table-column title="本级编码" :width="190" ellipsis tooltip>
              <template #cell="{ record }">{{ record.localDeviceId }}</template>
            </a-table-column>
            <a-table-column title="上级地址" :min-width="150" ellipsis tooltip>
              <template #cell="{ record }">{{ record.upperIp }}:{{ record.upperPort }}/{{ record.transport }}</template>
            </a-table-column>
            <a-table-column title="状态" :width="80" align="center">
              <template #cell="{ record }">
                <a-tag :color="record.status === 1 ? 'green' : 'gray'">
                  {{ record.status === 1 ? '启用' : '停用' }}
                </a-tag>
              </template>
            </a-table-column>
            <a-table-column title="注册" :width="80" align="center">
              <template #cell="{ record }">
                <a-tag :color="record.online === 1 ? 'green' : 'red'">
                  {{ record.online === 1 ? '在线' : '离线' }}
                </a-tag>
              </template>
            </a-table-column>
            <a-table-column title="注册时间" :width="180">
              <template #cell="{ record }">{{ fmtDateTime(record.registerTime) }}</template>
            </a-table-column>
            <a-table-column title="操作" :width="320" fixed="right">
              <template #cell="{ record }">
                <!-- size=2 对齐国标设备页操作列惯例, 5 个动作紧凑单行 -->
                <a-space :size="2">
                  <a-button size="mini" type="text" v-if="auth.hasPerm('cascade:config')" @click="openEdit(record)">编辑</a-button>
                  <a-button size="mini" type="text" v-if="auth.hasPerm('cascade:config')" @click="toggleEnable(record)">
                    {{ record.status === 1 ? '停用' : '启用' }}
                  </a-button>
                  <a-button size="mini" type="text" v-if="auth.hasPerm('cascade:config')" @click="openChannels(record)">共享通道</a-button>
                  <a-button size="mini" type="text" v-if="auth.hasPerm('cascade:config')" @click="push(record)">推送目录</a-button>
                  <a-popconfirm content="确认删除该上级配置？" @ok="removeUpper(record)">
                    <a-button size="mini" type="text" status="danger" v-if="auth.hasPerm('cascade:config')">删除</a-button>
                  </a-popconfirm>
                </a-space>
              </template>
            </a-table-column>
          </template>
        </a-table>
      </a-space>
    </a-card>

    <!-- 新增/编辑抽屉 -->
    <a-drawer v-model:visible="editVisible" :width="640" unmount-on-close :ok-loading="saving" ok-text="保存" @ok="save" @cancel="editVisible = false">
      <template #title>
        <span class="dlg-title"><icon-link /> {{ editing && editing.id ? '编辑上级平台' : '新增上级平台' }}</span>
      </template>
      <a-form ref="formRef" :model="form" layout="vertical">
        <a-form-item label="上级平台名称" field="name" required>
          <a-input v-model="form.name" placeholder="如: 市局平台" />
        </a-form-item>
        <a-form-item label="上级平台编码" field="upperDeviceId" required help="上级分配给本平台的对接编码（上级平台的国标 ID）">
          <a-input v-model="form.upperDeviceId" placeholder="如 34020000002000000001" />
        </a-form-item>
        <a-form-item label="本平台编码（上级侧）" field="localDeviceId" required help="本平台在上级侧登记的国标编码">
          <a-input v-model="form.localDeviceId" placeholder="如 34020000001320000001" />
        </a-form-item>
        <a-row :gutter="12">
          <a-col :span="16">
            <a-form-item label="上级地址" field="upperIp" required>
              <a-input v-model="form.upperIp" placeholder="IP 或域名" />
            </a-form-item>
          </a-col>
          <a-col :span="8">
            <a-form-item label="端口" field="upperPort">
              <a-input-number v-model="form.upperPort" :min="1" :max="65535" hide-button />
            </a-form-item>
          </a-col>
        </a-row>
        <a-form-item label="级联密码" field="password" :help="editing && editing.id ? '留空=不修改' : ''">
          <a-input-password v-model="form.password" placeholder="上级侧登记的接入密码" />
        </a-form-item>
        <a-row :gutter="12">
          <a-col :span="8">
            <a-form-item label="传输协议" field="transport">
              <a-radio-group v-model="form.transport" type="button">
                <a-radio value="UDP">UDP</a-radio>
                <a-radio value="TCP">TCP</a-radio>
              </a-radio-group>
            </a-form-item>
          </a-col>
          <a-col :span="8">
            <a-form-item label="注册有效期(s)" field="expires">
              <a-input-number v-model="form.expires" :min="60" hide-button />
            </a-form-item>
          </a-col>
          <a-col :span="8">
            <a-form-item label="心跳周期(s)" field="keepaliveInterval">
              <a-input-number v-model="form.keepaliveInterval" :min="10" hide-button />
            </a-form-item>
          </a-col>
        </a-row>
        <a-form-item label="目录上报模式" field="catalogMode">
          <a-radio-group v-model="form.catalogMode" type="button">
            <a-radio :value="1">仅共享通道</a-radio>
            <a-radio :value="2">全部通道</a-radio>
          </a-radio-group>
        </a-form-item>
        <a-form-item label="目录变化主动通知" field="autoPushCatalog">
          <a-switch :model-value="form.autoPushCatalog === 1" @change="v => (form.autoPushCatalog = v ? 1 : 0)" />
          <span class="form-tip">共享通道变化时主动推送目录（2016 通知式, 上级兼容性需联调确认）</span>
        </a-form-item>
        <a-form-item label="云台指令透传" field="ptzForward">
          <a-switch :model-value="form.ptzForward === 1" @change="v => (form.ptzForward = v ? 1 : 0)" />
          <span class="form-tip">上级下发云台控制转发到本平台设备（B2 中转生效）</span>
        </a-form-item>
      </a-form>
    </a-drawer>

    <!-- 共享通道抽屉 -->
    <a-drawer v-model:visible="channelsVisible" :width="700" title="共享通道配置" unmount-on-close :ok-loading="sharing" ok-text="保存" @ok="saveShared" @cancel="channelsVisible = false">
      <a-space direction="vertical" fill style="width: 100%">
        <a-alert type="info"> 勾选共享给「{{ editing && editing.name ? editing.name : '' }}」的通道；虚拟编码留空=使用原编码。 </a-alert>
        <a-input-search v-model="channelKeyword" :style="{ width: '260px' }" placeholder="搜索通道" allow-clear @search="refilter" @clear="refilter" @input="refilter" />
        <a-tree :data="treeData" checkable block-node :check-strictly="false" v-model:checked-keys="checkedKeys" :style="{ maxHeight: '320px', overflow: 'auto' }" />
        <a-table v-if="selectedRows.length" :data="selectedRows" :pagination="false" :scroll="{ x: 520 }" size="mini" row-key="channelDbId">
          <template #columns>
            <a-table-column title="通道编码" :width="190" ellipsis>
              <template #cell="{ record }">{{ record.channelId }}</template>
            </a-table-column>
            <a-table-column title="名称" :width="160" ellipsis>
              <template #cell="{ record }">{{ record.name }}</template>
            </a-table-column>
            <a-table-column title="虚拟编码" :width="170">
              <template #cell="{ record }">
                <a-input v-model="record.virtualDeviceId" placeholder="留空=原编码" size="mini" :max-length="50" />
              </template>
            </a-table-column>
          </template>
        </a-table>
      </a-space>
    </a-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, onUnmounted, reactive, ref } from 'vue'
import { Message, Modal } from '@arco-design/web-vue'
import { cascadeApi, type CascadeUpper, type CascadeSessions, type SharedChannelBinding } from '@/api/cascade'
import { deviceApi, type ChannelTreeNode } from '@/api/device'
import { useAuthStore } from '@/stores/auth'
import { WsEvent, wsClient } from '@/utils/ws'
import { fmtDateTime } from '@/utils/format'

const auth = useAuthStore()
const keyword = ref('')
const list = ref<CascadeUpper[]>([])
const sessions = ref<CascadeSessions>({})
const loading = ref(false)
const page = ref(1)
const pageSize = ref(10)
const total = ref(0)

const pagination = computed(() => ({
  total: total.value,
  current: page.value,
  pageSize: pageSize.value,
  showTotal: true,
  pageSizeOptions: [10, 20, 50],
  onChange: (p: number) => {
    page.value = p
    load()
  },
  onPageSizeChange: (s: number) => {
    pageSize.value = s
    page.value = 1
    load()
  }
}))

const editVisible = ref(false)
const channelsVisible = ref(false)
const saving = ref(false)
const sharing = ref(false)
const editing = ref<CascadeUpper | null>(null)
const formRef = ref()
const form = reactive<Partial<CascadeUpper> & { password?: string }>({})

const channelKeyword = ref('')
const fullTree = ref<ChannelTreeNode[]>([])
/** Arco 树展示结构（channel→key/title 映射, 同录像检索页 mapTree 惯例） */
interface TreeOption {
  key: number
  title: string
  children?: TreeOption[]
}
const treeData = ref<TreeOption[]>([])
const checkedKeys = ref<Array<string | number>>([])
const sharedVirtual = ref<Map<number, string>>(new Map())

const selectedRows = computed(() =>
  checkedKeys.value
    .map(k => Number(k))
    .filter(id => allLeafChannels.value.has(id))
    .map(id => ({
      channelDbId: id,
      channelId: allLeafChannels.value.get(id)?.channelId || '',
      name: allLeafChannels.value.get(id)?.name || '',
      virtualDeviceId: sharedVirtual.value.get(id) || ''
    }))
)

const allLeafChannels = ref<Map<number, { channelId: string; name: string }>>(new Map())

async function load() {
  loading.value = true
  try {
    const res = await cascadeApi.page({ keyword: keyword.value || undefined, page: page.value, count: pageSize.value })
    list.value = res.list
    sessions.value = res.sessions || {}
    total.value = res.total
  } finally {
    loading.value = false
  }
}

function openEdit(record: CascadeUpper | null) {
  editing.value = record
  Object.keys(form).forEach(k => delete (form as Record<string, unknown>)[k])
  if (record) {
    Object.assign(form, record)
    form.password = ''
  } else {
    Object.assign(form, {
      name: '',
      upperDeviceId: '',
      localDeviceId: '',
      upperIp: '',
      upperPort: 5060,
      transport: 'UDP',
      expires: 3600,
      keepaliveInterval: 60,
      catalogMode: 1,
      autoPushCatalog: 0,
      ptzForward: 1,
      password: ''
    })
  }
  editVisible.value = true
}

async function save() {
  saving.value = true
  try {
    if (editing.value && editing.value.id) {
      await cascadeApi.update(editing.value.id, form)
      Message.success('已保存')
    } else {
      await cascadeApi.create(form)
      Message.success('已新增（默认停用, 请编辑后启用）')
    }
    editVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function toggleEnable(record: CascadeUpper) {
  await cascadeApi.enable(record.id, record.status === 1 ? 0 : 1)
  Message.success(record.status === 1 ? '已停用' : '已启用, 级联 UA 将向上级发起注册')
  await load()
}

async function removeUpper(record: CascadeUpper) {
  await cascadeApi.remove(record.id)
  Message.success('已删除')
  await load()
}

async function push(record: CascadeUpper) {
  await cascadeApi.pushCatalog(record.id)
  Message.success('目录已推送（Notify/Catalog, 上级是否入库以其实现为准）')
}

async function openChannels(record: CascadeUpper) {
  editing.value = record
  channelKeyword.value = ''
  sharedVirtual.value = new Map()
  const [tree, shared] = await Promise.all([deviceApi.channelTree(), cascadeApi.sharedChannels(record.id)])
  fullTree.value = tree
  treeData.value = mapTree(tree)
  collectLeaves(tree)
  checkedKeys.value = shared.map(s => s.channelDbId)
  shared.forEach(s => sharedVirtual.value.set(s.channelDbId, s.virtualDeviceId || ''))
  channelsVisible.value = true
}

function filterTree(nodes: ChannelTreeNode[], kw: string): ChannelTreeNode[] {
  const hit = (n: ChannelTreeNode) => !kw || (n.channel.name || '').includes(kw) || (n.channel.channelId || '').includes(kw)
  const walk = (n: ChannelTreeNode): ChannelTreeNode | null => {
    const children = (n.children || []).map(walk).filter((x): x is ChannelTreeNode => x !== null)
    if (hit(n) || children.length) return { channel: n.channel, children }
    return null
  }
  return nodes.map(walk).filter((x): x is ChannelTreeNode => x !== null)
}

function refilter() {
  treeData.value = mapTree(filterTree(fullTree.value, channelKeyword.value.trim()))
}

function mapTree(nodes: ChannelTreeNode[]): TreeOption[] {
  return nodes.map(n => ({
    key: n.channel.id,
    title: n.channel.customName || n.channel.name || n.channel.channelId,
    children: n.children?.length ? mapTree(n.children) : undefined
  }))
}

function collectLeaves(nodes: ChannelTreeNode[]) {
  const map = new Map<number, { channelId: string; name: string }>()
  const walk = (n: ChannelTreeNode) => {
    if (n.children && n.children.length) {
      n.children.forEach(walk)
    } else {
      map.set(n.channel.id, { channelId: n.channel.channelId, name: n.channel.name })
    }
  }
  nodes.forEach(walk)
  allLeafChannels.value = map
}

async function saveShared() {
  if (!editing.value) return
  const bindings: SharedChannelBinding[] = selectedRows.value.map(r => ({
    channelDbId: r.channelDbId,
    virtualDeviceId: r.virtualDeviceId || ''
  }))
  sharing.value = true
  try {
    const n = await cascadeApi.replaceShared(editing.value.id, bindings)
    Message.success(`已保存共享通道 ${n} 条`)
    channelsVisible.value = false
    await load()
  } finally {
    sharing.value = false
  }
}

function onWs(type: string, data: Record<string, unknown>) {
  const deviceId = data.deviceId as string | undefined
  if (!deviceId) return
  if (type === WsEvent.DeviceOnline || type === WsEvent.DeviceOffline) {
    // 设备上下线影响目录条目 Status, 静默刷新本页
    void load()
  }
}

onMounted(() => {
  void load()
  unbindWs = wsClient.on(onWs)
})
onActivated(() => void load())
onUnmounted(() => unbindWs?.())

let unbindWs: (() => void) | undefined
</script>

<style scoped>
.form-tip {
  margin-left: 8px;
  font-size: 12px;
  color: var(--color-text-3);
}
</style>
