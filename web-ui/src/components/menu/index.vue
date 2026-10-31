<script lang="ts" setup>
/**
 * 菜单（Pro 布局适配版）：数据源为后端菜单树（auth store），非前端路由配置。
 */
import { computed, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import router from '@/router';
import type { MenuNode } from '@/stores/auth';
import { useAuthStore } from '@/stores/auth';
import { useAppStore } from '@/store';
import { IconVideoCamera, IconSettings, IconApps, IconLink, IconPlayCircle, IconDashboard, IconNotification } from '@arco-design/web-vue/es/icon';

const auth = useAuthStore();
const appStore = useAppStore();
const route = useRoute();

const dirs = computed(() => auth.menus.filter((d) => d.menuType === 1));
// 顶层条目 = 目录(1) + 一级叶子页(2, V8 起实时预览直连一级); 按钮等其它类型不渲染
const items = computed(() => auth.menus.filter((m) => m.menuType === 1 || m.menuType === 2));
const iconMap: Record<string, unknown> = { IconVideoCamera, IconSettings, IconLink, IconPlayCircle, IconDashboard, IconNotification };
const iconFor = (name?: string) =>
  iconMap[`Icon${(name ?? '').replace(/Outlined$/, '')}`] ?? IconApps;

const fullPath = (dir: MenuNode, menu: MenuNode) =>
  menu.path.startsWith('/') ? menu.path : `${dir.path}/${menu.path}`;

const selectedKeys = ref<string[]>([route.path]);
const openKeys = ref<string[]>([]);

watch(
  () => route.path,
  (p) => {
    selectedKeys.value = [p];
    const hit = dirs.value.find((d) => (d.children ?? []).some((m) => fullPath(d, m) === p));
    if (hit) openKeys.value = [...new Set([...openKeys.value, String(hit.id)])];
  },
  { immediate: true }
);

const collapsed = computed({
  get() {
    if (appStore.device === 'desktop') return appStore.menuCollapse;
    return false;
  },
  set(value: boolean) {
    appStore.updateSettings({ menuCollapse: value });
  },
});
const setCollapse = (val: boolean) => {
  if (appStore.device === 'desktop') appStore.updateSettings({ menuCollapse: val });
};

function goto(path: string) {
  if (route.path !== path) router.push(path);
}
</script>

<template>
  <a-menu
    :collapsed="collapsed"
    :selected-keys="selectedKeys"
    v-model:open-keys="openKeys"
    show-collapse-button
    auto-open-selected
    :level-indent="34"
    style="height: 100%; width: 100%"
    @menu-item-click="goto"
    @collapse="setCollapse"
  >
    <template v-for="item in items" :key="String(item.id)">
      <!-- 一级叶子页: key 用路由路径(与 selectedKeys 对齐) -->
      <a-menu-item v-if="item.menuType === 2" :key="item.path">
        <template #icon>
          <component :is="iconFor(item.icon)" />
        </template>
        {{ item.menuName }}
      </a-menu-item>
      <a-sub-menu v-else :key="String(item.id)">
        <template #icon>
          <component :is="iconFor(item.icon)" />
        </template>
        <template #title>{{ item.menuName }}</template>
        <a-menu-item v-for="menu in item.children ?? []" :key="fullPath(item, menu)">
          {{ menu.menuName }}
        </a-menu-item>
      </a-sub-menu>
    </template>
  </a-menu>
</template>

<style lang="less" scoped>
  :deep(.arco-menu-inner) {
    .arco-menu-inline-header {
      display: flex;
      align-items: center;
    }
    .arco-icon {
      &:not(.arco-icon-down) {
        font-size: 18px;
      }
    }
  }
</style>
