<script setup lang="ts">
  import { computed, onMounted, onUnmounted, ref } from 'vue';
  import { metricsApi } from '@/api/metrics';
  import type { MetricsNode, MetricsOverview } from '@/api/metrics';
  import { fmtDateTime } from '@/utils/format';

  /**
   * B4 监控概览（docs/09 M2 #8）: 平台内存快照直出 —— 会话四态/设备在线/
   * 流媒体节点端口与带宽。平台不落库, 历史曲线与告警阈值见 Grafana（旁路部署）。
   * 页面每 10 秒轮询一次; HTTP 指标不放（Grafana 看）。
   */
  const REFRESH_MS = 10_000;

  const loading = ref(false);
  const snap = ref<MetricsOverview>(emptySnap());
  let timer: number | undefined;

  function emptySnap(): MetricsOverview {
    return {
      collectTime: null,
      sessions: { INIT: 0, INVITING: 0, STREAMING: 0, CLOSING: 0 },
      devices: { online: 0, total: 0 },
      nodes: []
    };
  }

  /** 后端已兜底空结构, 这里仅防 null/缺键（首轮采集未跑时 collectTime=null 全 0 渲染） */
  function normalize(d?: MetricsOverview | null): MetricsOverview {
    if (!d) return emptySnap();
    return {
      collectTime: d.collectTime ?? null,
      sessions: {
        INIT: d.sessions?.INIT ?? 0,
        INVITING: d.sessions?.INVITING ?? 0,
        STREAMING: d.sessions?.STREAMING ?? 0,
        CLOSING: d.sessions?.CLOSING ?? 0
      },
      devices: { online: d.devices?.online ?? 0, total: d.devices?.total ?? 0 },
      nodes: Array.isArray(d.nodes) ? d.nodes : []
    };
  }

  const sessions = computed(() => snap.value.sessions);
  const sessionTotal = computed(
    () =>
      sessions.value.INIT +
      sessions.value.INVITING +
      sessions.value.STREAMING +
      sessions.value.CLOSING
  );
  const devices = computed(() => snap.value.devices);
  const onlinePercent = computed(() =>
    devices.value.total > 0 ? devices.value.online / devices.value.total : 0
  );

  /** 部署侧可经 index.html 内联脚本注入 window.__GRAFANA_URL__, 未注入则纯文案（不 hardcode 地址） */
  const grafanaUrl = String(
    (window as unknown as Record<string, unknown>).__GRAFANA_URL__ ?? ''
  );

  /** 状态归一: 'online'（大小写不敏感）或数字 1 视为在线; 其余（offline/未知）按离线呈灰 */
  function isOnline(v?: string | number | null): boolean {
    if (v === 1) return true;
    return String(v ?? '')
      .trim()
      .toLowerCase()
      .endsWith('online');
  }

  /** bytes/s → Mbps（bytes*8/1e6）; 按量级取小数位, 0 值直显 0 */
  function fmtMbps(bytesPerSec: number): string {
    const mbps = (bytesPerSec * 8) / 1e6;
    if (!Number.isFinite(mbps) || mbps <= 0) return '0 Mbps';
    if (mbps < 0.01) return '<0.01 Mbps';
    return `${mbps.toFixed(mbps >= 100 ? 0 : mbps >= 10 ? 1 : 2)} Mbps`;
  }

  /** 端口占用: used 由 ratio×capacity 反推（后端只平铺 ratio/capacity） */
  function portUsed(n: MetricsNode): number {
    return Math.min(n.capacity, Math.round(n.portRatio * n.capacity));
  }

  /** 端口占用百分比文案（ratio 截到 0~1, 一位小数） */
  function portPercent(n: MetricsNode): string {
    const ratio = Math.min(1, Math.max(0, n.portRatio));
    return `${(ratio * 100).toFixed(1)}%`;
  }

  async function load(silent = false) {
    if (!silent) loading.value = true;
    try {
      snap.value = normalize(await metricsApi.overview());
    } finally {
      loading.value = false;
    }
  }

  onMounted(() => {
    void load();
    timer = window.setInterval(() => void load(true), REFRESH_MS);
  });
  onUnmounted(() => window.clearInterval(timer));
</script>

