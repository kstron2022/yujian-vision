<script setup lang="ts">
  import { computed, reactive, ref, watch } from 'vue';
  import { Message } from '@arco-design/web-vue';
  import type { FormInstance } from '@arco-design/web-vue';
  import { deviceApi } from '@/api/device';
  import type { GbAccessConfig } from '@/api/device';
  import { formatProtocolVersion } from '@/utils/format';
  import { copyText } from '@/utils/clipboard';

  /** 设备预建档(右侧抽屉, M3-B3: 下级平台/设备先建档配接入密码, 对方注册时 Digest 校验) */
  const props = defineProps<{ visible: boolean }>();
  const emit = defineEmits<{
    (e: 'update:visible', v: boolean): void;
    (e: 'saved'): void;
  }>();

  const formRef = ref<FormInstance>();
  const saving = ref(false);
  /** 保存成功后的结果态: 展示对接信息卡(复制发给接入方), 点完成才关闭 */
  const saved = ref(false);
  const form = reactive({
    deviceId: '',
    name: '',
    deviceType: 1,
    password: '',
    protocolVersion: '2016',
    mediaTcpMode: 1
  });

  const rules = {
    deviceId: [
      { required: true, message: '请输入国标编码' },
      {
        validator: (v: string, cb: (msg?: string) => void) =>
          cb(/^\d{20}$/.test(v) ? undefined : '须为 20 位数字的国标编码')
      }
    ],
    password: [{ required: true, message: '请输入接入密码' }]
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

  // ---- 国标编码默认值: 平台域(10位) + 类型段(设备132/平台200) + 7 位随机序号 ----
  // 域取自接入配置(打开抽屉者必有 device:update); 取不到回退内置默认域
  const DOMAIN_FALLBACK = '3402000000';
  const PORT_FALLBACK = 5060;
  let domain = DOMAIN_FALLBACK;
  let lastGenerated = '';
  /** 接入配置缓存(结果态对接信息卡展示服务器四件套用) */
  const cfg = ref<GbAccessConfig | null>(null);
  /** 服务器 IP 展示值: 后端未配宣告/具体监听地址（0.0.0.0 全网卡监听）时回落当前访问主机名 */
  const serverIp = computed(() => cfg.value?.sipIp || window.location.hostname);

  function genDeviceId(deviceType: number): string {
    const seg = deviceType === 2 ? '200' : '132';
    let serial = '';
    for (let i = 0; i < 7; i += 1) {
      serial += Math.floor(Math.random() * 10);
    }
    return `${domain}${seg}${serial}`;
  }

  async function loadDomain() {
    try {
      const data = await deviceApi.accessConfig();
      cfg.value = data;
      if (data.domain && /^\d{6,20}$/.test(data.domain)) {
        domain = data.domain;
        // 用户尚未手改编码时同步刷新默认值
        if (form.deviceId === lastGenerated) {
          form.deviceId = genDeviceId(form.deviceType);
          lastGenerated = form.deviceId;
        }
      }
    } catch {
      // 保持回退域, 不打断建档流程
    }
  }

  /** 每次打开重置: 编码给默认值供参考修改(类型切换时未手改则按类型段重生成) */
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
      if (!v) return;
      saved.value = false;
      form.deviceType = 1;
      form.deviceId = genDeviceId(1);
      lastGenerated = form.deviceId;
      form.name = '';
      form.password = '';
      form.protocolVersion = '2016';
      form.mediaTcpMode = 1;
      void loadDomain();
    }
  );

  watch(
    () => form.deviceType,
    (t) => {
      if (form.deviceId === lastGenerated) {
        form.deviceId = genDeviceId(t);
        lastGenerated = form.deviceId;
      }
    }
  );

  async function save() {
    const err = await formRef.value?.validate();
    if (err) return;
    saving.value = true;
    try {
      await deviceApi.create({
        deviceId: form.deviceId.trim(),
        // 名称留空不传, 后端用编码兜底
        name: form.name.trim() || undefined,
        password: form.password,
        deviceType: form.deviceType,
        protocolVersion: form.protocolVersion,
        mediaTcpMode: form.mediaTcpMode
      });
      Message.success('预建档完成，等待设备/下级平台注册');
      saved.value = true;
      emit('saved');
    } finally {
      saving.value = false;
    }
  }

  /** 结果态: 复制完整对接信息(服务器四件套+设备编码/密码), 直接发给接入方 */
  async function copyHandoff() {
    const text = [
      '【GB28181 接入信息】',
      `服务器编码：${cfg.value?.serverId || '34020000002000000001'}`,
      `服务器域：${cfg.value?.domain || domain}`,
      `服务器IP：${serverIp.value}`,
      `SIP端口：${cfg.value?.sipPort || PORT_FALLBACK}`,
      `信令传输：${cfg.value?.transports || 'UDP'}`,
      `设备编码：${form.deviceId.trim()}`,
      `接入密码：（请自行补充）`
    ].join('\n');
    const ok = await copyText(text);
    if (ok) {
      Message.success('对接信息已复制（接入密码请自行补充）');
    } else {
      Message.error('复制失败，请手动选择文本复制');
    }
  }
