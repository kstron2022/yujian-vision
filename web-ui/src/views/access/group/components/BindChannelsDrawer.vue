<script setup lang="ts">
  import { ref, watch } from 'vue';
  import { Message } from '@arco-design/web-vue';
  import type { TreeNodeData } from '@arco-design/web-vue';
  import { groupApi } from '@/api/group';
  import type { GbGroup } from '@/api/group';

  /**
   * 分组「通道绑定」抽屉: 设备→通道两级树勾选, 保存全量覆盖当前分组。
   * 单分组约束(docs/02 §2.12 / docs/03 §3.1 修订): 通道仅可属一个分组,
   * 已绑定其他分组的通道不在列表中(数据源 /groups/{id}/bind-view, 通道按数据权限过滤)。
   * 设备节点可勾选 = 级联全选其下通道; 默认仅展开含已绑通道的设备, 搜索时自动展开。
   */
  const props = defineProps<{ visible: boolean; group: GbGroup | null }>();
  const emit = defineEmits<{
    (e: 'update:visible', v: boolean): void;
    (e: 'saved'): void;
  }>();

  /** /groups/{id}/bind-view 通道行(仅前端消费字段) */
  interface BindViewChannel {
    id: number;
    channelId: string;
    name: string;
    status: number;
    deviceDbId: number;
    deviceName: string;
    boundGroupIds: number[];
    /** 目录节点(虚拟分组): 只作级联勾选的分组壳, 保存只取通道叶子 */
    directory: boolean;
    /** 父节点编码(指向本设备内某目录节点的 channelId) */
    parentId?: string;
  }

  interface BindNode {
    /** 设备 'd{deviceDbId}' / 目录 'g{channelDbId}' / 通道 'c{channelDbId}' */
    key: string;
    title: string;
    checkable: boolean;
    /** 通道叶子附带字段(title 渲染/搜索用) */
    channelId?: string;
    status?: number;
    /** 目录节点: 文件夹图标+「目录」标; key 用 g 前缀与通道叶子区分(保存只取 c 前缀) */
    dir?: boolean;
    children?: BindNode[];
  }

  const loading = ref(false);
  const saving = ref(false);
  const keyword = ref('');
  /** 已勾选节点(含级联的设备/目录节点, 提交时只取通道叶子) */
  const checkedKeys = ref<string[]>([]);
  const expandedKeys = ref<string[]>([]);
  const treeData = ref<BindNode[]>([]);
  /** 全部可见通道均被其他分组绑定时给出针对性空态 */
  const allBoundElsewhere = ref(false);

  /**
   * 组绑定树: 设备 → 目录层级(下级平台行政区划等) → 通道叶子。
   * 目录节点 key 用 g 前缀, 可勾选但只是级联开关——保存只取 c 前缀的通道叶子,
   * 目录本身不是绑定对象(后端 doPlay 也不允许目录点播)。
   */
  function buildTree(channels: BindViewChannel[], groupId: number) {
    const checked: string[] = [];
    const devices = new Map<number, BindNode>();
    const deviceRows = new Map<number, BindViewChannel[]>();
    for (const c of channels) {
      let d = devices.get(c.deviceDbId);
      if (!d) {
        d = {
          key: `d${c.deviceDbId}`,
          title: c.deviceName || `设备#${c.deviceDbId}`,
          checkable: true,
          children: []
        };
        devices.set(c.deviceDbId, d);
        deviceRows.set(c.deviceDbId, []);
      }
      deviceRows.get(c.deviceDbId)!.push(c);
      if (c.boundGroupIds?.includes(groupId)) checked.push(`c${c.id}`);
    }
    // 设备按名称排序, 设备内按目录层级组树
    const nodes = [...devices.values()].sort((a, b) => a.title.localeCompare(b.title, 'zh-CN'));
    for (const [deviceDbId, d] of devices) {
      d.children = buildHierarchy(deviceRows.get(deviceDbId)!, checked);
    }
    return { nodes, checked, expanded: collectExpanded(nodes, checked) };
  }

  /**
   * 设备内按 parentId 组目录层级: parentId 命中目录节点则挂其下, 否则提升为设备直接子
   * (数据权限滤掉父节点/跨设备脏数据时防丢行); 目录节点排前、通道叶子排后; 剪无可见叶子的空目录。
   */
  function buildHierarchy(rows: BindViewChannel[], checked: string[]): BindNode[] {
    const nodeByChId = new Map<string, BindNode>();
    for (const c of rows) {
      const node: BindNode = c.directory
        ? {
            key: `g${c.id}`,
            title: c.name || c.channelId,
            checkable: true,
            dir: true,
            children: []
          }
        : {
            key: `c${c.id}`,
            title: c.name,
            checkable: true,
            channelId: c.channelId,
            status: c.status
          };
      nodeByChId.set(c.channelId, node);
    }
    const dirs: BindNode[] = [];
    const leaves: BindNode[] = [];
    for (const c of rows) {
      const node = nodeByChId.get(c.channelId)!;
      const parent = c.parentId ? nodeByChId.get(c.parentId) : undefined;
      if (parent && parent !== node) {
        parent.children!.push(node);
      } else if (c.directory) {
        dirs.push(node);
      } else {
        leaves.push(node);
      }
    }
    // 目录节点 children 内也按目录在前; 无可见叶子的目录剪掉(不出空壳)
    const pruned = [...dirs, ...leaves].map(fold).filter(keepIfHasLeaf);
    return pruned;

    function fold(n: BindNode): BindNode {
      if (n.dir && n.children) {
        const dirsIn: BindNode[] = [];
        const leavesIn: BindNode[] = [];
        for (const ch of n.children) {
          (ch.dir ? dirsIn : leavesIn).push(fold(ch));
        }
        n.children = [...dirsIn, ...leavesIn];
      }
      return n;
    }

    function keepIfHasLeaf(n: BindNode): boolean {
      if (!n.dir) return true; // 通道叶子永远保留
      n.children = n.children?.filter(keepIfHasLeaf);
      return !!n.children?.length;
    }
  }

  /** 默认展开含已绑通道的设备与其目录祖先链(勾选藏在收起目录里会看不见) */
  function collectExpanded(nodes: BindNode[], checked: string[]): string[] {
    const expanded: string[] = [];
    const walk = (n: BindNode): boolean => {
      let has = false;
      if (!n.dir && n.key.startsWith('c')) has = checked.includes(n.key);
      let childHas = false;
      for (const ch of n.children ?? []) {
        if (walk(ch)) childHas = true;
      }
      if (childHas) {
        has = true;
        expanded.push(n.key);
      }
      return has;
    };
    for (const n of nodes) {
      walk(n);
    }
    return expanded;
  }

  function matchNode(n: BindNode, kw: string): boolean {
    return (
      n.title.toLowerCase().includes(kw) ||
      (n.channelId?.toLowerCase().includes(kw) ?? false) ||
      !!n.children?.some((c) => matchNode(c, kw))
    );
  }

  /** 过滤只影响展示, 隐藏节点不丢勾选状态 */
  const filterByTitle = (node: TreeNodeData) => {
    const kw = keyword.value.trim().toLowerCase();
    if (!kw) return true;
    return matchNode(node as unknown as BindNode, kw);
  };

  async function load() {
    if (!props.group) return;
    loading.value = true;
    try {
      const view = await groupApi.bindView(props.group.id);
      // 单分组约束: 只显示 未绑定 + 已绑定当前组 的通道
      const visible = view.channels.filter(
        (c) => !c.boundGroupIds?.length || c.boundGroupIds.includes(props.group!.id)
      );
      const { nodes, checked, expanded } = buildTree(visible, props.group.id);
      treeData.value = nodes;
      checkedKeys.value = checked;
      expandedKeys.value = expanded;
      allBoundElsewhere.value = !nodes.length;
    } finally {
      loading.value = false;
    }
  }

  async function save() {
    if (!props.group) return;
    // 只提交通道叶子(设备节点 key 是级联勾选带出来的)
    const ids = checkedKeys.value
      .filter((k) => k.startsWith('c'))
      .map((k) => Number(k.slice(1)));
    saving.value = true;
    try {
      await groupApi.bindChannels(props.group.id, ids);
      Message.success(`已保存「${props.group.groupName}」的通道绑定（共 ${ids.length} 个）`);
      emit('update:visible', false);
      emit('saved');
    } finally {
      saving.value = false;
    }
  }

  // 搜索时展开所有含子节点的层级(设备+目录), 否则深层匹配项藏在收起节点里不可见
  watch(keyword, (kw) => {
    if (!kw.trim()) return;
    const expandable: string[] = [];
    const walk = (nodes: BindNode[]) => {
      for (const n of nodes) {
        if (n.children?.length) {
          expandable.push(n.key);
          walk(n.children);
        }
      }
    };
    walk(treeData.value);
    expandedKeys.value = expandable;
  });

  watch(
    () => props.visible,
    (v) => {
      if (v) {
        keyword.value = '';
        checkedKeys.value = [];
        expandedKeys.value = [];
        treeData.value = [];
        void load();
      }
    }
  );
