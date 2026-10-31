<script setup lang="ts">
  import { computed, onActivated, onMounted, reactive, ref } from 'vue';
  import { Message, Modal } from '@arco-design/web-vue';
  import type { FormInstance } from '@arco-design/web-vue';
  import { groupApi } from '@/api/group';
  import type { GbGroup, GroupNode } from '@/api/group';
  import BindChannelsDrawer from './components/BindChannelsDrawer.vue';

  /**
   * B3 分组管理（docs/09 M2 #5, D13）: 左侧分组树(增/改名/挪挂载点/删) + 右侧详情空态。
   * 通道绑定走右侧抽屉（BindChannelsDrawer, 全量覆盖语义）。
   * 按钮权限: group:add / group:update / group:delete / group:bind-channel。
   */

  interface GroupTreeOption {
    key: number;
    title: string;
    sortOrder: number;
    group: GbGroup;
    children?: GroupTreeOption[];
  }

  const tree = ref<GroupNode[]>([]);
  const loading = ref(false);
  const selectedKey = ref<number | null>(null);
  /** 展开的分组节点(数据异步到达后手动全量展开, defaultExpandAll 仅在挂载时生效一次) */
  const expandedKeys = ref<number[]>([]);

  // ---------- 编辑抽屉（新建/编辑共用; 编辑可挪挂载点） ----------
  const editVisible = ref(false);
  /** null = 新建 */
  const editing = ref<GbGroup | null>(null);
  const presetParentId = ref<number | null>(null);
  const saving = ref(false);
  const formRef = ref<FormInstance>();
  const form = reactive<{ parentId?: number; groupName: string; sortOrder: number }>({
    groupName: '',
    sortOrder: 0
  });

  const bindVisible = ref(false);
  const bindGroup = ref<GbGroup | null>(null);

  const treeData = computed<GroupTreeOption[]>(() => tree.value.map(mapNode));

  function mapNode(n: GroupNode): GroupTreeOption {
    return {
      key: n.group.id,
      title: n.group.groupName,
      sortOrder: n.group.sortOrder ?? 0,
      group: n.group,
      children: n.children?.length ? n.children.map((c) => mapNode(c)) : undefined
    };
  }

  /** 树里查分组对象 */
  function findGroup(id: number | null, nodes: GroupNode[] = tree.value): GbGroup | null {
    for (const n of nodes) {
      if (n.group.id === id) return n.group;
      const hit = findGroup(id, n.children ?? []);
      if (hit) return hit;
    }
    return null;
  }

  const selectedGroup = computed(() => findGroup(selectedKey.value));
  const parentGroup = computed(() =>
    selectedGroup.value ? findGroup(selectedGroup.value.parentId) : null
  );

  /** 编辑抽屉父分组候选: 排除自身与全部后代（不允许把自己挂到自己子树上） */
  function collectSelfAndDescendants(id: number | null, nodes: GroupNode[], acc: Set<number>) {
    for (const n of nodes) {
      if (id != null && n.group.id === id) {
        acc.add(n.group.id);
        const walk = (list: GroupNode[]) => {
          for (const c of list) {
            acc.add(c.group.id);
            walk(c.children ?? []);
          }
        };
        walk(n.children ?? []);
        continue;
      }
      collectSelfAndDescendants(id, n.children ?? [], acc);
    }
    return acc;
  }

  const parentOptions = computed(() => {
    const forbid = collectSelfAndDescendants(editing.value?.id ?? null, tree.value, new Set());
    const build = (nodes: GroupNode[]): GroupTreeOption[] =>
      nodes
        .filter((n) => !forbid.has(n.group.id))
        .map((n) => {
          const children = build(n.children ?? []);
          return {
            key: n.group.id,
            title: n.group.groupName,
            sortOrder: n.group.sortOrder ?? 0,
            group: n.group,
            // 叶子不挂空 children, 避免树选择器出现无效展开箭头
            children: children.length ? children : undefined
          };
        });
    return build(tree.value);
  });

  const editTitle = computed(() => (editing.value ? '编辑分组' : presetParentId.value ? '新增子分组' : '新增根分组'));

  // ---------- 数据加载 ----------
  function collectExpandKeys(nodes: GroupNode[], acc: number[] = []): number[] {
    for (const n of nodes) {
      if (n.children?.length) {
        acc.push(n.group.id);
        collectExpandKeys(n.children, acc);
      }
    }
    return acc;
  }

  async function load(silent = false) {
    if (!silent) loading.value = true;
    try {
      tree.value = await groupApi.tree();
      // 数据晚于树挂载到达, defaultExpandAll 不生效 → 手动全量展开
      expandedKeys.value = collectExpandKeys(tree.value);
      // 当前选中/编辑对象被删后落回空态
      if (selectedKey.value != null && !findGroup(selectedKey.value)) selectedKey.value = null;
    } catch {
      // 业务提示由 http 拦截器弹出
    } finally {
      loading.value = false;
    }
  }

  function selectGroup(keys: (string | number | undefined)[]) {
    const k = keys[0];
    selectedKey.value = typeof k === 'number' ? k : null;
  }

  // ---------- 增删改 ----------
  function openCreateRoot() {
    editing.value = null;
    presetParentId.value = null;
    form.groupName = '';
    form.sortOrder = 0;
    form.parentId = undefined;
    editVisible.value = true;
  }

  function openCreateChild(parent: GbGroup) {
    editing.value = null;
    presetParentId.value = parent.id;
    form.groupName = '';
    form.sortOrder = 0;
    form.parentId = parent.id;
    editVisible.value = true;
  }

  function openEdit(g: GbGroup) {
    editing.value = g;
    presetParentId.value = null;
    form.groupName = g.groupName;
    form.sortOrder = g.sortOrder ?? 0;
    form.parentId = g.parentId || undefined;
    editVisible.value = true;
  }

  async function saveEdit() {
    const err = await formRef.value?.validate();
    if (err) return;
    saving.value = true;
    try {
      const payload = {
        parentId: form.parentId ?? null,
        groupName: form.groupName.trim(),
        sortOrder: form.sortOrder ?? 0
      };
      if (editing.value) {
        await groupApi.update(editing.value.id, payload);
        Message.success('分组已更新');
      } else {
        const r = await groupApi.create(payload);
        selectedKey.value = r.id;
        Message.success('分组已创建');
      }
      editVisible.value = false;
      await load();
    } finally {
      saving.value = false;
    }
  }

  function confirmRemove(g: GbGroup) {
    Modal.confirm({
      title: '删除分组',
      content: `确认删除分组「${g.groupName}」？分组下存在子分组或已绑定通道时将无法删除。`,
      okText: '删除',
      cancelText: '取消',
      okButtonProps: { status: 'danger' },
      onOk: async () => {
        await groupApi.remove(g.id);
        Message.success('分组已删除');
        if (selectedKey.value === g.id) selectedKey.value = null;
        await load();
      }
    });
  }

  function openBind(g: GbGroup) {
    bindGroup.value = g;
    bindVisible.value = true;
  }

  onMounted(() => void load());

  // keep-alive 页签: 再次进入静默刷新, 跟上其他端的改动
  let everActivated = false;
  onActivated(() => {
    if (!everActivated) {
      everActivated = true;
      return;
    }
    void load(true);
  });
