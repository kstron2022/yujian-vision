<script setup lang="ts">
  import { computed, onMounted, reactive, ref } from 'vue';
  import { Message, Modal } from '@arco-design/web-vue';
  import type { FormInstance } from '@arco-design/web-vue';
  import { deviceApi } from '@/api/device';
  import type { GbAccessConfig } from '@/api/device';
  import { copyText } from '@/utils/clipboard';

  /** 接入配置独立页（M3-B3: 设备侧对接信息复制 + 接入模式切换 + 默认接入密码） */
  const loading = ref(false);
  const saving = ref(false);
  /** 后端未配宣告/具体监听地址时, 服务器 IP 回落当前访问主机名（模板访问不到 window, 取脚本常量） */
  const fallbackHost = window.location.hostname;
  /** 对接信息(来自配置文件, 只读) + 默认密码状态(defaultPasswordSet 决定 placeholder 文案) */
  const config = ref<GbAccessConfig | null>(null);

  const formRef = ref<FormInstance>();
  const form = reactive({ password: '' });

  /** 选填: 填了才校验长度(留空=不修改) */
  const rules = {
    password: [
      {
        validator: (v: string, cb: (msg?: string) => void) =>
          cb(!v || v.length >= 8 ? undefined : '默认接入密码至少 8 位')
      }
    ]
  };

  const passwordPlaceholder = computed(() =>
    config.value?.defaultPasswordSet
      ? '已设置自定义密码（数据库），留空表示不修改'
      : '当前使用配置文件内置默认值，留空表示不修改'
  );

  /** 接入模式两态语义实时说明 */
  const modeDesc = computed(() =>
    config.value?.allowAutoRegister === 1
      ? '开放模式：未知设备可用默认接入密码注册并自动建档（适合演示/内网）'
      : '白名单模式：仅预建档设备可注册，未建档设备注册将被拒绝（生产推荐）'
  );

  /** 失败由 http 拦截器统一弹错, 这里只管 loading */
  async function load() {
    loading.value = true;
    try {
      config.value = await deviceApi.accessConfig();
    } finally {
      loading.value = false;
    }
  }

  /** 一键复制对接信息（设备/NVR/下级平台注册时照抄填写） */
  async function copyInfo() {
    const c = config.value;
    if (!c) return;
    const text = [
      '【GB28181 接入信息】',
      `服务器编码：${c.serverId || '-'}`,
      `服务器域：${c.domain || '-'}`,
      `服务器IP：${c.sipIp || fallbackHost}`,
      `SIP端口：${c.sipPort || '-'}`,
      `信令传输：${c.transports || '-'}`
    ].join('\n');
    const ok = await copyText(text);
    if (ok) Message.success('对接信息已复制');
    else Message.error('复制失败，请手动复制');
  }

  /**
   * 切换接入模式: 二次确认后生效。开关用 :model-value 受控,
   * 确认成功并刷新前界面保持原态, 失败回读后端实际值即自动"回滚"。
   */
  function onModeChange(v: string | number | boolean) {
    const open = v === true || v === 1;
    Modal.confirm({
      title: '切换接入模式',
      content: open
        ? '切换为开放模式：未知设备可用默认接入密码注册并自动建档，适合演示/内网环境。'
        : '切换为白名单模式：未建档设备将立即无法注册，请确保已逐台预建档。',
      okText: '确认切换',
      cancelText: '取消',
      onOk: async () => {
        try {
          await deviceApi.updateAccessConfig({ allowAutoRegister: open ? 1 : 0 });
          Message.success('接入模式已切换');
        } catch {
          // 失败已由拦截器弹错, 这里静默回读回滚
        } finally {
          await load();
        }
      }
    });
  }

  async function savePassword() {
    const err = await formRef.value?.validate();
    if (err) return;
    // 留空=不修改(后端对空密码直接 400), 不发请求
    if (!form.password) {
      Message.info('默认接入密码未修改');
      return;
    }
    saving.value = true;
    try {
      await deviceApi.updateAccessConfig({ defaultPassword: form.password });
      Message.success('默认接入密码已保存');
      form.password = '';
      formRef.value?.clearValidate();
      // 重新加载刷新 placeholder 状态(是否已自定义)
      await load();
    } finally {
      saving.value = false;
    }
  }

  onMounted(() => void load());
</script>

<template>
  <div class="page-access-config">
    <a-spin :loading="loading" style="width: 100%">
      <a-space direction="vertical" fill :size="12">
        <!-- 卡1: 设备侧对接信息(只读, 来自 vision.gb.* 配置) -->
        <a-card :bordered="false" class="general-card">
          <template #title>
            <span class="dlg-title">
              <icon-info-circle />
              设备侧对接信息
            </span>
          </template>
          <template #extra>
            <a-button type="outline" size="small" @click="copyInfo">
              <template #icon><icon-copy /></template>
              复制对接信息
            </a-button>
          </template>
          <a-descriptions :column="1" bordered size="small">
            <a-descriptions-item label="服务器国标编码">
              {{ config?.serverId || '-' }}
            </a-descriptions-item>
            <a-descriptions-item label="国标域">
              {{ config?.domain || '-' }}
            </a-descriptions-item>
            <a-descriptions-item label="SIP 端口">
              {{ config?.sipPort || '-' }}
            <a-descriptions-item label="服务器 IP">
              {{ config?.sipIp || fallbackHost }}
            </a-descriptions-item>
            </a-descriptions-item>
            <a-descriptions-item label="信令传输">
              {{ config?.transports || '-' }}
            </a-descriptions-item>
          </a-descriptions>
          <div class="info-note">
            以上信息来自服务端配置文件（vision.gb.*），设备/NVR/下级平台注册时填写；如需变更请修改 application.yaml 并重启
          </div>
        </a-card>

        <!-- 卡2: 接入模式(开放=未知设备自动建档 / 白名单=仅预建档可注册) -->
        <a-card :bordered="false" class="general-card">
          <template #title>
            <span class="dlg-title">
              <icon-safe />
              接入模式
            </span>
          </template>
          <a-space align="center">
            <a-switch :model-value="config?.allowAutoRegister === 1" @change="onModeChange" />
            <span class="mode-desc">{{ modeDesc }}</span>
          </a-space>
        </a-card>

        <!-- 卡3: 默认接入密码(留空=不修改, 仅开放模式下对新注册未建档设备生效) -->
        <a-card :bordered="false" class="general-card">
          <template #title>
            <span class="dlg-title">
              <icon-lock />
              默认接入密码
            </span>
          </template>
          <a-form ref="formRef" :model="form" :rules="rules" layout="vertical">
            <a-form-item
              label="默认接入密码"
              field="password"
              help="仅开放模式（允许自动注册）下对新注册未建档设备生效；生产建议关闭自动注册、逐台预建档"
            >
              <a-space>
                <a-input-password
                  v-model="form.password"
                  :placeholder="passwordPlaceholder"
                  allow-clear
                  :max-length="64"
                  style="width: 320px"
                />
                <a-button type="primary" :loading="saving" @click="savePassword">保存</a-button>
              </a-space>
            </a-form-item>
          </a-form>
        </a-card>
      </a-space>
    </a-spin>
  </div>
</template>

<style scoped lang="less">
  .dlg-title {
    display: inline-flex;
    align-items: center;
    gap: 8px;
  }

  .info-note {
    margin-top: 8px;
    font-size: 12px;
    color: var(--color-text-3);
  }

  .mode-desc {
    font-size: 13px;
    color: var(--color-text-2);
  }
</style>
