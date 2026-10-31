<script setup lang="ts">
  import { computed, onMounted, reactive, ref } from 'vue';
  import { Message, Modal } from '@arco-design/web-vue';
  import type { FormInstance } from '@arco-design/web-vue';
  import { serverApi } from '@/api/system';
  import type { MediaServer, ServerPayload, ZlmStream } from '@/api/system';
  import { useAuthStore } from '@/stores/auth';
  import { fmtDateTime } from '@/utils/format';

  const auth = useAuthStore();
  const rows = ref<MediaServer[]>([]);
  const loading = ref(false);

  const drawerVisible = ref(false);
  const editing = ref<MediaServer | null>(null);
  const saving = ref(false);
  const formRef = ref<FormInstance>();
  const form = reactive({
    serverId: '',
    hostIp: '',
    publicIp: '',
    httpPort: 80,
    httpSslPort: 443,
    rtspPort: 554,
    rtmpPort: 1935,
    rtcPort: 8000,
    rtpPortStart: 30000,
    rtpPortEnd: 30500,
    hookEnable: 1,
    signSecret: ''
  });

  // 查流抽屉
  const streamsVisible = ref(false);
  const streamsTarget = ref<MediaServer | null>(null);
  const streams = ref<ZlmStream[]>([]);
  const streamsLoading = ref(false);

  const rules = {
    serverId: [{ required: true, message: '请输入节点 ID' }],
    hostIp: [{ required: true, message: '请输入内网 IP' }]
  };

  const statusText = (s: number) => (s === 1 ? '在线' : '离线');
  const total = computed(() => rows.value.length);

  async function load() {
    loading.value = true;
    try {
      rows.value = await serverApi.list();
    } finally {
      loading.value = false;
    }
  }

  function openCreate() {
    editing.value = null;
    Object.assign(form, {
      serverId: '',
      hostIp: '',
      publicIp: '',
      httpPort: 80,
      httpSslPort: 443,
      rtspPort: 554,
      rtmpPort: 1935,
      rtcPort: 8000,
      rtpPortStart: 30000,
      rtpPortEnd: 30500,
      hookEnable: 1,
      signSecret: ''
    });
    drawerVisible.value = true;
  }

  function openEdit(row: MediaServer) {
    editing.value = row;
    Object.assign(form, {
      serverId: row.serverId,
      hostIp: row.hostIp,
      publicIp: row.publicIp || '',
      httpPort: row.httpPort,
      httpSslPort: row.httpSslPort,
      rtspPort: row.rtspPort,
      rtmpPort: row.rtmpPort,
      rtcPort: row.rtcPort,
      rtpPortStart: row.rtpPortStart,
      rtpPortEnd: row.rtpPortEnd,
      hookEnable: row.hookEnable,
      signSecret: ''
    });
    drawerVisible.value = true;
  }

  function payload(): Partial<ServerPayload> {
    const p: Partial<ServerPayload> = {
      hostIp: form.hostIp,
      publicIp: form.publicIp || undefined,
      httpPort: form.httpPort,
      httpSslPort: form.httpSslPort,
      rtspPort: form.rtspPort,
      rtmpPort: form.rtmpPort,
      rtcPort: form.rtcPort,
      rtpPortStart: form.rtpPortStart,
      rtpPortEnd: form.rtpPortEnd,
      hookEnable: form.hookEnable
    };
    // 密钥留空=不修改(后端 patch 语义)
    if (form.signSecret) p.signSecret = form.signSecret;
    if (!editing.value) p.serverId = form.serverId;
    return p;
  }

  async function save() {
    const err = await formRef.value?.validate();
    if (err) return;
    saving.value = true;
    try {
      if (editing.value) {
        await serverApi.update(editing.value.serverId, payload());
        Message.success('节点已更新');
      } else {
        await serverApi.create(payload() as ServerPayload);
        Message.success('节点已新增');
      }
      drawerVisible.value = false;
      await load();
    } finally {
      saving.value = false;
    }
  }

  function confirmRemove(row: MediaServer) {
    Modal.confirm({
      title: '删除节点',
      content: `确认删除流媒体节点「${row.serverId}」？节点上的播放会话将不受管理。`,
      okText: '删除',
      cancelText: '取消',
      okButtonProps: { status: 'danger' },
      onOk: async () => {
        await serverApi.remove(row.serverId);
        Message.success('删除成功');
        await load();
      }
    });
  }

  async function openStreams(row: MediaServer) {
    streamsTarget.value = row;
    streams.value = [];
    streamsVisible.value = true;
    streamsLoading.value = true;
    try {
      const res = await serverApi.streams(row.serverId);
      // ZLM 同一流会在 rtsp/rtmp/hls/ts/fmp4 等多 schema 下各出一行, 且 totalReaderCount
      // 在各行重复计同一播放端(与 B4 监控同源口径: 每流取 MAX 防重复计数) → 按流聚合一行
      const byStream = new Map<string, ZlmStream & { _key: string }>();
      (res?.data ?? []).forEach((s) => {
        const key = `${s.app}/${s.stream}`;
        const prev = byStream.get(key);
        if (!prev) {
          byStream.set(key, { ...s, _key: key });
          return;
        }
        const a = prev.readers ?? prev.totalReaderCount ?? 0;
        const b = s.readers ?? s.totalReaderCount ?? 0;
        if (b > a) {
          prev.readers = s.readers;
          prev.totalReaderCount = s.totalReaderCount;
        }
        prev.schema = `${prev.schema},${s.schema}`;
      });
      streams.value = [...byStream.values()];
    } catch {
      streams.value = [];
    } finally {
      streamsLoading.value = false;
    }
  }

  onMounted(() => void load());