</script>

<template>
  <a-drawer
    :visible="visible"
    :width="520"
    unmount-on-close
    @cancel="emit('update:visible', false)"
    @close="emit('update:visible', false)"
  >
    <template #title>
      <span class="dlg-title">
        <icon-link />
        通道绑定 · {{ group?.groupName || '' }}
      </span>
    </template>

    <div class="bind-tip">
      勾选通道加入分组, 保存即<b>全量覆盖</b>本分组的绑定（取消勾选 = 移出）。
      通道仅可属于一个分组, 已绑定到其他分组的通道不在列表中。
      目录（📁）节点只是分组壳, 勾选=级联全选其下通道, 保存只记录通道。
    </div>

    <a-input-search v-model="keyword" placeholder="搜索通道名称 / 国标编号 / 设备" allow-clear />

    <a-spin v-if="loading" class="bind-spin" :size="24" />
    <a-empty
      v-else-if="!treeData.length"
      class="bind-empty"
      :description="allBoundElsewhere ? '无可绑定通道（均已绑定到其他分组）' : '暂无可见通道'"
    />
    <a-tree
      v-else
      v-model:checked-keys="checkedKeys"
      v-model:expanded-keys="expandedKeys"
      :data="treeData"
      checkable
      block-node
      size="small"
      class="bind-tree"
      :filter-tree-node="filterByTitle"
    >
      <template #title="{ title, channelId, status, children, dir }">
        <span class="bind-title">
          <icon-folder v-if="dir" class="bind-dir-icon" />
          <span v-if="status !== undefined" class="bind-dot" :class="status === 1 ? 'on' : 'off'" />
          {{ title }}
          <span v-if="dir" class="bind-dir-tag">目录</span>
          <span v-if="channelId" class="bind-chid">{{ channelId }}</span>
          <span v-else-if="children" class="bind-devcount">{{ children.length }}</span>
        </span>
      </template>
    </a-tree>

    <template #footer>
      <div class="bind-footer">
        <span class="bind-count">已选 {{ checkedKeys.filter((k) => k.startsWith('c')).length }} 个通道</span>
        <a-space>
          <a-button @click="emit('update:visible', false)">取 消</a-button>
          <a-button type="primary" :loading="saving" @click="save">保 存</a-button>
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

  // 抽屉 body 改 flex 列: 树区铺满剩余高度并自滚(修复底部空白), tip/搜索固定在上方
  :deep(.arco-drawer-body) {
    display: flex;
    flex-direction: column;
    overflow: hidden;
  }

  .bind-tip {
    margin-bottom: 12px;
    padding: 8px 12px;
    font-size: 12px;
    line-height: 18px;
    color: var(--color-text-2);
    background: var(--color-fill-2);
    border-radius: 4px;
    flex-shrink: 0;
  }

  .bind-spin {
    display: block;
    margin: 40px auto;
    flex-shrink: 0;
  }

  .bind-empty {
    display: flex;
    align-items: center;
    justify-content: center;
    flex: 1;
  }

  .bind-tree {
    flex: 1;
    min-height: 0;
    margin-top: 8px;
    overflow: auto;

    :deep(.arco-tree-node) {
      padding: 2px 4px;
      border-radius: 4px;
    }
  }

  .bind-title {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    font-size: 13px;
    min-width: 0;

    .bind-dot {
      width: 6px;
      height: 6px;
      border-radius: 50%;
      flex-shrink: 0;

      &.on {
        background: rgb(var(--green-6));
      }

      &.off {
        background: var(--color-text-4);
      }
    }

    .bind-chid {
      font-size: 11px;
      color: var(--color-text-4);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .bind-dir-icon {
      flex-shrink: 0;
      color: var(--color-text-3);
    }

    .bind-dir-tag {
      flex-shrink: 0;
      font-size: 11px;
      color: var(--color-text-2);
      background: var(--color-fill-3);
      padding: 0 5px;
      border-radius: 2px;
    }

    .bind-devcount {
      font-size: 11px;
      color: var(--color-text-4);
    }
  }

  .bind-footer {
    display: flex;
    align-items: center;
    justify-content: space-between;

    .bind-count {
      font-size: 13px;
      color: var(--color-text-3);
    }
  }
</style>
