<script setup lang="ts">
  import { computed, onMounted, reactive, ref } from 'vue';
  import { logApi } from '@/api/system';
  import type { LoginLog } from '@/api/system';
  import { fmtDateTime } from '@/utils/format';

  const rows = ref<LoginLog[]>([]);
  const total = ref(0);
  const loading = ref(false);
  const query = reactive({
    username: '',
    result: -1,
    page: 1,
    count: 10
  });

  const pagination = computed(() => ({
    total: total.value,
    current: query.page,
    pageSize: query.count,
    showTotal: true,
    showPageSize: true,
    pageSizeOptions: [10, 20, 50, 100]
  }));

  async function load() {
    loading.value = true;
    try {
      const data = await logApi.login({
        username: query.username || undefined,
        result: query.result === -1 ? undefined : query.result,
        page: query.page,
        count: query.count
      });
      rows.value = data.list;
      total.value = data.total;
    } finally {
      loading.value = false;
    }
  }

  function search() {
    query.page = 1;
    void load();
  }

  function onPage(p: number) {
    query.page = p;
    void load();
  }

  function onPageSize(s: number) {
    query.count = s;
    query.page = 1;
    void load();
  }

  onMounted(() => void load());
</script>

<template>
  <div class="general-card-wrap">
    <a-card :bordered="false" class="general-card">
      <div class="toolbar">
        <a-space wrap>
          <a-input-search
            v-model="query.username"
            placeholder="用户名"
            allow-clear
            style="width: 180px"
            @search="search"
            @clear="search"
          />
          <a-radio-group v-model="query.result" type="button" size="small" @change="search">
            <a-radio :value="-1">全部</a-radio>
            <a-radio :value="1">成功</a-radio>
            <a-radio :value="0">失败</a-radio>
          </a-radio-group>
        </a-space>
        <span class="tip">留存 ≥ 6 个月（等保要求）</span>
      </div>

      <a-table
        :data="rows"
        :loading="loading"
        :pagination="pagination"
        :scroll="{ x: 900 }"
        row-key="id"
        size="medium"
        @page-change="onPage"
        @page-size-change="onPageSize"
      >
        <template #columns>
          <a-table-column title="时间" :width="170">
            <template #cell="{ record }">{{ fmtDateTime(record.createTime) }}</template>
          </a-table-column>
          <a-table-column title="用户名" :width="130">
            <template #cell="{ record }">{{ record.username }}</template>
          </a-table-column>
          <a-table-column title="客户端 IP" :width="140">
            <template #cell="{ record }">{{ record.clientIp || '-' }}</template>
          </a-table-column>
          <a-table-column title="结果" :width="90" align="center">
            <template #cell="{ record }">
              <a-tag size="small" :color="record.result === 1 ? 'green' : 'red'">
                {{ record.result === 1 ? '成功' : '失败' }}
              </a-tag>
            </template>
          </a-table-column>
          <a-table-column title="消息" :width="180">
            <template #cell="{ record }">{{ record.msg || '-' }}</template>
          </a-table-column>
          <a-table-column title="User-Agent">
            <template #cell="{ record }">
              <span class="ua">{{ record.userAgent || '-' }}</span>
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

  .ua {
    font-size: 12px;
    color: var(--color-text-3);
  }
</style>