</script>

<template>
  <div class="general-card-wrap">
    <a-card :bordered="false" class="general-card">
      <div class="toolbar">
        <span class="tip">共 {{ total }} 个节点；调度器按节点健康与端口余量分配收流端口</span>
        <a-space :size="8">
          <a-button size="small" @click="() => load()">
            <template #icon><icon-refresh /></template>
            刷新
          </a-button>
          <a-button v-if="auth.hasPerm('server:add')" type="primary" size="small" @click="openCreate">
            <template #icon><icon-plus /></template>
            新增节点
          </a-button>
        </a-space>
      </div>

      <a-table
        :data="rows"
        :loading="loading"
        :pagination="false"
        :scroll="{ x: 1200 }"
        row-key="id"
        size="medium"
      >
        <template #columns>
          <a-table-column title="节点 ID" :min-width="150" ellipsis tooltip>
            <template #cell="{ record }">{{ record.serverId }}</template>
          </a-table-column>
          <a-table-column title="内网地址" :width="170">
            <template #cell="{ record }">{{ record.hostIp }}:{{ record.httpPort }}</template>
          </a-table-column>
          <a-table-column title="公网 IP" :width="140">
            <template #cell="{ record }">{{ record.publicIp || '-' }}</template>
          </a-table-column>
          <a-table-column title="RTP 端口段" :width="130">
            <template #cell="{ record }">{{ record.rtpPortStart }}-{{ record.rtpPortEnd }}</template>
          </a-table-column>
          <a-table-column title="状态" :width="90" align="center">
            <template #cell="{ record }">
              <a-tag size="small" :color="record.status === 1 ? 'green' : 'gray'">
                {{ statusText(record.status) }}
              </a-tag>
            </template>
          </a-table-column>
          <a-table-column title="版本" :min-width="110" ellipsis tooltip>
            <template #cell="{ record }">{{ record.zlmVersion || '-' }}</template>
          </a-table-column>
          <a-table-column title="流数" :width="80" align="center">
            <template #cell="{ record }">{{ record.streamCount ?? 0 }}</template>
          </a-table-column>
          <a-table-column title="接入时间" :min-width="160" ellipsis tooltip>
            <template #cell="{ record }">{{ fmtDateTime(record.createTime) }}</template>
          </a-table-column>
          <a-table-column title="操作" :width="170" fixed="right">
            <template #cell="{ record }">
              <a-space :size="2">
                <a-button type="text" size="mini" @click="openStreams(record)">查流</a-button>
                <a-button
                  v-if="auth.hasPerm('server:update')"
                  type="text"
                  size="mini"
                  @click="openEdit(record)"
                >
                  编辑
                </a-button>
                <a-button
                  v-if="auth.hasPerm('server:delete')"
                  type="text"
                  size="mini"
                  status="danger"
                  @click="confirmRemove(record)"
                >
                  删除
                </a-button>
              </a-space>
            </template>
          </a-table-column>
        </template>
      </a-table>
    </a-card>

    <a-drawer
      :visible="drawerVisible"
      :width="640"
      unmount-on-close
      :ok-loading="saving"
      ok-text="保存"
      cancel-text="取消"
      @ok="save"
      @cancel="drawerVisible = false"
      @close="drawerVisible = false"
    >
      <template #title>
        <span class="dlg-title">
          <icon-cloud />
          {{ editing ? '编辑节点' : '新增节点' }}
        </span>
      </template>

      <a-form ref="formRef" :model="form" :rules="rules" layout="vertical">
        <a-form-item label="节点 ID" field="serverId" help="创建后不可修改；对应 ZLM 的 admin_params 与 hook 配置">
          <a-input v-model="form.serverId" :disabled="!!editing" placeholder="如 vision-zlm" allow-clear />
        </a-form-item>
        <div class="form-grid">
          <a-form-item label="内网 IP" field="hostIp">
            <a-input v-model="form.hostIp" placeholder="平台访问 ZLM 的地址" allow-clear />
          </a-form-item>
          <a-form-item label="公网 IP" help="级联/外网播放时下发">
            <a-input v-model="form.publicIp" allow-clear />
          </a-form-item>
          <a-form-item label="HTTP 端口">
            <a-input-number v-model="form.httpPort" :min="1" :max="65535" hide-button />
          </a-form-item>
          <a-form-item label="HTTPS 端口">
            <a-input-number v-model="form.httpSslPort" :min="1" :max="65535" hide-button />
          </a-form-item>
          <a-form-item label="RTSP 端口">
            <a-input-number v-model="form.rtspPort" :min="1" :max="65535" hide-button />
          </a-form-item>
          <a-form-item label="RTMP 端口">
            <a-input-number v-model="form.rtmpPort" :min="1" :max="65535" hide-button />
          </a-form-item>
          <a-form-item label="RTC 端口">
            <a-input-number v-model="form.rtcPort" :min="1" :max="65535" hide-button />
          </a-form-item>
          <a-form-item label="Hook 开关">
            <a-radio-group v-model="form.hookEnable" type="button">
              <a-radio :value="1">开启</a-radio>
              <a-radio :value="0">关闭</a-radio>
            </a-radio-group>
          </a-form-item>
          <a-form-item label="RTP 起始端口">
            <a-input-number v-model="form.rtpPortStart" :min="1000" :max="65535" hide-button />
          </a-form-item>
          <a-form-item label="RTP 结束端口">
            <a-input-number v-model="form.rtpPortEnd" :min="1000" :max="65535" hide-button />
          </a-form-item>
        </div>
        <a-form-item label="访问密钥" help="留空表示不修改；与 ZLM admin_params/签名一致">
          <a-input-password v-model="form.signSecret" :placeholder="editing ? '不修改请留空' : '节点密钥'" allow-clear />
        </a-form-item>
      </a-form>
    </a-drawer>

    <a-drawer
      :visible="streamsVisible"
      :width="920"
      unmount-on-close
      hide-cancel
      ok-text="关 闭"
      @ok="streamsVisible = false"
      @cancel="streamsVisible = false"
      @close="streamsVisible = false"
    >
      <template #title>
        <span class="dlg-title">
          <icon-live />
          在线流 · {{ streamsTarget?.serverId }}
        </span>
      </template>
      <a-spin :loading="streamsLoading" style="width: 100%">
        <div class="streams-refresh">
          <a-button size="mini" @click="() => streamsTarget && openStreams(streamsTarget)">
            <template #icon><icon-refresh /></template>
            刷新
          </a-button>
        </div>
        <a-table :data="streams" :pagination="false" row-key="_key" size="small">
          <template #empty>
            <a-empty description="无在线流" />
          </template>
          <template #columns>
            <a-table-column title="应用" data-index="app" :width="110" />
            <a-table-column title="协议" data-index="schema" :width="180" />
            <a-table-column title="流 ID" data-index="stream" :width="320" ellipsis tooltip />
            <a-table-column title="观看数" :width="90" align="center">
              <template #cell="{ record }">{{ record.readers ?? record.totalReaderCount ?? 0 }}</template>
            </a-table-column>
            <a-table-column title="产生时间" :width="170">
              <template #cell="{ record }">{{
                fmtDateTime(record.createTime ?? (record.createStamp ? record.createStamp * 1000 : undefined))
              }}</template>
            </a-table-column>
          </template>
        </a-table>
      </a-spin>
    </a-drawer>
  </div>
</template>

<style scoped lang="less">
  .toolbar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 12px;

    .tip {
      font-size: 12px;
      color: var(--color-text-3);
    }
  }

  .streams-refresh {
    display: flex;
    justify-content: flex-end;
    margin-bottom: 8px;
  }

  .form-grid {
    display: grid;
    grid-template-columns: 1fr 1fr;
    column-gap: 16px;
  }
</style>
