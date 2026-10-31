<script setup lang="ts">
  import { computed, onMounted, onUnmounted, reactive, ref } from 'vue';
  import { Message, Modal } from '@arco-design/web-vue';
  import { onlineApi } from '@/api/system';
  import type { OnlineSession } from '@/api/system';
  import { useAuthStore } from '@/stores/auth';
  import { fmtDateTime } from '@/utils/format';

  const auth = useAuthStore();
  const rows = ref<OnlineSession[]>([]);
  const loading = ref(false);
  const kw = ref('');
  const page = reactive({ current: 1, size: 10 });
  let timer: number | undefined;

  /**
   * 在线会话是 Redis 中的活动令牌(易变状态, 非 DB 持久化; 历史留痕见登录日志),
   * M1 规模下量小 → 检索与分页在前端完成。
   */
  const filtered = computed(() => {
    const k = kw.value.trim().toLowerCase();
    if (!k) return rows.value;
    return rows.value.filter(
      (r) =>
        r.username.toLowerCase().includes(k) ||
        (r.nickname || '').toLowerCase().includes(k) ||
        (r.loginIp || '').includes(k)
    );
  });

  const pagination = computed(() => ({
    total: filtered.value.length,
    current: page.current,
    pageSize: page.size,
    showTotal: true,
    showPageSize: true,
    pageSizeOptions: [10, 20, 50]
  }));

  const paged = computed(() => {
    const start = (page.current - 1) * page.size;
    return filtered.value.slice(start, start + page.size);
  });

  async function load(silent = false) {
    if (!silent) loading.value = true;
    try {
      rows.value = await onlineApi.list();
    } finally {
      loading.value = false;
    }
  }

  function remain(sec: number): string {
    if (sec <= 0) return '已过期';
    if (sec < 3600) return `${Math.ceil(sec / 60)} 分钟`;
    return `${Math.floor(sec / 3600)} 小时 ${Math.ceil((sec % 3600) / 60)} 分`;
  }

  function kick(row: OnlineSession) {
    Modal.confirm({
      title: '强制下线',
      content: `确认将用户「${row.username}」的会话强制下线？`,
      okText: '强制下线',
      cancelText: '取消',
      okButtonProps: { status: 'danger' },
      onOk: async () => {
        await onlineApi.kick(row.tokenId);
        Message.success('已强制下线');
        await load(true);
      }
    });
  }

  onMounted(() => {
    void load();
    timer = window.setInterval(() => void load(true), 30000);
  });
  onUnmounted(() => window.clearInterval(timer));
</script>

<template>
  <div class="general-card-wrap">
    <a-card :bordered="false" class="general-card">
      <div class="toolbar">
        <a-space wrap>
          <a-input-search
            v-model="kw"
            placeholder="用户名 / 昵称 / IP"
            allow-clear
            style="width: 220px"
          />
          <span class="tip">每 30 秒自动刷新；会话为活动令牌(非历史留痕, 历史见登录日志)</span>
        </a-space>
        <a-button size="small" :loading="loading" @click="load()">
          <template #icon><icon-refresh /></template>
          刷新
        </a-button>
      </div>

      <a-table
        :data="paged"
        :loading="loading"
        :pagination="pagination"
        :scroll="{ x: 770 }"
        row-key="tokenId"
        size="medium"
        @page-change="(p: number) => (page.current = p)"
        @page-size-change="(s: number) => ((page.size = s), (page.current = 1))"
      >
        <template #columns>
          <a-table-column title="用户" :width="180">
            <template #cell="{ record }">
              <div>{{ record.nickname || record.username }}</div>
              <div class="sub">{{ record.username }}</div>
            </template>
          </a-table-column>
          <a-table-column title="登录 IP" :width="150">
            <template #cell="{ record }">{{ record.loginIp || '-' }}</template>
          </a-table-column>
          <a-table-column title="登录时间" :width="170">
            <template #cell="{ record }">{{ fmtDateTime(record.loginTime) }}</template>
          </a-table-column>
          <a-table-column title="会话剩余" :width="150">
            <template #cell="{ record }">{{ remain(record.idleRemainSeconds) }}</template>
          </a-table-column>
          <a-table-column title="操作" :width="120" fixed="right">
            <template #cell="{ record }">
              <a-button
                v-if="auth.hasPerm('sys:online:kick')"
                type="text"
                size="mini"
                status="danger"
                @click="kick(record)"
              >
                强制下线
              </a-button>
            </template>
          </a-table-column>
        </template>
      </a-table>
    </a-card>
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

  .sub {
    font-size: 12px;
    color: var(--color-text-3);
    line-height: 18px;
  }
</style>
