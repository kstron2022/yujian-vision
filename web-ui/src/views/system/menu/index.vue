<script setup lang="ts">
  import { onMounted, ref } from 'vue';
  import { menuApi } from '@/api/system';
  import type { MenuTreeItem } from '@/api/system';

  /**
   * 菜单管理（D22 定稿式）: 菜单/权限点随版本种子(Flyway)演进, 运行期只读展示;
   * 授权动作在「角色管理 → 授权」完成。
   */
  const tree = ref<MenuTreeItem[]>([]);
  const loading = ref(false);

  const typeText: Record<number, string> = { 1: '目录', 2: '菜单', 3: '按钮' };
  const typeColor: Record<number, string> = { 1: 'arcoblue', 2: 'green', 3: 'gray' };

  onMounted(async () => {
    loading.value = true;
    try {
      tree.value = await menuApi.tree();
    } finally {
      loading.value = false;
    }
  });
</script>

<template>
  <div class="general-card-wrap">
    <a-card :bordered="false" class="general-card">
      <div class="menu-tip">
        菜单与权限点由版本种子维护（D22 定稿式），运行期只读；角色与菜单的关联请在「角色管理 → 授权」中配置。
      </div>
      <a-table
        :data="tree"
        :loading="loading"
        :pagination="false"
        row-key="id"
        size="small"
      >
        <template #columns>
          <a-table-column title="名称" :width="260">
            <template #cell="{ record }">{{ record.menuName }}</template>
          </a-table-column>
          <a-table-column title="类型" :width="90" align="center">
            <template #cell="{ record }">
              <a-tag size="small" :color="typeColor[record.menuType]">
                {{ typeText[record.menuType] ?? record.menuType }}
              </a-tag>
            </template>
          </a-table-column>
          <a-table-column title="路由路径" :width="180">
            <template #cell="{ record }">
              <span v-if="record.path" class="menu-meta">{{ record.path }}</span>
              <span v-else>-</span>
            </template>
          </a-table-column>
          <a-table-column title="组件 / 权限码">
            <template #cell="{ record }">
              <span v-if="record.component" class="menu-meta">{{ record.component }}</span>
              <span v-else-if="record.permCode" class="menu-meta perm">{{ record.permCode }}</span>
              <span v-else>-</span>
            </template>
          </a-table-column>
          <a-table-column title="排序" :width="80" align="center">
            <template #cell="{ record }">{{ record.sortOrder }}</template>
          </a-table-column>
          <a-table-column title="状态" :width="90" align="center">
            <template #cell="{ record }">
              <a-tag size="small" :color="record.status === 1 ? 'green' : 'gray'">
                {{ record.status === 1 ? '启用' : '停用' }}
              </a-tag>
            </template>
          </a-table-column>
        </template>
      </a-table>
    </a-card>
  </div>
</template>

<style scoped lang="less">
  .menu-tip {
    margin-bottom: 12px;
    padding: 8px 12px;
    font-size: 12px;
    color: var(--color-text-2);
    background: var(--color-fill-2);
    border-radius: 4px;
  }

  .menu-meta {
    font-size: 12px;
    color: var(--color-text-3);
  }

  .menu-meta.perm {
    font-family: monospace;
    color: rgb(var(--arcoblue-6));
  }
</style>
