<template>
  <a-drawer :visible="visible" width="560px" unmount-on-close @cancel="close" @ok="submit">
    <template #title>
      <div class="drawer-title">
        <icon-link class="title-icon" />
        <span>编辑拉流代理</span>
      </div>
    </template>
    <a-form ref="formRef" :model="form" layout="vertical">
      <a-form-item label="国标编码">
        <a-input :model-value="device?.deviceId" disabled />
      </a-form-item>
      <a-form-item label="设备名称" field="name">
        <a-input v-model="form.name" allow-clear :max-length="128" />
      </a-form-item>
      <a-form-item label="拉流地址" field="sourceUrl"
        help="留空表示不修改（加密盲显, 不回显明文）">
        <a-input v-model="form.sourceUrl" placeholder="不修改请留空" allow-clear />
      </a-form-item>
      <a-form-item label="传输协议" field="rtspTransport" help="变更后常驻代理自动按新协议重建">
        <a-radio-group v-model="form.rtspTransport" type="button">
          <a-radio value="tcp">TCP</a-radio>
          <a-radio value="udp">UDP</a-radio>
        </a-radio-group>
      </a-form-item>
      <a-form-item label="常驻拉流" field="alwaysOn"
        help="开启后平台启动即拉流、断线自动重建（观众走光不断流）; 关闭会拆除常驻代理">
        <a-switch v-model="form.alwaysOn" :checked-value="1" :unchecked-value="0" />
      </a-form-item>
      <a-form-item label="探活周期(秒)" field="heartbeatInterval" help="在线态探测周期, 10~3600">
        <a-input-number v-model="form.heartbeatInterval" :min="10" :max="3600" style="width: 180px" />
      </a-form-item>
    </a-form>
    <template #footer>
      <a-space>
        <a-button @click="close">取消</a-button>
        <a-button type="primary" :loading="loading" @click="submit">保存</a-button>
      </a-space>
    </template>
  </a-drawer>
</template>

<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { Message } from '@arco-design/web-vue'
import { deviceApi, type GbDevice } from '@/api/device'

const visible = defineModel<boolean>('visible', { default: false })
const emit = defineEmits<{ saved: [] }>()

const props = defineProps<{ device: GbDevice | null }>()

const formRef = ref()
const loading = ref(false)
const form = reactive({
  name: '',
  sourceUrl: '',
  rtspTransport: 'tcp',
  alwaysOn: 0,
  heartbeatInterval: 60
})

watch(
  () => props.device,
  (d) => {
    if (d) {
      form.name = d.name ?? ''
      form.sourceUrl = ''
      form.rtspTransport = d.rtspTransport ?? 'tcp'
      form.alwaysOn = d.alwaysOn ?? 0
      form.heartbeatInterval = d.heartbeatInterval ?? 60
    }
  },
  { immediate: true }
)

const close = () => {
  visible.value = false
}

const submit = async () => {
  if (!props.device) return
  loading.value = true
  try {
    await deviceApi.updateRtsp(props.device.deviceId, {
      name: form.name || undefined,
      // 源地址盲显: 留空 = 不修改（与设备密码同口径）
      sourceUrl: form.sourceUrl.trim() || undefined,
      rtspTransport: form.rtspTransport,
      alwaysOn: form.alwaysOn,
      heartbeatInterval: form.heartbeatInterval
    })
    Message.success('已保存')
    emit('saved')
    close()
  } finally {
    loading.value = false
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
</style>