</script>

<template>
  <a-drawer
    :visible="visible"
    :width="750"
    unmount-on-close
    :ok-loading="saving"
    :ok-text="saved ? '完成' : '保存'"
    :hide-cancel="saved"
    @ok="saved ? emit('update:visible', false) : save()"
    @cancel="emit('update:visible', false)"
    @close="emit('update:visible', false)"
  >
    <template #title>
      <span class="dlg-title">
        <icon-plus />
        {{ saved ? '预建档成功' : '设备预建档' }}
      </span>
    </template>

    <!-- 结果态: 对接信息卡, 一键复制发给接入方 -->
    <template v-if="saved">
      <a-alert type="success" class="cascade-tip">
        已建档。请将以下对接信息发给对方（设备安装方/下级平台管理员），配置后即可注册接入。
      </a-alert>
      <a-descriptions :column="1" size="small" bordered>
        <a-descriptions-item label="服务器编码">{{ cfg?.serverId || '34020000002000000001' }}</a-descriptions-item>
        <a-descriptions-item label="服务器域">{{ cfg?.domain || domain }}</a-descriptions-item>
        <a-descriptions-item label="服务器 IP">{{ serverIp }}</a-descriptions-item>
        <a-descriptions-item label="SIP 端口">{{ cfg?.sipPort || PORT_FALLBACK }}</a-descriptions-item>
        <a-descriptions-item label="信令传输">{{ cfg?.transports || 'UDP' }}</a-descriptions-item>
        <a-descriptions-item label="设备编码">{{ form.deviceId.trim() }}</a-descriptions-item>
      </a-descriptions>
      <a-space class="copy-bar">
        <a-button type="primary" @click="copyHandoff">
          <template #icon><icon-copy /></template>
          复制完整对接信息
        </a-button>
      </a-space>
      <div class="copy-tip">接入密码未包含在复制内容中，请自行补充后单独告知对方；设备注册成功后将自动同步其目录</div>
    </template>

    <a-form v-else ref="formRef" :model="form" :rules="rules" layout="vertical">
      <a-alert v-if="form.deviceType === 2" class="cascade-tip" type="info">
        下级平台：先建档配置接入密码，对方平台按设备流程注册后自动同步其目录
      </a-alert>
      <a-form-item
        label="国标编码"
        field="deviceId"
        help="默认值按平台域+类型段+随机序号生成，可直接修改为分配的编码"
      >
        <a-input
          v-model="form.deviceId"
          placeholder="20 位数字国标编码；下级平台编码通常为 200 段"
          allow-clear
          :max-length="20"
        />
      </a-form-item>
      <a-form-item label="设备名称" help="选填，留空后端用编码兜底">
        <a-input v-model="form.name" placeholder="设备显示名称" allow-clear :max-length="64" />
      </a-form-item>
      <a-form-item label="设备类型">
        <a-radio-group v-model="form.deviceType" type="button">
          <a-radio v-for="opt in deviceTypeOptions" :key="opt.value" :value="opt.value">
            {{ opt.label }}
          </a-radio>
        </a-radio-group>
      </a-form-item>
      <a-form-item
        label="接入密码"
        field="password"
        help="生产必须预配置强密码；修改后设备需重新注册"
      >
        <a-input-password
          v-model="form.password"
          placeholder="设备/下级平台注册时使用的密码"
          allow-clear
        />
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
    </a-form>
  </a-drawer>
</template>

<style scoped lang="less">
  .dlg-title {
    display: inline-flex;
    align-items: center;
    gap: 8px;
  }

  .cascade-tip {
    margin-bottom: 16px;
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
