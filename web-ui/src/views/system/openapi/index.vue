<script setup lang="ts">
  import { computed, onMounted, reactive, ref } from 'vue';
  import { Message, Modal } from '@arco-design/web-vue';
  import { openApiAppApi } from '@/api/openapi';
  import type { OpenApp, OpenApiLog, OpenAppCredential } from '@/api/openapi';
  import { groupApi } from '@/api/group';
  import type { GroupNode } from '@/api/group';
  import { useAuthStore } from '@/stores/auth';
  import { fmtDateTime } from '@/utils/format';

  const activeTab = ref('apps');
  const rows = ref<OpenApp[]>([]);
  const total = ref(0);
  const loading = ref(false);
  const query = reactive({ appName: '', status: -1, page: 1, count: 10 });

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
      const data = await openApiAppApi.page({
        appName: query.appName || undefined,
        status: query.status === -1 ? undefined : query.status,
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

  // ---- secret 一次性展示（创建/重置后弹出, 关闭前二次确认） ----
  const credVisible = ref(false);
  const credTitle = ref('');
  const cred = ref<OpenAppCredential | null>(null);

  function showCredential(c: OpenAppCredential, mode: 'create' | 'reset') {
    cred.value = c;
    credTitle.value = mode === 'create' ? '应用创建成功' : 'secret 已重置';
    credVisible.value = true;
  }

  async function copyText(text: string, tip: string) {
    try {
      await navigator.clipboard.writeText(text);
      Message.success(tip);
    } catch {
      Message.warning('复制失败, 请手动选择复制');
    }
  }

  function closeCredential(done: () => void) {
    Modal.confirm({
      title: '确认已保存凭证?',
      content: 'appSecret 仅此一次展示, 关闭后平台不再显示, 只能重置获取新 secret。',
      okText: '已保存, 关闭',
      cancelText: '返回查看',
      onOk: () => {
        done();
        load();
      }
    });
  }

  // ---- 新建/编辑抽屉 ----
  const drawerVisible = ref(false);
  const editing = ref<OpenApp | null>(null);
  const saving = ref(false);
  const formRef = ref();
  const form = reactive({
    appName: '',
    channelScope: '',
    rateLimit: 50,
    expireTime: '' as string | undefined
  });
  /** 勾选的写接口白名单（提交时 join(',') 落 allowedApis, 留空=仅只读接口） */
  const selectedApis = ref<string[]>([]);

  // ---- 数据范围分组树（方案 B 下拉树, 级联勾选; 树加载失败回退手输框兜底） ----
  interface GroupTreeNode {
    key: number;
    title: string;
    children: GroupTreeNode[];
  }
  const groupTree = ref<GroupTreeNode[]>([]);
  const groupTreeFailed = ref(false);
  /** 勾选的分组 id（tree-check-strategy=parent 只含最上级勾选节点, 与后端 expandWithDescendants 展开语义同构） */
  const selectedGroups = ref<number[]>([]);

  async function loadGroupTree() {
    try {
      groupTree.value = mapGroupTree(await groupApi.tree());
    } catch {
      groupTreeFailed.value = true;
    }
  }

  function mapGroupTree(nodes: GroupNode[]): GroupTreeNode[] {
    return nodes.map((n) => ({
      key: n.group.id,
      title: n.group.groupName,
      children: mapGroupTree(n.children || [])
    }));
  }

  /** 存量 CSV 容脏解析（空白/空段/非数字段过滤） */
  function parseScopeIds(csv: string): number[] {
    return csv
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean)
      .map((s) => Number(s))
      .filter((n) => Number.isFinite(n));
  }

  const rules = {
    appName: [{ required: true, message: '应用名称必填' }],
    rateLimit: [
      {
        validator: (v: unknown, cb: (msg?: string) => void) => {
          const n = Number(v);
          if (!Number.isFinite(n) || n < 1 || n > 1000) {
            cb('QPS 范围 1~1000');
          } else {
            cb();
          }
        }
      }
    ]
  };

  // 白名单只约束写接口（设备/通道/分组/录像 4 个只读接口所有应用默认可调, 见 OpenApiSignatureFilter READ_METHODS）,
  // 故勾选清单仅列写接口。API 集合由代码版本决定, 清单跟前端常量走（新增接口时同步本数组 + docs/16 手册接口表）,
  // 单独建库表会与代码漂移, 不做。
  const WRITE_APIS = [
    { path: '/openapi/v1/play', label: '实时点播' },
    { path: '/openapi/v1/play/stop', label: '停止点播' },
    // TODO(openapi): 云台控制暂不对外开放, 恢复时同步此处（见 OpenApiController TODO）
    // { path: '/openapi/v1/ptz/control', label: '云台控制' },
    { path: '/openapi/v1/playback/start', label: '回放点播' },
    { path: '/openapi/v1/playback/control', label: '回放控制' },
    { path: '/openapi/v1/playback/stop', label: '停止回放' }
  ];

  function openCreate() {
    editing.value = null;
    Object.assign(form, { appName: '', channelScope: '', rateLimit: 50, expireTime: '' });
    selectedApis.value = [];
    selectedGroups.value = [];
    drawerVisible.value = true;
  }

  function openEdit(row: OpenApp) {
    editing.value = row;
    Object.assign(form, {
      appName: row.appName,
      channelScope: row.channelScope,
      rateLimit: row.rateLimit,
      expireTime: row.expireTime ? row.expireTime.slice(0, 19) : ''
    });
    // 存量 CSV 容脏（历史手输可能带空白）: 勾选回显前归一化
    selectedApis.value = row.allowedApis
      ? row.allowedApis.split(',').map((s) => s.trim()).filter(Boolean)
      : [];
    selectedGroups.value = parseScopeIds(row.channelScope || '');
    drawerVisible.value = true;
  }

  async function save() {
    const err = await formRef.value?.validate();
    if (err) return;
    saving.value = true;
    try {
      const payload = {
        ...form,
        allowedApis: selectedApis.value.join(','),
        // 树可用=按勾选提交; 树加载失败的兜底手输框=form.channelScope 原值
        channelScope: groupTreeFailed.value ? form.channelScope : selectedGroups.value.join(','),
        expireTime: form.expireTime || undefined
      };
      if (editing.value) {
        await openApiAppApi.update(editing.value.id, payload);
        Message.success('已保存');
      } else {
        const c = await openApiAppApi.create(payload);
        showCredential(c, 'create');
      }
      drawerVisible.value = false;
      if (editing.value) load();
    } finally {
      saving.value = false;
    }
  }

  function confirmRemove(row: OpenApp) {
    Modal.confirm({
      title: '删除应用?',
      content: `「${row.appName}」删除后第三方调用立即失效, 调用日志保留。`,
      okText: '删除',
      okButtonProps: { status: 'danger' },
      onOk: async () => {
        await openApiAppApi.remove(row.id);
        Message.success('已删除');
        load();
      }
    });
  }

  function confirmReset(row: OpenApp) {
    Modal.confirm({
      title: '重置 secret?',
      content: `「${row.appName}」的旧凭证将立即失效, 第三方需改用新 secret。`,
      okText: '重置',
      onOk: async () => {
        const c = await openApiAppApi.resetSecret(row.id);
        showCredential(c, 'reset');
      }
    });
  }

  async function toggleStatus(row: OpenApp) {
    const next = row.status === 1 ? 0 : 1;
    try {
      await openApiAppApi.updateStatus(row.id, next);
      row.status = next;
      Message.success(next === 1 ? '已启用' : '已停用');
    } catch {
      row.status = next === 1 ? 0 : 1;
    }
  }

  // ---- 调用日志 tab ----
  const logRows = ref<OpenApiLog[]>([]);
  const logTotal = ref(0);
  const logLoading = ref(false);
  const logQuery = reactive({ appKey: '', resultCode: -1, page: 1, count: 10 });

  const logPagination = computed(() => ({
    total: logTotal.value,
    current: logQuery.page,
    pageSize: logQuery.count,
    showTotal: true,
    showPageSize: true,
    pageSizeOptions: [10, 20, 50, 100]
  }));

  async function loadLogs() {
    logLoading.value = true;
    try {
      const data = await openApiAppApi.logs({
        appKey: logQuery.appKey || undefined,
        resultCode: logQuery.resultCode === -1 ? undefined : logQuery.resultCode,
        page: logQuery.page,
        count: logQuery.count
      });
      logRows.value = data.list;
      logTotal.value = data.total;
    } finally {
      logLoading.value = false;
    }
  }

  function logSearch() {
    logQuery.page = 1;
    void loadLogs();
  }

  function logPage(p: number) {
    logQuery.page = p;
    void loadLogs();
  }

  function logPageSize(s: number) {
    logQuery.count = s;
    logQuery.page = 1;
    void loadLogs();
  }

  function failText(row: OpenApiLog) {
    if (row.resultCode === 0) return '—';
    return row.remark || `HTTP ${row.resultCode}`;
  }

  const auth = useAuthStore();

  function hasPerm(code: string) {
    return auth.hasPerm(code);
  }

  // ---- 启用状态探测: 未启用时显示引导而非空表/一排注定 404 的请求 ----
  const enabled = ref(true);
  const logsLoaded = ref(false);

  async function loadStatus() {
    try {
      const data = await openApiAppApi.status();
      enabled.value = data.enabled;
    } catch {
      // 探测端点不可用（旧后端包/权限缺失）按未启用处理, 页面显示引导
      enabled.value = false;
    }
  }

  onMounted(async () => {
    await loadStatus();
    if (enabled.value) {
      void load();
      void loadGroupTree();
    }
  });

  function onTabChange(key: string | number) {
    if (key === 'logs' && enabled.value && !logsLoaded.value) {
      logsLoaded.value = true;
      void loadLogs();
    }
  }

  function toggleAllApis(checked: unknown) {
    selectedApis.value = checked ? WRITE_APIS.map((a) => a.path) : [];
  }
</script>

<template>
  <div class="general-card-wrap">
    <a-card :bordered="false" class="general-card">
      <a-alert
        v-if="!enabled"
        type="warning"
        :show-icon="true"
        title="开放接口功能未启用"
      >
        应用管理与调用日志不可用。启用方法: 部署环境设置 VISION_OPENAPI_ENABLED=true 并配置 VISION_SM4_KEY
        （强随机 ≥16 字符主密钥, 配置后不可更换, 换钥即存量密文不可解）后重启服务, 详见《OpenAPI 对接手册》。
      </a-alert>
      <a-tabs v-else v-model:active-key="activeTab" size="medium" @change="onTabChange">
        <a-tab-pane key="apps" title="应用管理">
          <div class="toolbar">
            <a-space wrap>
              <a-input-search
                v-model="query.appName"
                placeholder="应用名称"
                allow-clear
                style="width: 200px"
                @search="search"
                @clear="search"
              />
              <a-select v-model="query.status" :style="{ width: '130px' }" @change="search">
                <a-option :value="-1">全部状态</a-option>
                <a-option :value="1">启用</a-option>
                <a-option :value="0">停用</a-option>
              </a-select>
              <a-button type="outline" @click="search">查询</a-button>
              <a-button type="outline" @click="load">刷新</a-button>
            </a-space>
            <a-button v-permission="'openapi:app:create'" type="primary" @click="openCreate">
              新建应用
            </a-button>
          </div>

          <a-table
            :data="rows"
            :loading="loading"
            :pagination="pagination"
            row-key="id"
            :bordered="{ cell: true }"
            :scroll="{ x: 1500 }"
            @page-change="onPage"
            @page-size-change="onPageSize"
          >
            <template #columns>
              <a-table-column :width="90" title="ID" data-index="id" />
              <a-table-column :width="300" title="App Key">
                <template #cell="{ record }">
                  <a-space :size="4">
                    <span class="mono">{{ record.appKey }}</span>
                    <icon-copy
                      style="cursor: pointer; color: var(--color-text-3)"
                      @click="copyText(record.appKey, 'App Key 已复制')"
                    />
                  </a-space>
                </template>
              </a-table-column>
              <a-table-column :min-width="140" title="应用名称" data-index="appName" ellipsis tooltip />
              <a-table-column :min-width="180" title="API 白名单" ellipsis tooltip>
                <template #cell="{ record }">
                  {{ record.allowedApis || '仅只读接口' }}
                </template>
              </a-table-column>
              <a-table-column :width="110" title="数据范围">
                <template #cell="{ record }">
                  {{ record.channelScope ? `分组×${record.channelScope.split(',').length}` : '全部' }}
                </template>
              </a-table-column>
              <a-table-column :width="70" title="QPS" data-index="rateLimit" />
              <a-table-column :width="170" title="有效期">
                <template #cell="{ record }">
                  {{ record.expireTime ? fmtDateTime(record.expireTime) : '永久' }}
                </template>
              </a-table-column>
              <a-table-column :width="80" title="状态">
                <template #cell="{ record }">
                  <a-switch
                    :model-value="record.status === 1"
                    :disabled="!hasPerm('openapi:app:update')"
                    @change="toggleStatus(record)"
                  />
                </template>
              </a-table-column>
              <a-table-column :width="175" title="创建时间">
                <template #cell="{ record }">
                  {{ fmtDateTime(record.createTime) }}
                </template>
              </a-table-column>
              <a-table-column :width="210" title="操作" fixed="right">
                <template #cell="{ record }">
                  <a-space :size="0">
                    <a-button
                      v-permission="'openapi:app:update'"
                      type="text"
                      size="small"
                      @click="openEdit(record)"
                    >
                      编辑
                    </a-button>
                    <a-button
                      v-permission="'openapi:app:reset'"
                      type="text"
                      size="small"
                      @click="confirmReset(record)"
                    >
                      重置密钥
                    </a-button>
                    <a-button
                      v-permission="'openapi:app:delete'"
                      type="text"
                      size="small"
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
        </a-tab-pane>

        <a-tab-pane key="logs" title="调用日志">
          <div class="toolbar">
            <a-space wrap>
              <a-input-search
                v-model="logQuery.appKey"
                placeholder="App Key"
                allow-clear
                style="width: 220px"
                @search="logSearch"
                @clear="logSearch"
              />
              <a-select v-model="logQuery.resultCode" :style="{ width: '150px' }" @change="logSearch">
                <a-option :value="-1">全部结果</a-option>
                <a-option :value="0">成功</a-option>
                <a-option :value="401">401 认证失败</a-option>
                <a-option :value="403">403 白名单拒绝</a-option>
                <a-option :value="429">429 触发频控</a-option>
              </a-select>
              <a-button type="outline" @click="logSearch">查询</a-button>
              <a-button type="outline" @click="loadLogs">刷新</a-button>
            </a-space>
          </div>

          <a-table
            :data="logRows"
            :loading="logLoading"
            :pagination="logPagination"
            row-key="id"
            :bordered="{ cell: true }"
            :scroll="{ x: 1300 }"
            @page-change="logPage"
            @page-size-change="logPageSize"
          >
            <template #columns>
              <a-table-column :width="170" title="调用时间">
                <template #cell="{ record }">
                  {{ fmtDateTime(record.createTime) }}
                </template>
              </a-table-column>
              <a-table-column :min-width="240" title="App Key" ellipsis tooltip>
                <template #cell="{ record }">
                  <span class="mono">{{ record.appKey || '—' }}</span>
                </template>
              </a-table-column>
              <a-table-column :width="90" title="方法" data-index="method" />
              <a-table-column :min-width="200" title="路径" ellipsis tooltip>
                <template #cell="{ record }">
                  {{ record.path }}
                </template>
              </a-table-column>
              <a-table-column :width="90" title="结果码">
                <template #cell="{ record }">
                  <a-tag :color="record.resultCode === 0 ? 'green' : record.resultCode === 429 ? 'orange' : 'red'">
                    {{ record.resultCode }}
                  </a-tag>
                </template>
              </a-table-column>
              <a-table-column :width="100" title="耗时(ms)" data-index="costMs" />
              <a-table-column :width="140" title="来源 IP" data-index="clientIp" />
              <a-table-column :min-width="200" title="失败原因" ellipsis tooltip>
                <template #cell="{ record }">
                  {{ failText(record) }}
                </template>
              </a-table-column>
            </template>
          </a-table>
        </a-tab-pane>
      </a-tabs>
    </a-card>

    <a-drawer
      :visible="drawerVisible"
      :width="560"
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
          <icon-safe />
          {{ editing ? '编辑应用' : '新建应用' }}
        </span>
      </template>
      <a-form ref="formRef" :model="form" :rules="rules" layout="vertical">
        <a-form-item label="应用名称" field="appName">
          <a-input v-model="form.appName" placeholder="如: 城市大脑对接" allow-clear />
        </a-form-item>
        <a-form-item label="API 白名单（写接口勾选; 不勾 = 仅只读接口）" field="allowedApis">
          <div class="api-checks">
            <a-checkbox
              :model-value="selectedApis.length === WRITE_APIS.length"
              @change="toggleAllApis"
            >
              全选
            </a-checkbox>
            <a-checkbox-group v-model="selectedApis">
              <a-checkbox v-for="api in WRITE_APIS" :key="api.path" :value="api.path">
                {{ api.label }}
              </a-checkbox>
            </a-checkbox-group>
            <div class="form-hint">
              设备/通道/分组/录像 4 个只读接口所有应用默认可调用, 无需勾选; 未勾选的写接口第三方调用将被拒绝(403)。
            </div>
          </div>
        </a-form-item>
        <a-form-item label="数据范围（可调用的分组; 留空 = 全部分组）" field="channelScope">
          <a-tree-select
            v-if="!groupTreeFailed"
            v-model="selectedGroups"
            :data="groupTree"
            tree-checkable
            tree-check-strategy="parent"
            allow-clear
            :allow-search="true"
            :max-tag-count="3"
            placeholder="留空 = 全部分组"
          />
          <a-input
            v-else
            v-model="form.channelScope"
            placeholder="分组 ID, 逗号分隔（留空=全部; 分组树加载失败, 手输兜底）"
            allow-clear
          />
          <div class="form-hint">
            限定该应用可查询/点播/回放的通道范围: 勾选「分组管理」中的分组（勾父分组自动含其子分组下的通道）; 留空不限制。
          </div>
        </a-form-item>
        <a-form-item label="频控 QPS" field="rateLimit">
          <a-input-number v-model="form.rateLimit" :min="1" :max="1000" style="width: 160px" />
        </a-form-item>
        <a-form-item label="接入有效期（留空=永久）" field="expireTime">
          <a-date-picker
            v-model="form.expireTime"
            show-time
            value-format="YYYY-MM-DD HH:mm:ss"
            format="YYYY-MM-DD HH:mm:ss"
            placeholder="选择日期时间，留空为永久"
            allow-clear
            style="width: 100%"
          />
        </a-form-item>
      </a-form>
    </a-drawer>

    <a-modal
      :visible="credVisible"
      :title="credTitle"
      :width="520"
      :mask-closable="false"
      @close="credVisible = false"
      @cancel="credVisible = false"
    >
      <template #footer>
        <a-button type="primary" @click="closeCredential(() => (credVisible = false))">
          我已保存, 关闭
        </a-button>
      </template>
      <a-alert type="warning" :show-icon="true" style="margin-bottom: 12px">
        凭证仅此一次展示, 关闭后只能重置获取。请立即复制保存。
      </a-alert>
      <a-descriptions :column="1" bordered size="large">
        <a-descriptions-item label="App Key">
          <a-space :size="4">
            <span class="mono">{{ cred?.appKey }}</span>
            <icon-copy
              style="cursor: pointer; color: var(--color-text-3)"
              @click="copyText(cred?.appKey || '', 'App Key 已复制')"
            />
          </a-space>
        </a-descriptions-item>
        <a-descriptions-item label="App Secret">
          <a-space :size="4">
            <span class="mono">{{ cred?.appSecret }}</span>
            <icon-copy
              style="cursor: pointer; color: var(--color-text-3)"
              @click="copyText(cred?.appSecret || '', 'App Secret 已复制')"
            />
          </a-space>
        </a-descriptions-item>
      </a-descriptions>
    </a-modal>
  </div>
</template>

<style scoped>
  .toolbar {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 12px;
    gap: 8px;
  }

  .mono {
    font-family: 'JetBrains Mono', Consolas, monospace;
    font-size: 12px;
    word-break: break-all;
  }

  .dlg-title {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    font-weight: 600;
  }

  .api-checks {
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  .form-hint {
    font-size: 12px;
    color: var(--color-text-3);
    line-height: 1.5;
  }
</style>
