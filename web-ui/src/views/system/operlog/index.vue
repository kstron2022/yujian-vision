<script setup lang="ts">
  import { computed, onMounted, reactive, ref } from 'vue';
  import { logApi } from '@/api/system';
  import type { OperLog } from '@/api/system';
  import { fmtDateTime } from '@/utils/format';

  const rows = ref<OperLog[]>([]);
  const total = ref(0);
  const loading = ref(false);
  const query = reactive({
    module: '',
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
      const data = await logApi.oper({
        module: query.module || undefined,
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

  // 首屏加载: 此前 load 仅有工具栏「刷新」/搜索/翻页三个触发口, 首次进入/浏览器刷新时
  // 无任何入口发起查询, 表格恒空、必须手动点一次刷新(M2 遗留缺陷)
  onMounted(() => {
    void load();
  });
</script>

<template>
  <div class="general-card-wrap">
    <a-card :bordered="false" class="general-card">
      <div class="toolbar">
        <a-space wrap>
          <a-input-search
            v-model="query.module"
            placeholder="模块（device/play/ptz…）"
            allow-clear
            style="width: 200px"
            @search="search"
            @clear="search"
          />
          <a-input-search
            v-model="query.username"
            placeholder="操作人"
            allow-clear
            style="width: 150px"
            @search="search"
            @clear="search"
          />
          <a-radio-group v-model="query.result" type="button" size="small" @change="search">
            <a-radio :value="-1">全部</a-radio>
            <a-radio :value="1">成功</a-radio>
            <a-radio :value="0">失败</a-radio>
          </a-radio-group>
        </a-space>
        <a-button size="small" :loading="loading" @click="load()">
          <template #icon><icon-refresh /></template>
          刷新
        </a-button>
      </div>

      <a-table
        :data="rows"
        :loading="loading"
        :pagination="pagination"
        :scroll="{ x: 1310 }"
        row-key="id"
        size="medium"
        @page-change="onPage"
        @page-size-change="onPageSize"
      >
        <template #columns>
          <a-table-column title="时间" :width="170">
            <template #cell="{ record }">{{ fmtDateTime(record.createTime) }}</template>
          </a-table-column>
          <a-table-column title="操作人" :width="110">
            <template #cell="{ record }">{{ record.username }}</template>
          </a-table-column>
          <a-table-column title="模块" :width="90">
            <template #cell="{ record }">
              <a-tag size="small">{{ record.module }}</a-tag>
            </template>
          </a-table-column>
          <a-table-column title="操作" :min-width="130" ellipsis tooltip>
            <template #cell="{ record }">{{ record.operation }}</template>
          </a-table-column>
          <!-- 业务对象: 通道/流 ID 类长文本, 单行省略+hover 全文; min-width 列吸收视口富余,
               避免右侧大片空白压给详情列 -->
          <a-table-column title="业务对象" :min-width="260" ellipsis tooltip>
            <template #cell="{ record }">
              <span v-if="record.bizType" class="biz">
                {{ record.bizType }}<template v-if="record.bizId">#{{ record.bizId }}</template>
              </span>
              <span v-else>-</span>
            </template>
          </a-table-column>
          <a-table-column title="结果" :width="80" align="center">
            <template #cell="{ record }">
              <a-tag size="small" :color="record.result === 1 ? 'green' : 'red'">
                {{ record.result === 1 ? '成功' : '失败' }}
              </a-tag>
            </template>
          </a-table-column>
          <a-table-column title="耗时" :width="90" align="center">
            <template #cell="{ record }">{{ record.costMs != null ? `${record.costMs}ms` : '-' }}</template>
          </a-table-column>
          <a-table-column title="详情" :width="380">
            <template #cell="{ record }">
              <a-tooltip :content="record.detail" position="tl">
                <span class="detail">{{ record.detail || '-' }}</span>
              </a-tooltip>
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
  }

  .biz {
    font-size: 12px;
  }

  .detail {
    display: inline-block;
    max-width: 100%;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-size: 12px;
    color: var(--color-text-3);
    cursor: default;
  }
</style>