<template>
  <div class="general-card-wrap page-ops-overview">
    <!-- 顶部统计: 活跃会话 + 设备在线（HTTP 指标不放, Grafana 看） -->
    <a-row :gutter="12">
      <a-col :span="12">
        <a-card :bordered="false" class="general-card stat-card">
          <div class="stat-label"><span class="dlg-title"><icon-thunderbolt /> 活跃会话</span></div>
          <div class="stat-value">{{ sessionTotal }}</div>
          <div class="stat-sub">
            初始化 {{ sessions.INIT }} · 应答中 {{ sessions.INVITING }} · 推流中
            {{ sessions.STREAMING }} · 收口中 {{ sessions.CLOSING }}
          </div>
        </a-card>
      </a-col>
      <a-col :span="12">
        <a-card :bordered="false" class="general-card stat-card">
          <div class="stat-label"><span class="dlg-title"><icon-video-camera /> 设备在线</span></div>
          <div class="stat-value">{{ devices.online }} / {{ devices.total }}</div>
          <a-progress
            :percent="onlinePercent"
            size="small"
            :show-text="false"
            class="stat-bar"
          />
        </a-card>
      </a-col>
    </a-row>

    <!-- 流媒体节点 -->
    <a-card :bordered="false" class="general-card node-card">
      <div class="toolbar">
        <span class="dlg-title"><icon-cloud /> 流媒体节点</span>
        <a-space>
          <span class="tip">采集于 {{ fmtDateTime(snap.collectTime) }} · 每 10 秒自动刷新</span>
          <a-button size="small" :loading="loading" @click="load()">
            <template #icon><icon-refresh /></template>
            刷新
          </a-button>
        </a-space>
      </div>

      <a-table
        :data="snap.nodes"
        :loading="loading"
        :pagination="false"
        :scroll="{ x: 1020 }"
        row-key="serverId"
        size="medium"
      >
        <template #columns>
          <a-table-column title="节点" :width="170">
            <template #cell="{ record }">{{ record.serverId || '-' }}</template>
          </a-table-column>
          <a-table-column title="状态" :width="90" align="center">
            <template #cell="{ record }">
              <a-tag size="small" :color="isOnline(record.status) ? 'green' : 'gray'">
                {{ isOnline(record.status) ? '在线' : '离线' }}
              </a-tag>
            </template>
          </a-table-column>
          <a-table-column title="ZLM 可达" :width="100" align="center">
            <template #cell="{ record }">
              <a-tag size="small" :color="record.reachable ? 'green' : 'red'">
                {{ record.reachable ? '可达' : '不可达' }}
              </a-tag>
            </template>
          </a-table-column>
          <a-table-column title="流数" :width="80" align="right">
            <template #cell="{ record }">{{ record.streams ?? 0 }}</template>
          </a-table-column>
          <a-table-column title="端口占用" :width="240">
            <template #cell="{ record }">
              <a-space direction="vertical" :size="2" class="port-cell">
                <a-progress
                  :percent="Math.min(1, Math.max(0, record.portRatio ?? 0))"
                  size="small"
                  :show-text="false"
                />
                <span class="port-text">
                  {{ portUsed(record) }}/{{ record.capacity ?? 0 }}（{{ portPercent(record) }}）
                </span>
              </a-space>
            </template>
          </a-table-column>
          <a-table-column title="观看者" :width="90" align="right">
            <template #cell="{ record }">{{ record.viewers ?? 0 }}</template>
          </a-table-column>
          <a-table-column title="带宽" :width="120" align="right">
            <template #cell="{ record }">{{ fmtMbps(record.bandwidthBytesPerSec ?? 0) }}</template>
          </a-table-column>
          <a-table-column title="SSRC 占用" :width="110" align="right">
            <template #cell="{ record }">{{ record.ssrcAllocated ?? 0 }}</template>
          </a-table-column>
        </template>
      </a-table>
    </a-card>

    <!-- 底部说明: 平台只呈现"当下", 历史与告警在旁路监控栈 -->
    <div class="foot-note">
      <icon-info-circle />
      <span>
        平台仅呈现当下快照；历史曲线与告警阈值由 Prometheus + Grafana 承担
        <a v-if="grafanaUrl" :href="grafanaUrl" target="_blank" rel="noopener">打开 Grafana</a>
      </span>
    </div>
  </div>
</template>

<style scoped lang="less">
  .stat-card {
    margin-bottom: 12px;

    .stat-label {
      margin-bottom: 6px;
      font-size: 13px;
      color: var(--color-text-2);
    }

    .stat-value {
      font-size: 28px;
      font-weight: 600;
      line-height: 34px;
      color: var(--color-text-1);
    }

    .stat-sub {
      margin-top: 4px;
      font-size: 12px;
      color: var(--color-text-3);
    }

    .stat-bar {
      margin-top: 8px;
      max-width: 320px;
    }
  }

  .node-card {
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

    .port-cell {
      width: 100%;
    }

    .port-text {
      font-size: 12px;
      color: var(--color-text-3);
    }
  }

  .foot-note {
    display: flex;
    align-items: center;
    gap: 6px;
    margin-top: 12px;
    font-size: 12px;
    color: var(--color-text-3);

    .arco-icon {
      color: rgb(var(--arcoblue-6));
    }
  }
</style>
