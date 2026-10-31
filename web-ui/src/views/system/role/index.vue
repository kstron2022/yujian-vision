<script setup lang="ts">
  import { computed, onMounted, reactive, ref } from 'vue';
  import { Message, Modal } from '@arco-design/web-vue';
  import type { FormInstance, TreeNodeData } from '@arco-design/web-vue';
  import { roleApi, menuApi } from '@/api/system';
  import type { SysRole, MenuTreeItem } from '@/api/system';
  import { groupApi } from '@/api/group';
  import type { GroupNode } from '@/api/group';
  import { useAuthStore } from '@/stores/auth';
  import { fmtDateTime } from '@/utils/format';

  const auth = useAuthStore();
  const rows = ref<SysRole[]>([]);
  const total = ref(0);
  const loading = ref(false);
  const query = reactive({ keyword: '', page: 1, count: 10 });

  const drawerVisible = ref(false);
  const editing = ref<SysRole | null>(null);
  const saving = ref(false);
  const formRef = ref<FormInstance>();
  const form = reactive({ roleCode: '', roleName: '', remark: '' });

  // 授权抽屉(D22: 菜单定稿式, 运行期仅"查看树+角色授权")
  const authVisible = ref(false);
  const authTarget = ref<SysRole | null>(null);
  const authSaving = ref(false);
  const menuTree = ref<MenuTreeItem[]>([]);
  const checkedKeys = ref<number[]>([]);
  const treeFieldNames = { key: 'id', title: 'menuName', children: 'children' };
  // Arco a-tree 的入参类型(TreeNodeData)与业务类型对齐
  const treeData = computed<TreeNodeData[]>(() => menuTree.value as unknown as TreeNodeData[]);

  const pagination = computed(() => ({
    total: total.value,
    current: query.page,
    pageSize: query.count,
    showTotal: true
  }));

  const rules = {
    roleCode: [{ required: true, message: '请输入角色编码' }],
    roleName: [{ required: true, message: '请输入角色名称' }]
  };

  async function load() {
    loading.value = true;
    try {
      const data = await roleApi.page({
        keyword: query.keyword || undefined,
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

  function openCreate() {
    editing.value = null;
    form.roleCode = '';
    form.roleName = '';
    form.remark = '';
    drawerVisible.value = true;
  }

  function openEdit(row: SysRole) {
    editing.value = row;
    form.roleCode = row.roleCode;
    form.roleName = row.roleName;
    form.remark = row.remark || '';
    drawerVisible.value = true;
  }

  async function save() {
    const err = await formRef.value?.validate();
    if (err) return;
    saving.value = true;
    try {
      if (editing.value) {
        await roleApi.update(editing.value.id, { roleName: form.roleName, remark: form.remark });
        Message.success('角色已更新');
      } else {
        await roleApi.create({
          roleCode: form.roleCode,
          roleName: form.roleName,
          remark: form.remark
        });
        Message.success('角色已创建（可继续在「授权」中分配菜单）');
      }
      drawerVisible.value = false;
      await load();
    } finally {
      saving.value = false;
    }
  }

  async function openAuth(row: SysRole) {
    authTarget.value = row;
    checkedKeys.value = [];
    if (!menuTree.value.length) {
      menuTree.value = await menuApi.tree();
    }
    checkedKeys.value = await roleApi.menuIds(row.id);
    authVisible.value = true;
  }

  async function saveAuth() {
    if (!authTarget.value) return;
    authSaving.value = true;
    try {
      await roleApi.grant(authTarget.value.id, checkedKeys.value);
      Message.success(`已保存「${authTarget.value.roleName}」的菜单授权`);
      authVisible.value = false;
    } finally {
      authSaving.value = false;
    }
  }

  function confirmRemove(row: SysRole) {
    Modal.confirm({
      title: '删除角色',
      content: `确认删除角色「${row.roleName}」？已关联用户的授权将随之失效。`,
      okText: '删除',
      cancelText: '取消',
      okButtonProps: { status: 'danger' },
      onOk: async () => {
        await roleApi.remove(row.id);
        Message.success('删除成功');
        await load();
      }
    });
  }

  // ---------- 数据授权抽屉（B3 通道级授权, perm sys:role:data-scope） ----------
  const dsVisible = ref(false);
  const dsTarget = ref<SysRole | null>(null);
  const dsSaving = ref(false);
  /** 1=全部通道 2=按分组授权 */
  const dsScope = ref(1);
  /** 已授权分组 id（check-strictly: 只勾选已授权分组本身, 不联动子分组） */
  const dsChecked = ref<number[]>([]);
  const groupTree = ref<GroupNode[]>([]);
  const dsLoading = ref(false);
  const dsFieldNames = { key: 'key', title: 'title', children: 'children' };

  const dsTreeData = computed<TreeNodeData[]>(() => {
    const build = (nodes: GroupNode[]): TreeNodeData[] =>
      nodes.map((n) => ({
        key: n.group.id,
        title: n.group.groupName,
        children: build(n.children ?? [])
      }));
    return build(groupTree.value);
  });

  async function openDataScope(row: SysRole) {
    dsTarget.value = row;
    dsScope.value = 1;
    dsChecked.value = [];
    dsLoading.value = true;
    try {
      // 分组树缓存 + 授权回显并行
      const [scope, tree] = await Promise.all([
        roleApi.dataScope(row.id),
        groupTree.value.length ? Promise.resolve(groupTree.value) : groupApi.tree()
      ]);
      groupTree.value = tree;
      dsScope.value = scope.dataScope === 2 ? 2 : 1;
      dsChecked.value = scope.groupIds ?? [];
      dsVisible.value = true;
    } finally {
      dsLoading.value = false;
    }
  }

  /** 实际提交（空分组确认通过后也走这里） */
  async function submitDataScope() {
    if (!dsTarget.value) return;
    dsSaving.value = true;
    try {
      // 全部通道时不携带分组; 按分组时提交勾选的分组（后端全量覆盖）
      await roleApi.grantDataScope(
        dsTarget.value.id,
        dsScope.value,
        dsScope.value === 2 ? dsChecked.value : []
      );
      Message.success(`已保存「${dsTarget.value.roleName}」的数据授权`);
      dsVisible.value = false;
    } finally {
      dsSaving.value = false;
    }
  }

  async function saveDataScope() {
    if (!dsTarget.value) return;
    // 按分组授权但一个分组都没勾 = 保存后该角色看不到任何通道, 二次确认防误存自锁
    if (dsScope.value === 2 && !dsChecked.value.length) {
      Modal.confirm({
        title: '保存空的数据授权',
        content: '该角色将看不到任何通道，确认保存？',
        okText: '确认保存',
        cancelText: '取消',
        onOk: () => submitDataScope()
      });
      return;
    }
    await submitDataScope();
  }

  onMounted(() => void load());
</script>

<template>
  <div class="general-card-wrap">
    <a-card :bordered="false" class="general-card">
      <div class="toolbar">
        <a-input-search
          v-model="query.keyword"
          placeholder="角色编码 / 名称"
          allow-clear
          style="width: 220px"
          @search="search"
          @clear="search"
        />
        <a-button v-if="auth.hasPerm('sys:role:add')" type="primary" size="small" @click="openCreate">
          <template #icon><icon-plus /></template>
          新增角色
        </a-button>
      </div>

      <a-table
        :data="rows"
        :loading="loading"
        :pagination="pagination"
        :scroll="{ x: 760 }"
        row-key="id"
        size="medium"
        @page-change="(p: number) => (query.page = p, load())"
      >
        <template #columns>
          <a-table-column title="角色编码" :width="160">
            <template #cell="{ record }">{{ record.roleCode }}</template>
          </a-table-column>
          <a-table-column title="角色名称" :width="160">
            <template #cell="{ record }">{{ record.roleName }}</template>
          </a-table-column>
          <a-table-column title="备注">
            <template #cell="{ record }">{{ record.remark || '-' }}</template>
          </a-table-column>
          <a-table-column title="创建时间" :width="230">
            <template #cell="{ record }">{{ fmtDateTime(record.createTime) }}</template>
          </a-table-column>
          <a-table-column title="操作" :width="250" fixed="right">
            <template #cell="{ record }">
              <a-space :size="2">
                <a-button
                  v-if="auth.hasPerm('sys:role:auth')"
                  type="text"
                  size="mini"
                  @click="openAuth(record)"
                >
                  授权
                </a-button>
                <span v-permission="'sys:role:data-scope'" class="ds-btn-wrap">
                  <!-- admin 的数据范围后端恒为全部通道, 置灰并说明原因（点击无效避免误以为可配） -->
                  <a-tooltip v-if="record.roleCode === 'admin'" content="超级管理员恒为全部通道">
                    <span class="ds-btn-wrap">
                      <a-button type="text" size="mini" disabled>数据授权</a-button>
                    </span>
                  </a-tooltip>
                  <a-button v-else type="text" size="mini" @click="openDataScope(record)">
                    数据授权
                  </a-button>
                </span>
                <a-button
                  v-if="auth.hasPerm('sys:role:update')"
                  type="text"
                  size="mini"
                  @click="openEdit(record)"
                >
                  编辑
                </a-button>
                <a-button
                  v-if="auth.hasPerm('sys:role:delete')"
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
          <icon-idcard />
          {{ editing ? '编辑角色' : '新增角色' }}
        </span>
      </template>

      <a-form ref="formRef" :model="form" :rules="rules" layout="vertical">
        <a-form-item label="角色编码" field="roleCode" help="创建后不可修改；登录鉴权用（如 operator）">
          <a-input v-model="form.roleCode" :disabled="!!editing" allow-clear />
        </a-form-item>
        <a-form-item label="角色名称" field="roleName">
          <a-input v-model="form.roleName" allow-clear />
        </a-form-item>
        <a-form-item label="备注">
          <a-textarea v-model="form.remark" :max-length="200" :auto-size="{ minRows: 2, maxRows: 4 }" />
        </a-form-item>
      </a-form>
    </a-drawer>

    <a-drawer
      :visible="authVisible"
      :width="700"
      unmount-on-close
      :ok-loading="authSaving"
      ok-text="保存授权"
      cancel-text="取消"
      @ok="saveAuth"
      @cancel="authVisible = false"
      @close="authVisible = false"
    >
      <template #title>
        <span class="dlg-title">
          <icon-branch />
          菜单授权 · {{ authTarget?.roleName }}
        </span>
      </template>
      <div class="auth-tip">勾选该角色可见的菜单与可用按钮（含按钮权限点），保存后即时生效；用户重新登录后菜单刷新。</div>
      <a-tree
        v-model:checked-keys="checkedKeys"
        :data="treeData"
        :field-names="treeFieldNames"
        checkable
        check-strictly
        block-node
        size="small"
      />
    </a-drawer>

    <!-- 数据授权抽屉（B3 通道级授权） -->
    <a-drawer
      :visible="dsVisible"
      :width="700"
      unmount-on-close
      :ok-loading="dsSaving"
      ok-text="保 存"
      cancel-text="取 消"
      @ok="saveDataScope"
      @cancel="dsVisible = false"
      @close="dsVisible = false"
    >
      <template #title>
        <span class="dlg-title">
          <icon-safe />
          数据授权 · {{ dsTarget?.roleName }}
        </span>
      </template>
      <a-spin v-if="dsLoading" style="display: block; margin: 48px auto" :size="24" />
      <template v-else>
        <a-form :model="{ dataScope: dsScope }" layout="vertical">
          <a-form-item label="可见通道范围">
            <a-radio-group v-model="dsScope" direction="vertical">
              <a-radio :value="1">全部通道（不限制）</a-radio>
              <a-radio :value="2">按分组授权（仅可见已勾选分组下的通道）</a-radio>
            </a-radio-group>
          </a-form-item>
        </a-form>
        <template v-if="dsScope === 2">
          <div class="auth-tip">
            勾选分组 = 该分组及其全部子分组生效（预置位/点播/回放/云端录像等通道级数据均按此范围过滤）。
            回显仅勾选已授权分组本身, 子分组不会自动勾上。
          </div>
          <div v-if="!dsTreeData.length" class="ds-empty">
            <a-empty description="暂无分组, 请先在「接入管理-分组管理」中创建" />
          </div>
          <a-tree
            v-else
            v-model:checked-keys="dsChecked"
            :data="dsTreeData"
            :field-names="dsFieldNames"
            checkable
            check-strictly
            block-node
            size="small"
            default-expand-all
          />
        </template>
      </template>
    </a-drawer>
  </div>
</template>

<style scoped lang="less">
  .toolbar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 12px;
  }

  .auth-tip {
    margin-bottom: 12px;
    padding: 8px 12px;
    font-size: 12px;
    color: var(--color-text-2);
    background: var(--color-fill-2);
    border-radius: 4px;
  }

  // 数据授权按钮的 tooltip 悬挂层: 禁用态按钮不接收 hover, 事件落在外层 span 上
  .ds-btn-wrap {
    display: inline-flex;
    align-items: center;
  }

  .ds-empty {
    display: flex;
    justify-content: center;
    padding: 32px 0;
  }
</style>