</script>

<template>
  <div class="page-group">
    <a-card :bordered="false" class="general-card group-card">
      <template #title>
        <span class="dlg-title"><icon-apps /> 分组树</span>
      </template>
      <template #extra>
        <a-space>
          <a-button size="small" :loading="loading" @click="load()">
            <template #icon><icon-refresh /></template>
          </a-button>
          <a-button v-permission="'group:add'" type="primary" size="small" @click="openCreateRoot">
            <template #icon><icon-plus /></template>
            新增根分组
          </a-button>
        </a-space>
      </template>

      <a-spin v-if="loading && !treeData.length" class="tree-spin" :size="24" />
      <a-empty v-else-if="!treeData.length" description="暂无分组, 点击右上角新增">
      </a-empty>
      <a-tree
        v-else
        :selected-keys="selectedKey == null ? [] : [selectedKey]"
        v-model:expanded-keys="expandedKeys"
        :data="treeData"
        block-node
        size="small"
        @select="selectGroup"
      >
        <template #title="{ title }">
          <span class="g-title">{{ title }}</span>
        </template>
        <template #extra="{ group, sortOrder }">
          <span class="g-actions" @click.stop>
            <span v-if="sortOrder" class="g-sort">#{{ sortOrder }}</span>
            <a-tooltip content="新增子分组">
              <a-button
                v-permission="'group:add'"
                type="text"
                size="mini"
                class="g-act"
                @click="openCreateChild(group)"
              >
                <template #icon><icon-plus /></template>
              </a-button>
            </a-tooltip>
            <a-tooltip content="通道绑定">
              <a-button
                v-permission="'group:bind-channel'"
                type="text"
                size="mini"
                class="g-act"
                @click="openBind(group)"
              >
                <template #icon><icon-link /></template>
              </a-button>
            </a-tooltip>
            <a-tooltip content="编辑">
              <a-button
                v-permission="'group:update'"
                type="text"
                size="mini"
                class="g-act"
                @click="openEdit(group)"
              >
                <template #icon><icon-edit /></template>
              </a-button>
            </a-tooltip>
            <a-tooltip content="删除">
              <a-button
                v-permission="'group:delete'"
                type="text"
                size="mini"
                status="danger"
                class="g-act"
                @click="confirmRemove(group)"
              >
                <template #icon><icon-delete /></template>
              </a-button>
            </a-tooltip>
          </span>
        </template>
      </a-tree>
    </a-card>

    <a-card :bordered="false" class="general-card detail-card">
      <!-- 注意: 具名插槽不能包在 v-if 的 template 里（Vue 模板编译会崩）, 标题直接画在内容区 -->
      <template v-if="selectedGroup">
        <div class="detail-head">
          <span class="dlg-title"><icon-storage /> {{ selectedGroup.groupName }}</span>
        </div>
        <a-descriptions :column="1" size="medium" class="g-desc">
          <a-descriptions-item label="分组 ID">{{ selectedGroup.id }}</a-descriptions-item>
          <a-descriptions-item label="上级分组">
            {{ parentGroup ? parentGroup.groupName : '（根分组）' }}
          </a-descriptions-item>
          <a-descriptions-item label="排序">{{ selectedGroup.sortOrder ?? 0 }}</a-descriptions-item>
        </a-descriptions>
        <a-space wrap class="g-btns">
          <a-button v-permission="'group:add'" size="small" @click="openCreateChild(selectedGroup)">
            <template #icon><icon-plus /></template>
            新增子分组
          </a-button>
          <a-button
            v-permission="'group:bind-channel'"
            type="primary"
            size="small"
            @click="openBind(selectedGroup)"
          >
            <template #icon><icon-link /></template>
            通道绑定
          </a-button>
          <a-button v-permission="'group:update'" size="small" @click="openEdit(selectedGroup)">
            <template #icon><icon-edit /></template>
            编辑
          </a-button>
          <a-button v-permission="'group:delete'" status="danger" size="small" @click="confirmRemove(selectedGroup)">
            <template #icon><icon-delete /></template>
            删除
          </a-button>
        </a-space>
      </template>
      <div v-else class="detail-empty">
        <a-empty description="在左侧选择分组查看详情，或直接使用节点上的悬浮操作" />
      </div>
    </a-card>

    <!-- 编辑抽屉: 新建/编辑共用, 编辑时可挪挂载点 -->
    <a-drawer
      :visible="editVisible"
      :width="460"
      unmount-on-close
      :ok-loading="saving"
      ok-text="保 存"
      cancel-text="取 消"
      @ok="saveEdit"
      @cancel="editVisible = false"
      @close="editVisible = false"
    >
      <template #title>
        <span class="dlg-title"><icon-edit /> {{ editTitle }}</span>
      </template>
      <a-form ref="formRef" :model="form" layout="vertical">
        <a-form-item
          label="上级分组"
          help="不选 = 根分组；编辑时改选上级即挪动挂载点"
          field="parentId"
        >
          <a-tree-select
            v-model="form.parentId"
            :data="parentOptions"
            :allow-search="true"
            allow-clear
            placeholder="不选 = 根分组"
            :max-tag-count="2"
          />
        </a-form-item>
        <a-form-item
          label="分组名称"
          field="groupName"
          required
          :rules="[{ required: true, message: '请输入分组名称' }]"
        >
          <a-input v-model="form.groupName" :max-length="32" allow-clear placeholder="如：园区东门" />
        </a-form-item>
        <a-form-item label="排序" field="sortOrder" help="数值小的排前面">
          <a-input-number v-model="form.sortOrder" :min="0" :max="9999" style="width: 160px" />
        </a-form-item>
      </a-form>
    </a-drawer>

    <BindChannelsDrawer
      v-model:visible="bindVisible"
      :group="bindGroup"
      @saved="load(true)"
    />
  </div>
