<template>
  <a-drawer :visible="visible" width="560px" unmount-on-close @cancel="close" @ok="submit">
    <template #title>
      <div class="drawer-title">
        <icon-link class="title-icon" />
        <span>{{ mode === 'single' ? '添加拉流代理' : '批量导入拉流代理' }}</span>
      </div>
    </template>
    <a-tabs v-model:active-key="mode" type="rounded" size="medium">
      <a-tab-pane key="single" title="单个新增">
        <a-form ref="formRef" :model="form" layout="vertical">
          <a-form-item label="设备名称" field="name" help="留空自动命名（拉流代理-N）">
            <a-input v-model="form.name" placeholder="如: 广场东侧枪机" allow-clear :max-length="128" />
          </a-form-item>
          <a-form-item label="拉流地址" field="sourceUrl" required
            :rules="[{ required: true, message: '拉流地址不能为空' }, { validator: validateUrl }]"
            help="rtsp://[用户名:密码@]主机[:端口]/路径，整串加密存储">
            <a-input v-model="form.sourceUrl" placeholder="rtsp://admin:pass@192.168.1.64:554/Streaming/Channels/101"
              allow-clear />
          </a-form-item>
          <a-form-item label="传输协议" field="rtspTransport" help="TCP 抗丢包（推荐）; UDP 低延迟">
            <a-radio-group v-model="form.rtspTransport" type="button">
              <a-radio value="tcp">TCP</a-radio>
              <a-radio value="udp">UDP</a-radio>
            </a-radio-group>
          </a-form-item>
          <a-form-item label="常驻拉流" field="alwaysOn"
            help="开启后平台启动即拉流、断线自动重建（观众走光不断流）">
            <a-switch v-model="form.alwaysOn" :checked-value="1" :unchecked-value="0" />
          </a-form-item>
          <a-form-item label="探活周期(秒)" field="heartbeatInterval" help="在线态探测周期, 10~3600">
            <a-input-number v-model="form.heartbeatInterval" :min="10" :max="3600" style="width: 180px" />
          </a-form-item>
        </a-form>
      </a-tab-pane>
      <a-tab-pane key="batch" title="批量导入">
        <a-form ref="batchFormRef" :model="batchForm" layout="vertical">
          <a-form-item help="每行一台设备: 纯 URL（自动命名）或「名称,URL」; # 开头为注释行; 单次最多 200 行">
            <a-textarea v-model="batchForm.text" :auto-size="{ minRows: 8, maxRows: 14 }"
              placeholder="拉流代理一&#10;rtsp://admin:pass@192.168.1.64:554/Streaming/Channels/101&#10;rtsp://admin:pass@192.168.1.65:554/h264" />
          </a-form-item>
          <a-form-item label="传输协议">
            <a-radio-group v-model="batchForm.rtspTransport" type="button">
              <a-radio value="tcp">TCP</a-radio>
              <a-radio value="udp">UDP</a-radio>
            </a-radio-group>
          </a-form-item>
          <a-form-item label="常驻拉流">
            <a-switch v-model="batchForm.alwaysOn" :checked-value="1" :unchecked-value="0" />
          </a-form-item>
          <a-alert v-if="batchResult" :type="batchResult.failed.length === 0 ? 'success' : 'warning'">
            共 {{ batchResult.total }} 行: 成功 {{ batchResult.success }}、重复跳过 {{ batchResult.duplicated }}
            、失败 {{ batchResult.failed.length }}
            <div v-for="f in batchResult.failed" :key="f.lineNo" class="fail-line">
              第 {{ f.lineNo }} 行: {{ f.line }} — {{ f.reason }}
            </div>
          </a-alert>
        </a-form>
      </a-tab-pane>
    </a-tabs>
    <template #footer>
      <a-space>
        <a-button @click="close">取消</a-button>
        <a-button v-if="mode === 'batch'" type="primary" :loading="batchLoading" @click="submitBatch">
          导入
        </a-button>
        <a-button v-else type="primary" :loading="loading" @click="submit">确定</a-button>
      </a-space>
    </template>
  </a-drawer>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import { deviceApi, type RtspBatchImportResult } from '@/api/device'

const visible = defineModel<boolean>('visible', { default: false })
const emit = defineEmits<{ saved: [] }>()

const mode = ref<'single' | 'batch'>('single')
const formRef = ref()
const batchFormRef = ref()
const loading = ref(false)
const batchLoading = ref(false)
const batchResult = ref<RtspBatchImportResult | null>(null)

const form = reactive({
  name: '',
  sourceUrl: '',
  rtspTransport: 'tcp',
  alwaysOn: 0,
  heartbeatInterval: 60
})
const batchForm = reactive({ text: '', rtspTransport: 'tcp', alwaysOn: 0 })

const validateUrl = (_v: string, cb: (msg?: string) => void) => {
  const u = (form.sourceUrl || '').trim()
  if (!u) return cb()
  if (!u.toLowerCase().startsWith('rtsp://')) return cb('拉流地址须以 rtsp:// 开头')
  cb()
}

const close = () => {
  visible.value = false
  form.name = ''
  form.sourceUrl = ''
  form.rtspTransport = 'tcp'
  form.alwaysOn = 0
  form.heartbeatInterval = 60
  batchForm.text = ''
  batchResult.value = null
}

const submit = async () => {
  const r = await formRef.value?.validate()
  if (r) return
  loading.value = true
  try {
    await deviceApi.createRtsp({
      name: form.name || undefined,
      sourceUrl: form.sourceUrl.trim(),
      rtspTransport: form.rtspTransport,
      alwaysOn: form.alwaysOn,
      heartbeatInterval: form.heartbeatInterval
    })
    Message.success('拉流代理已添加')
    emit('saved')
    close()
  } finally {
    loading.value = false
  }
}

const submitBatch = async () => {
  if (!batchForm.text.trim()) {
    Message.warning('请粘贴导入内容')
    return
  }
  batchLoading.value = true
  try {
    batchResult.value = await deviceApi.batchImportRtsp({
      text: batchForm.text,
      rtspTransport: batchForm.rtspTransport,
      alwaysOn: batchForm.alwaysOn
    })
    const r = batchResult.value
    if (r.success > 0) {
      Message.success(`导入完成: 成功 ${r.success} 台`)
      emit('saved')
    } else {
      Message.warning('没有设备被导入')
    }
  } finally {
    batchLoading.value = false
  }
}
</script>

<style scoped>
.drawer-title {
  display: flex;
  align-items: center;
  gap: 8px;
}
.title-icon {
  color: rgb(var(--arcoblue-6));
}
.fail-line {
  margin-top: 4px;
  font-size: 12px;
  word-break: break-all;
}
</style>
