<script setup lang="ts">
  import { computed, ref, watch } from 'vue';
  import { Message } from '@arco-design/web-vue';
  import type { TreeNodeData } from '@arco-design/web-vue';
  import { deviceApi } from '@/api/device';
  import type { ChannelTreeNode } from '@/api/device';
  import { recordPlanApi } from '@/api/cloudRecord';
  import type { RecordPlanRow } from '@/api/cloudRecord';
  import WeekPlanEditor from './WeekPlanEditor.vue';

  /**
   * 录像计划抽屉（B3 云端录像排程）: 全局默认计划 + 通道计划行（覆盖全局默认）。
   * 后端仅提供 upsert（PUT /record-plan, 无删除接口）→ 通道行「停用」= enable=0, 仍保留配置。
   */
  const props = defineProps<{ visible: boolean }>();
  const emit = defineEmits<{ (e: 'update:visible', v: boolean): void }>();

  const EMPTY_GRID: boolean[][] = Array.from({ length: 7 }, () =>
    Array.from({ length: 3 }, () => false)
  );

  /** weekPlan 7x48 → 7x3（段 = 该 16 格全 1 才算勾选; 30 分钟格（7×48）, 每段 16 格 = 8 小时） */
  function cellsToSlots(cells: boolean[][] | null | undefined): boolean[][] {
    return Array.from({ length: 7 }, (_, d) =>
      Array.from({ length: 3 }, (_, s) => {
        const row = cells?.[d] ?? [];
        for (let i = s * 16; i < s * 16 + 16; i += 1) {
          if (!row[i]) return false;
        }
        return true;
      })
    );
  }

  /** 7x3 → weekPlan 7x48: seg0=0..15(00-08) seg1=16..31(08-16) seg2=32..47(16-24) */
  function slotsToCells(slots: boolean[][]): boolean[][] {
    return Array.from({ length: 7 }, (_, d) => {
      const row: boolean[] = new Array(48).fill(false);
      for (let s = 0; s < 3; s += 1) {
        if (slots[d]?.[s]) {
          for (let i = s * 16; i < s * 16 + 16; i += 1) row[i] = true;
        }
      }
      return row;
    });
  }

  interface ChannelRow {
    key: string;
    /** 后端行 id（新行未保存时为 null） */
    id: number | null;
    channelDbId: number;
    channelName: string;
    enable: number;
    grid: boolean[][];
    saving: boolean;
  }

  const loading = ref(false);
  const plans = ref<RecordPlanRow[]>([]);
  const channelTree = ref<ChannelTreeNode[]>([]);

  const globalEnable = ref(1);
  const globalGrid = ref<boolean[][]>(EMPTY_GRID.map((r) => r.slice()));
  const globalSaving = ref(false);

  const channelRows = ref<ChannelRow[]>([]);
  const newChannelId = ref<number | undefined>(undefined);

  const channelOptions = computed(() => mapChannelOptions(channelTree.value));

  interface ChannelOption {
    key: number;
    title: string;
    selectable: boolean;
    children?: ChannelOption[];
  }

  function mapChannelOptions(nodes: ChannelTreeNode[]): ChannelOption[] {
    return nodes.map((n) => ({
      key: n.channel.id,
      title: n.channel.customName || n.channel.name || n.channel.channelId,
      selectable: n.channel.parental !== 1,
      children: n.children?.length ? mapChannelOptions(n.children) : undefined
    }));
  }

  const filterByTitle = (kw: string, node: TreeNodeData) =>
    String(node.title ?? '')
      .toLowerCase()
      .includes(kw.trim().toLowerCase());

  const treeSelectable = (node: TreeNodeData) => node.selectable === true;

  function channelLabelOf(row: RecordPlanRow, dbId: number | null): string {
    if (row.channelName) return row.channelName;
    return findChannelTitle(channelTree.value, dbId ?? 0) || `通道 ${dbId ?? '-'}`;
  }

  function findChannelTitle(nodes: ChannelTreeNode[], dbId: number): string {
    for (const n of nodes) {
      if (n.channel.id === dbId) return n.channel.customName || n.channel.name || n.channel.channelId;
      const hit = findChannelTitle(n.children ?? [], dbId);
      if (hit) return hit;
    }
    return '';
  }

  function rowKey(dbId: number) {
    return `ch${dbId}`;
  }

  async function load() {
    loading.value = true;
    try {
      const [list, tree] = await Promise.all([
        recordPlanApi.list(),
        channelTree.value.length ? Promise.resolve(channelTree.value) : deviceApi.channelTree()
      ]);
      plans.value = list;
      channelTree.value = tree;
      applyPlans(list);
    } finally {
      loading.value = false;
    }
  }

  function applyPlans(list: RecordPlanRow[]) {
    const global = list.find((p) => p.planType === 1);
    globalEnable.value = global?.enable === 1 ? 1 : 0;
    globalGrid.value = cellsToSlots(global?.weekPlan ?? null);
    channelRows.value = list
      .filter((p) => p.planType === 2 && p.channelDbId != null)
      .map((p) => ({
        key: rowKey(p.channelDbId ?? 0),
        id: p.id,
        channelDbId: p.channelDbId ?? 0,
        channelName: channelLabelOf(p, p.channelDbId ?? 0),
        enable: p.enable === 1 ? 1 : 0,
        grid: cellsToSlots(p.weekPlan ?? null),
        saving: false
      }));
  }

  /** 新增通道计划行: 已存在则定位, 不重复加 */
  function addChannelRow(dbId: number) {
    const key = rowKey(dbId);
    if (channelRows.value.some((r) => r.key === key)) {
      Message.info('该通道已有计划, 可直接修改');
      newChannelId.value = undefined;
      return;
    }
    channelRows.value.push({
      key,
      id: null,
      channelDbId: dbId,
      channelName: findChannelTitle(channelTree.value, dbId) || `通道 ${dbId}`,
      enable: 1,
      // 新行默认沿用全局默认计划, 再按需调整
      grid: globalGrid.value.map((r) => r.slice()),
      saving: false
    });
    newChannelId.value = undefined;
  }

  async function saveGlobal() {
    globalSaving.value = true;
    try {
      await recordPlanApi.save({
        planType: 1,
        enable: globalEnable.value,
        weekPlan: slotsToCells(globalGrid.value)
      });
      Message.success('全局默认计划已保存');
      await reloadPlans();
    } finally {
      globalSaving.value = false;
    }
  }

  async function saveRow(row: ChannelRow) {
    row.saving = true;
    try {
      await recordPlanApi.save({
        planType: 2,
        channelDbId: row.channelDbId,
        enable: row.enable,
        weekPlan: slotsToCells(row.grid)
      });
      Message.success(`「${row.channelName}」通道计划已保存`);
      await reloadPlans();
    } finally {
      row.saving = false;
    }
  }

  /** 无删除接口: 停用 = enable=0 upsert, 不再参与排程但保留配置 */
  async function disableRow(row: ChannelRow) {
    row.saving = true;
    try {
      await recordPlanApi.save({
        planType: 2,
        channelDbId: row.channelDbId,
        enable: 0,
        weekPlan: slotsToCells(row.grid)
      });
      row.enable = 0;
      Message.success(`「${row.channelName}」通道计划已停用`);
      await reloadPlans();
    } finally {
      row.saving = false;
    }
  }

  /** 保存后只回读计划数据, 不重复拉通道树 */
  async function reloadPlans() {
    plans.value = await recordPlanApi.list();
    applyPlans(plans.value);
  }

  const hasChannelRows = computed(() => channelRows.value.length > 0);

  watch(
    () => props.visible,
    (v) => {
      if (v) {
        channelRows.value = [];
        newChannelId.value = undefined;
        void load();
      }
    }
  );
