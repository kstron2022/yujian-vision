<script setup lang="ts">
  import { computed, reactive, ref, watch } from 'vue';
  import { Message } from '@arco-design/web-vue';
  import type { FormInstance } from '@arco-design/web-vue';
  import { deviceApi } from '@/api/device';
  import type { DeviceAccessInfo, GbDevice } from '@/api/device';
  import { formatProtocolVersion } from '@/utils/format';
  import { copyText } from '@/utils/clipboard';

  /** 设备参数编辑(右侧抽屉, 前端交互规范: CRUD 用抽屉, 标题=icon+文字, footer 固定底部) */
  const props = defineProps<{ visible: boolean; device: GbDevice | null }>();
  const emit = defineEmits<{
    (e: 'update:visible', v: boolean): void;
    (e: 'saved'): void;
  }>();

  const formRef = ref<FormInstance>();
  const saving = ref(false);
  const form = reactive({
    name: '',
    deviceType: 1,
    password: '',
    heartbeatInterval: 60,
    heartbeatTimeout: 300,
    mediaTcpMode: 0,
    protocolVersion: '2016'
  });

  const rules = {
    name: [{ required: true, message: '请输入设备名称' }],
    heartbeatInterval: [{ required: true, message: '请输入心跳周期' }],
    heartbeatTimeout: [{ required: true, message: '请输入心跳超时' }],
    protocolVersion: [{ required: true, message: '请选择协议版本' }]
  };

  /** 显示 GB/T28181-xxxx 风格, 提交/存储仍是原始年份值 */
  const protocolOptions = ['2011', '2016', '2022'].map((v) => ({
    label: formatProtocolVersion(v),
    value: v
  }));

  /** 设备类型（M3-B3 级联标识）: 1=设备 2=下级平台 */
  const deviceTypeOptions = [
    { label: '设备（IPC/NVR）', value: 1 },
    { label: '下级平台', value: 2 }
  ];

  /** 对接信息（M3-B3: 服务器信息+设备信息含明文密码, 供复制发给接入方） */
  const accessInfo = ref<DeviceAccessInfo | null>(null);
  /** 服务器 IP 展示值: 后端未配宣告/具体监听地址（0.0.0.0 全网卡监听）时回落当前访问主机名 */
  const serverIp = computed(() => accessInfo.value?.sipIp || window.location.hostname);

  function buildFullText(info: DeviceAccessInfo): string {
    return [
      '【GB28181 接入信息】',
      `服务器编码：${info.serverId}`,
      `服务器域：${info.domain}`,
      `服务器IP：${info.sipIp || window.location.hostname}`,
      `SIP端口：${info.sipPort}`,
      `信令传输：${info.transports}`,
      `设备编码：${info.deviceId}`,
      `接入密码：（请自行补充）`
    ].join('\n');
  }

  function buildServerText(info: DeviceAccessInfo): string {
    return [
      '【GB28181 服务器信息】',
      `服务器编码：${info.serverId}`,
      `服务器域：${info.domain}`,
      `服务器IP：${info.sipIp || window.location.hostname}`,
      `SIP端口：${info.sipPort}`,
      `信令传输：${info.transports}`
    ].join('\n');
  }

  async function copyAccessInfo(full: boolean) {
    if (!accessInfo.value) return;
    const ok = await copyText(full ? buildFullText(accessInfo.value) : buildServerText(accessInfo.value));
    if (ok) {
      Message.success(full ? '完整对接信息已复制（接入密码请自行补充）' : '服务器信息已复制');
    } else {
      Message.error('复制失败，请手动选择文本复制');
    }
  }

  async function loadAccessInfo(deviceId: string) {
    accessInfo.value = null;
    try {
      accessInfo.value = await deviceApi.deviceAccessInfo(deviceId);
    } catch {
      // 加载失败不打断编辑(权限不足/网络异常时隐藏该区块)
    }
  }

  // GB/T28181-2011 设备普遍无 TCP 收流(SDP a=setup 行 2016 才标准化)——选 2011 默认切 UDP
  watch(
    () => form.protocolVersion,
    (v) => {
      if (v === '2011') form.mediaTcpMode = 0;
    }
  );

  watch(
    () => props.visible,
    (v) => {
      if (!v || !props.device) return;
      form.name = props.device.name || '';
      form.deviceType = props.device.deviceType ?? 1;
      form.password = '';
      form.heartbeatInterval = props.device.heartbeatInterval ?? 60;
      form.heartbeatTimeout = props.device.heartbeatTimeout ?? 300;
      form.mediaTcpMode = props.device.mediaTcpMode ?? 0;
      form.protocolVersion = props.device.protocolVersion || '2016';
      void loadAccessInfo(props.device.deviceId);
    }
  );

  async function save() {
    const err = await formRef.value?.validate();
    if (err) return;
    if (!props.device) return;
    saving.value = true;
    try {
      await deviceApi.update(props.device.deviceId, {
        name: form.name.trim(),
        deviceType: form.deviceType,
        // 密码留空=不修改
        password: form.password || undefined,
        heartbeatInterval: form.heartbeatInterval,
        heartbeatTimeout: form.heartbeatTimeout,
        mediaTcpMode: form.mediaTcpMode,
        protocolVersion: form.protocolVersion
      });
      Message.success('设备参数已保存');
      emit('update:visible', false);
      emit('saved');
    } finally {
      saving.value = false;
    }
  }
