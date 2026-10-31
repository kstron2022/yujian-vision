<script setup lang="ts">
  import { reactive, ref, watch } from 'vue';
  import { Message } from '@arco-design/web-vue';
  import type { FormInstance } from '@arco-design/web-vue';
  import { deviceApi } from '@/api/device';
  import type { OnvifTestView, OnvifSourceView, OnvifProfileView, OnvifDiscoveredDevice } from '@/api/device';

  /**
   * ONVIF 设备手填添加（右侧抽屉, 交互规范: 标题=icon+文字, footer 固定底部, docs/13 §6.2）。
   * 流程: 填 IP/端口/账密 → 测试连接（回显设备信息+通道预览）→ 保存（建档即目录同步）。
   * 顶部折叠区=同网段探测（M4-B4）: WS-Discovery 发现已接入标记, 点「填入」回填 IP/端口,
   * 账密仍须手输——发现不等于接入（D1 拍板）。
   */
  const props = defineProps<{ visible: boolean }>();
  const emit = defineEmits<{
    (e: 'update:visible', v: boolean): void;
    (e: 'saved'): void;
  }>();

  const formRef = ref<FormInstance>();
  const saving = ref(false);
  const testing = ref(false);
  const testView = ref<OnvifTestView | null>(null);

  const form = reactive({
    ip: '',
    port: 80,
    username: '',
    password: '',
    name: '',
    probeInterval: 60
  });

  const rules = {
    ip: [{ required: true, message: '请输入设备 IP' }],
    username: [{ required: true, message: '请输入 ONVIF 用户名' }],
    password: [{ required: true, message: '请输入 ONVIF 密码' }]
  };

  /** 通道预览（测试连接后展示; 每路 VideoSource=1 通道, 主/子码流由后端按分辨率定档） */
  const sources = ref<OnvifSourceView[]>([]);

  /** 同网段探测（M4-B4）: 结果列表/进行中/已探测过(区分「未发现」与「还没探」) */
  const probing = ref(false);
  const probed = ref(false);
  const discovered = ref<OnvifDiscoveredDevice[]>([]);
  // 在途竞态防护: 关抽屉 reset 后, 之前在途的 probe 响应不得弹入新抽屉（评审 P3-5）
  let probeSeq = 0;

  async function probe() {
    const seq = ++probeSeq;
    probing.value = true;
    try {
      const list = await deviceApi.onvifDiscoveryProbe();
      if (seq !== probeSeq) return; // 期间已重开抽屉, 丢弃过期响应
      discovered.value = list;
      probed.value = true;
      if (!list.length) {
        Message.info('未发现设备（确认与本平台同网段且设备已开启 WS-Discovery）');
      }
    } catch {
      // 失败提示已由拦截器弹出
    } finally {
      if (seq === probeSeq) {
        probing.value = false;
      }
    }
  }

  /** 填入: 回填地址两项并聚焦账密——账密必须手输, 探测不做认证（发现≠接入） */
  function applyDevice(d: OnvifDiscoveredDevice) {
    form.ip = d.ip;
    form.port = d.port;
    Message.success(`已填入 ${d.ip}:${d.port}, 请输入账号密码`);
    document.querySelector<HTMLInputElement>('#onvif-username')?.focus();
  }

  function codecText(p: OnvifProfileView): string {
    if (!p.encoding) return '';
    const up = p.encoding.toUpperCase();
    if (up.includes('265')) return 'H.265';
    if (up.includes('264')) return 'H.264';
    return up;
  }

  async function testConnection() {
    const err = await formRef.value?.validate();
    if (err) return;
    testing.value = true;
    try {
      const v = await deviceApi.onvifTestConnection({
        ip: form.ip.trim(),
        port: form.port,
        username: form.username,
        password: form.password
      });
      testView.value = v;
      sources.value = v.sources ?? [];
      if (!form.name) {
        form.name = v.model || form.ip;
      }
      Message.success('连接成功');
    } catch {
      // 失败提示已由拦截器弹出
    } finally {
      testing.value = false;
    }
  }

  async function save() {
    const err = await formRef.value?.validate();
    if (err) return;
    saving.value = true;
    try {
      await deviceApi.createOnvif({
        ip: form.ip.trim(),
        port: form.port,
        username: form.username,
        password: form.password,
        name: form.name.trim() || undefined,
        probeInterval: form.probeInterval
      });
      Message.success('ONVIF 设备已添加并完成目录同步');
      emit('update:visible', false);
      emit('saved');
    } finally {
      saving.value = false;
    }
  }

  function reset() {
    form.ip = '';
    form.port = 80;
    form.username = '';
    form.password = '';
    form.name = '';
    form.probeInterval = 60;
    testView.value = null;
    sources.value = [];
    probing.value = false;
    probed.value = false;
    discovered.value = [];
    probeSeq++; // 作废在途探测响应（评审 P3-5）
  }

  watch(
    () => props.visible,
    (v) => {
      if (v) reset();
    }
  );

  function onClose() {
    emit('update:visible', false);
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
    @cancel="onClose"
    @close="onClose"
  >
    <template #title>
      <span class="dlg-title">
        <icon-plus />
        添加ONVIF设备
      </span>
    </template>

    <a-alert v-if="!testView" type="normal" class="flow-tip">
      填写相机 ONVIF 服务地址与独立 ONVIF 账号（相机侧需开启开放型网络视频接口并单独建用户），
      点击「测试连接」拉取设备信息与通道。
    </a-alert>

    <a-collapse :bordered="false" class="discovery-box">
      <a-collapse-item key="probe" header="同网段探测（自动发现局域网 ONVIF 设备）">
        <div class="disc-tip">
          仅探测与平台同网段的设备（组播不跨网段），设备需开启 WS-Discovery。
          探测只获取地址，接入仍需录入账号密码。
        </div>
        <a-button size="small" type="outline" :loading="probing" @click="probe">
          <template #icon><icon-find-replace /></template>
          开始探测
        </a-button>
        <div v-if="discovered.length" class="disc-list">
          <div v-for="(d, i) in discovered" :key="i" class="disc-row">
            <span class="disc-addr">{{ d.ip }}:{{ d.port }}</span>
            <span class="disc-model">{{ d.modelHint || '-' }}</span>
            <a-tag v-if="d.registered" size="small" color="green">已接入</a-tag>
            <a-button v-else size="mini" type="text" @click="applyDevice(d)">填入</a-button>
          </div>
        </div>
        <div v-else-if="probed && !probing" class="disc-empty">未发现设备</div>
      </a-collapse-item>
    </a-collapse>

    <a-form ref="formRef" :model="form" :rules="rules" layout="vertical">
      <a-space size="large" class="ip-row">
        <a-form-item label="设备 IP" field="ip">
          <a-input v-model="form.ip" placeholder="如 10.0.0.201" allow-clear style="width: 220px" />
        </a-form-item>
        <a-form-item label="端口" field="port">
          <a-input-number v-model="form.port" :min="1" :max="65535" style="width: 110px" />
        </a-form-item>
      </a-space>
      <a-space size="large">
        <a-form-item label="ONVIF 用户名" field="username">
          <a-input id="onvif-username" v-model="form.username" allow-clear style="width: 220px" />
        </a-form-item>
        <a-form-item label="ONVIF 密码" field="password">
          <a-input-password v-model="form.password" allow-clear style="width: 220px" />
        </a-form-item>
      </a-space>
      <a-space size="large">
        <a-form-item label="设备名称" field="name">
          <a-input v-model="form.name" placeholder="默认取设备型号" allow-clear style="width: 220px" :max-length="64" />
        </a-form-item>
        <a-form-item label="探活周期（秒）" field="probeInterval">
          <a-input-number v-model="form.probeInterval" :min="10" :max="3600" style="width: 110px" />
        </a-form-item>
      </a-space>
      <a-form-item>
        <a-button type="outline" size="small" :loading="testing" @click="testConnection">
          测试连接
        </a-button>
      </a-form-item>
    </a-form>

    <template v-if="testView">
      <a-divider>设备信息</a-divider>
      <a-descriptions :column="2" size="small" bordered>
        <a-descriptions-item label="厂商">{{ testView.manufacturer || '-' }}</a-descriptions-item>
        <a-descriptions-item label="型号">{{ testView.model || '-' }}</a-descriptions-item>
        <a-descriptions-item label="固件">{{ testView.firmware || '-' }}</a-descriptions-item>
        <a-descriptions-item label="序列号">{{ testView.serialNumber || '-' }}</a-descriptions-item>
      </a-descriptions>

      <a-divider>通道（{{ sources.length }} 路, 全部导入）</a-divider>
      <div v-if="!sources.length" class="empty-tip">设备未返回任何 VideoSource/Profile</div>
      <div v-for="s in sources" :key="s.token" class="source-row">
        <div class="source-name">{{ s.token }}</div>
        <a-space :size="4" wrap>
          <a-tag v-for="p in s.profiles" :key="p.token" size="small" :color="codecText(p) === 'H.265' ? 'purple' : 'arcoblue'">
            {{ p.name || p.token }} · {{ p.width }}x{{ p.height }} · {{ codecText(p) }}{{ p.ptz ? ' · PTZ' : '' }}
          </a-tag>
        </a-space>
      </div>
    </template>
  </a-drawer>
</template>

<style scoped lang="less">
  .dlg-title {
    display: inline-flex;
    align-items: center;
    gap: 8px;
  }

  .flow-tip {
    margin-bottom: 12px;
  }

  .discovery-box {
    margin-bottom: 12px;
    padding: 8px 12px;
    background-color: var(--color-fill-1);
    border-radius: 4px;

    .disc-tip {
      font-size: 12px;
      color: var(--color-text-3);
      margin-bottom: 8px;
    }

    .disc-list {
      margin-top: 10px;
    }

    .disc-row {
      display: flex;
      align-items: center;
      gap: 10px;
      padding: 4px 0;
      border-bottom: 1px dashed var(--color-border-2);

      &:last-child {
        border-bottom: none;
      }

      .disc-addr {
        font-weight: 500;
        min-width: 150px;
      }

      .disc-model {
        flex: 1;
        font-size: 12px;
        color: var(--color-text-3);
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
    }

    .disc-empty {
      margin-top: 10px;
      font-size: 12px;
      color: var(--color-text-3);
    }
  }

  .ip-row {
    flex-wrap: nowrap;
  }

  .empty-tip {
    font-size: 12px;
    color: var(--color-text-3);
  }

  .source-row {
    margin-bottom: 8px;

    .source-name {
      font-size: 12px;
      color: var(--color-text-2);
      margin-bottom: 4px;
    }
  }
</style>