</script>

<template>
  <a-drawer
    :visible="visible"
    :width="720"
    unmount-on-close
    @cancel="emit('update:visible', false)"
    @close="emit('update:visible', false)"
  >
    <template #title>
      <span class="dlg-title">
        <icon-schedule />
        录像计划
      </span>
    </template>

    <a-spin v-if="loading" style="display: block; margin: 60px auto" :size="26" />
    <template v-else>
      <div class="plan-tip">
        时段粒度 8 小时 × 7 天：勾选 = 该时段云端录像；「通道计划」优先于全局默认计划。
        通道行不支持物理删除，「停用」后不再参与排程（保留配置便于再次启用）。
        云端录像跟随实况流：通道处于计划时段且有人观看（流已建立）才落盘。
        后端计划粒度为 30 分钟，界面暂按 8 小时时段配置（更细粒度挂账）。
      </div>

      <!-- 全局默认计划 -->
      <div class="plan-block">
        <div class="plan-block-head">
          <span class="plan-block-title"><icon-apps /> 全局默认计划</span>
          <a-space>
            <a-switch
              :model-value="globalEnable === 1"
              size="small"
              @change="(v: unknown) => (globalEnable = v === true ? 1 : 0)"
            >
              <template #checked>启用</template>
              <template #unchecked>停用</template>
            </a-switch>
            <a-button type="primary" size="mini" :loading="globalSaving" @click="saveGlobal">
              保存
            </a-button>
          </a-space>
        </div>
        <WeekPlanEditor v-model="globalGrid" />
      </div>

      <!-- 通道计划 -->
      <div class="plan-block">
        <div class="plan-block-head">
          <span class="plan-block-title"><icon-storage /> 通道计划</span>
          <a-tree-select
            :model-value="newChannelId"
            :data="channelOptions"
            :tree-props="{ selectable: treeSelectable }"
            :filter-tree-node="filterByTitle"
            allow-search
            allow-clear
            placeholder="选择通道新增计划"
            style="width: 240px"
            @change="(v: unknown) => (typeof v === 'number' ? addChannelRow(v) : undefined)"
          />
        </div>
        <div v-if="!hasChannelRows" class="plan-empty">暂无通道计划, 全部通道按全局默认计划录像</div>
        <div v-for="row in channelRows" :key="row.key" class="plan-row" :class="{ off: row.enable === 0 }">
          <div class="plan-row-head">
            <span class="plan-row-name" :title="String(row.channelDbId)">
              {{ row.channelName }}
              <a-tag v-if="row.enable === 0" size="small" color="gray">已停用</a-tag>
            </span>
            <a-space>
              <a-switch
                :model-value="row.enable === 1"
                size="small"
                @change="(v: unknown) => (row.enable = v === true ? 1 : 0)"
              >
                <template #checked>启用</template>
                <template #unchecked>停用</template>
              </a-switch>
              <a-button size="mini" type="primary" :loading="row.saving" @click="saveRow(row)">
                保存
              </a-button>
              <a-button
                v-if="row.enable === 1"
                size="mini"
                status="danger"
                :loading="row.saving"
                @click="disableRow(row)"
              >
                停用
              </a-button>
            </a-space>
          </div>
          <WeekPlanEditor v-model="row.grid" />
        </div>
      </div>
    </template>
  </a-drawer>
</template>

<style scoped lang="less">
  .plan-tip {
    margin-bottom: 14px;
    padding: 8px 12px;
    font-size: 12px;
    line-height: 18px;
    color: var(--color-text-2);
    background: var(--color-fill-2);
    border-radius: 4px;
  }

  .plan-block {
    margin-bottom: 20px;

    .plan-block-head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      margin-bottom: 8px;

      .plan-block-title {
        display: inline-flex;
        align-items: center;
        gap: 6px;
        font-size: 13px;
        font-weight: 500;
      }
    }
  }

  .plan-row {
    margin-bottom: 12px;
    padding: 10px 10px 12px;
    border: 1px solid var(--color-border);
    border-radius: 4px;

    &.off {
      background: var(--color-fill-1);
    }

    .plan-row-head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      margin-bottom: 8px;

      .plan-row-name {
        display: inline-flex;
        align-items: center;
        gap: 6px;
        font-size: 13px;
        min-width: 0;
      }
    }
  }

  .plan-empty {
    padding: 16px 0;
    font-size: 12px;
    color: var(--color-text-3);
    text-align: center;
  }
</style>