</script>

<template>
  <a-drawer
    :visible="visible"
    :width="560"
    unmount-on-close
    :ok-loading="saving"
    ok-text="保存"
    cancel-text="取消"
    @ok="save"
    @cancel="emit('update:visible', false)"
    @close="emit('update:visible', false)"
  >
    <template #title>
      <span class="dlg-title">
        <icon-edit />
        编辑设备参数
      </span>
    </template>

    <a-form ref="formRef" :model="form" :rules="rules" layout="vertical">
      <a-form-item label="设备名称" field="name">
        <a-input v-model="form.name" placeholder="设备显示名称" allow-clear :max-length="64" />
      </a-form-item>
      <a-form-item label="设备类型" help="下级平台=通过国标注册接入的平台级设备（级联）">
        <a-radio-group v-model="form.deviceType" type="button">
          <a-radio v-for="opt in deviceTypeOptions" :key="opt.value" :value="opt.value">
            {{ opt.label }}
          </a-radio>
        </a-radio-group>
      </a-form-item>
      <a-form-item label="接入密码" help="留空表示不修改；修改后设备需重新注册">
        <a-input-password v-model="form.password" placeholder="不修改请留空" allow-clear />
      </a-form-item>
      <a-form-item label="协议版本">
        <a-select v-model="form.protocolVersion" allow-create :options="protocolOptions" />
      </a-form-item>
      <a-form-item label="收流模式">
        <a-radio-group v-model="form.mediaTcpMode" type="button">
          <a-radio :value="0">UDP</a-radio>
          <a-radio :value="1">TCP 被动</a-radio>
        </a-radio-group>
      </a-form-item>
      <a-space size="large">
        <a-form-item label="心跳周期（秒）" field="heartbeatInterval">
          <a-input-number
            v-model="form.heartbeatInterval"
            :min="1"
            :max="86400"
            style="width: 150px"
          />
        </a-form-item>
        <a-form-item label="心跳超时（秒）" field="heartbeatTimeout">
          <a-input-number
            v-model="form.heartbeatTimeout"
            :min="1"
            :max="86400"
            style="width: 150px"
          />
        </a-form-item>
      </a-space>
    </a-form>

    <!-- 对接信息: 复制发给第三方平台/单位用于级联接入（M3-B3） -->
    <template v-if="accessInfo">
      <a-divider>{{ accessInfo.deviceType === 2 ? '级联接入对接信息' : '设备接入对接信息' }}</a-divider>
      <a-descriptions :column="1" size="small" bordered>
        <a-descriptions-item label="服务器编码">{{ accessInfo.serverId }}</a-descriptions-item>
        <a-descriptions-item label="服务器域">{{ accessInfo.domain }}</a-descriptions-item>
        <a-descriptions-item label="服务器 IP">{{ serverIp }}</a-descriptions-item>
        <a-descriptions-item label="SIP 端口">{{ accessInfo.sipPort }}</a-descriptions-item>
        <a-descriptions-item label="本设备编码">{{ accessInfo.deviceId }}</a-descriptions-item>
      </a-descriptions>
      <a-space class="copy-bar">
        <a-button size="small" @click="copyAccessInfo(false)">
          <template #icon><icon-copy /></template>
          复制服务器信息
        </a-button>
        <a-button type="primary" size="small" @click="copyAccessInfo(true)">
          <template #icon><icon-copy /></template>
          复制完整对接信息
        </a-button>
      </a-space>
      <div class="copy-tip">可直接粘贴发给第三方平台/设备安装方用于级联接入</div>
    </template>
  </a-drawer>
</template>

<style scoped lang="less">
  .dlg-title {
    display: inline-flex;
    align-items: center;
    gap: 8px;
  }

  .copy-bar {
    margin-top: 12px;
  }

  .copy-tip {
    margin-top: 8px;
    font-size: 12px;
    color: var(--color-text-3);
  }
</style>