</template>

<style scoped lang="less">
  .page-group {
    display: flex;
    gap: 16px;
    align-items: stretch;

    .group-card {
      width: 400px;
      flex-shrink: 0;

      :deep(.arco-card-body) {
        min-height: 240px;
        padding-top: 8px;
      }
    }

    .detail-card {
      flex: 1;
      min-width: 0;

      :deep(.arco-card-body) {
        padding-top: 16px;
      }
    }
  }

  .tree-spin {
    display: block;
    margin: 48px auto;
  }

  .g-title {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    font-size: 13px;
  }

  .g-actions {
    display: inline-flex;
    align-items: center;
    gap: 0;
    // 悬浮才出现, 避免树行被按钮挤乱
    opacity: 0;
    transition: opacity 0.15s ease;

    .g-sort {
      font-size: 11px;
      color: var(--color-text-4);
      margin-right: 4px;
    }
  }

  :deep(.arco-tree-node:hover) .g-actions,
  .g-actions:focus-within {
    opacity: 1;
  }

  .detail-head {
    display: flex;
    align-items: center;
    margin-bottom: 12px;
  }

  .g-desc {
    margin-bottom: 16px;
  }

  .g-btns {
    padding-top: 4px;
  }

  .detail-empty {
    display: flex;
    align-items: center;
    justify-content: center;
    min-height: 280px;
  }
</style>
