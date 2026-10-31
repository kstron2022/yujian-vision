<script setup lang="ts">
  import { reactive, ref, watch } from 'vue';
  import { Message } from '@arco-design/web-vue';
  import type { FormInstance } from '@arco-design/web-vue';
  import { deviceApi } from '@/api/device';
  import type { GbDevice } from '@/api/device';

  /**
   * ONVIF 设备参数编辑（右侧抽屉; 与 GB EditDrawer 分开, 避免 GB 表单混入协议分支）。
   * 密码留空=不修改; 地址/账密变更后建议手动「刷新目录」（列表行按钮复用）。
   */
  const props = defineProps<{ visible: boolean; device: GbDevice | null }>();
  const emit = defineEmits<{
    (e: 'update:visible', v: boolean): void;
    (e: 'saved'): void;
  }>();

  const formRef = ref<FormInstance>();
  const saving = ref(false);
  const testing = ref(false);
  const resyncing = ref(false);
  const form = reactive({
    name: '',
    ip: '',
    port: 80,
    username: '',
    password: '',
    probeInterval: 60,
    probeTimeout: 3
  });

  const rules = {
    name: [{ required: true, message: '请输入设备名称' }],
    ip: [{ required: true, message: '请输入设备 IP' }]
  };

  watch(
    () => props.visible,
    (v) => {
      if (!v || !props.device) return;
      form.name = props.device.name || '';
      form.ip = props.device.ip || '';
      form.port = props.device.port || 80;
      form.username = props.device.onvifUsername || '';
      form.password = '';
      form.probeInterval = props.device.heartbeatInterval ?? 60;
      form.probeTimeout = props.device.heartbeatTimeout ?? 3;
    }
  );

  /** 测试连接：用表单当前地址+原账密（密码留空时不重传新密码） */
  async function testConnection() {
    if (!props.device) return;
    testing.value = true;
    try {
      const r = await deviceApi.onvifTestConnection({
        deviceId: props.device.deviceId,
        ip: form.ip.trim(),
        port: form.port,
        username: form.username,
        password: form.password || undefined
      });
      const d = r as any;
      if (d?.manufacturer) {
        Message.success(`连接成功: ${d.manufacturer} ${d.model || ''} ${d.firmware || ''}`);
      } else {
        Message.success('连接成功');
      }
    } catch (e: any) {
      Message.error(e?.message || '连接失败');
    } finally {
      testing.value = false;
    }
  }

  /** 重新同步目录：复用按协议分流的目录刷新接口 */
  async function resyncCatalog() {
    if (!props.device) return;
    resyncing.value = true;
    try {
      await deviceApi.refreshCatalog(props.device.deviceId);
      Message.success('目录同步已触发');
      emit('saved');
    } catch (e: any) {
      Message.error(e?.message || '目录同步失败');
    } finally {
      resyncing.value = false;
    }
  }

  async function save() {
    const err = await formRef.value?.validate();
    if (err) return;
    if (!props.device) return;
    saving.value = true;
    try {
      await deviceApi.updateOnvif(props.device.deviceId, {
        name: form.name.trim(),
        ip: form.ip.trim(),
        port: form.port,
        username: form.username,
        // 密码留空=不修改
        password: form.password || undefined,
        probeInterval: form.probeInterval,
        probeTimeout: form.probeTimeout
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
        编辑 ONVIF 设备
      </span>
    </template>

    <a-form ref="formRef" :model="form" :rules="rules" layout="vertical">
      <a-form-item label="设备名称" field="name">
        <a-input v-model="form.name" allow-clear :max-length="64" />
      </a-form-item>
      <a-space size="large">
        <a-form-item label="设备 IP" field="ip">
          <a-input v-model="form.ip" allow-clear style="width: 220px" />
        </a-form-item>
        <a-form-item label="端口" field="port">
          <a-input-number v-model="form.port" :min="1" :max="65535" style="width: 110px" />
        </a-form-item>
      </a-space>
      <a-space size="large">
        <a-form-item label="ONVIF 用户名" field="username" help="留空表示不修改">
          <a-input v-model="form.username" placeholder="不修改请留空" allow-clear style="width: 220px" />
        </a-form-item>
        <a-form-item label="ONVIF 密码" help="留空表示不修改">
          <a-input-password v-model="form.password" placeholder="不修改请留空" allow-clear style="width: 220px" />
        </a-form-item>
      </a-space>
      <a-space size="large">
        <a-form-item label="探活周期（秒）" field="probeInterval" help="连续失败达到判死次数后标记离线">
          <a-input-number v-model="form.probeInterval" :min="10" :max="3600" style="width: 150px" />
        </a-form-item>
        <a-form-item label="判死次数" field="probeTimeout">
          <a-input-number v-model="form.probeTimeout" :min="1" :max="10" style="width: 110px" />
        </a-form-item>
      </a-space>
    </a-form>
    <template #footer>
      <div class="drawer-footer">
        <a-space>
          <a-button :loading="testing" @click="testConnection">
            <icon-thunderbolt /> 测试连接
          </a-button>
          <a-button :loading="resyncing" @click="resyncCatalog">
            <icon-sync /> 重新同步目录
          </a-button>
        </a-space>
        <a-space>
          <a-button @click="emit('update:visible', false)">取消</a-button>
          <a-button type="primary" :loading="saving" @click="save">保存</a-button>
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

  .drawer-footer {
    display: flex;
    justify-content: space-between;
    align-items: center;
    width: 100%;
  }
</style>
