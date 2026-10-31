<template>
  <div v-if="items.length" class="container-breadcrumb">
    <a-breadcrumb>
      <a-breadcrumb-item>
        <icon-apps />
      </a-breadcrumb-item>
      <a-breadcrumb-item v-for="item in items" :key="item">
        {{ item }}
      </a-breadcrumb-item>
    </a-breadcrumb>
  </div>
</template>

<script lang="ts" setup>
  import { computed } from 'vue';
  import { useRoute } from 'vue-router';
  import { useAuthStore } from '@/stores/auth';

  /** 自包含: 由后端菜单树 + 当前路径推导面包屑（内容区顶部, Pro 风格） */
  const route = useRoute();
  const auth = useAuthStore();
  const items = computed(() => {
    // 一级叶子页(V8, 如实时预览): 单级面包屑
    for (const menu of auth.menus) {
      if (menu.menuType === 2 && menu.path === route.path) {
        return [menu.menuName];
      }
    }
    for (const dir of auth.menus) {
      for (const menu of dir.children ?? []) {
        const full = menu.path.startsWith('/') ? menu.path : `${dir.path}/${menu.path}`;
        if (full === route.path) {
          return [dir.menuName, menu.menuName];
        }
      }
    }
    return [] as string[];
  });
</script>

<style scoped lang="less">
  .container-breadcrumb {
    margin: 0 0 16px;
    :deep(.arco-breadcrumb-item) {
      color: rgb(var(--gray-6));
      &:last-child {
        color: rgb(var(--gray-8));
      }
    }
  }
</style>
