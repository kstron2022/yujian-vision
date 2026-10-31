<template>
  <a-layout class="layout" :class="{ mobile: appStore.hideMenu, 'layout-fill': route.meta.fill }">
    <div v-if="navbar" class="layout-navbar">
      <NavBar />
    </div>
    <a-layout>
      <a-layout>
        <a-layout-sider
          v-if="renderMenu"
          v-show="!hideMenu"
          class="layout-sider"
          breakpoint="xl"
          :collapsed="collapsed"
          :collapsible="true"
          :width="menuWidth"
          :style="{ paddingTop: navbar ? '60px' : '' }"
          :hide-trigger="true"
          @collapse="setCollapsed"
        >
          <div class="menu-wrapper">
            <Menu />
          </div>
        </a-layout-sider>
        <a-drawer
          v-if="hideMenu"
          :visible="drawerVisible"
          placement="left"
          :footer="false"
          mask-closable
          :closable="false"
          @cancel="drawerCancel"
        >
          <Menu />
        </a-drawer>
        <a-layout class="layout-content" :style="paddingStyle">
          <TabBar v-if="appStore.tabBar" />
          <div class="page-area" :class="{ 'page-fill': route.meta.fill }">
            <a-layout-content>
              <Breadcrumb />
              <PageLayout />
            </a-layout-content>
            <Footer v-if="footer" />
          </div>
        </a-layout>
      </a-layout>
    </a-layout>
  </a-layout>
</template>

<script lang="ts" setup>
  import { ref, computed, provide, onMounted } from 'vue';
  import { useRoute } from 'vue-router';
  import { useAppStore } from '@/store';
  import NavBar from '@/components/navbar/index.vue';
  import Menu from '@/components/menu/index.vue';
  import Footer from '@/components/footer/index.vue';
  import TabBar from '@/components/tab-bar/index.vue';
  import Breadcrumb from '@/components/breadcrumb/index.vue';
  import useResponsive from '@/hooks/responsive';
  import PageLayout from './page-layout.vue';

  const isInit = ref(false);
  const appStore = useAppStore();
  const route = useRoute();
  useResponsive(true);
  const navbarHeight = `60px`;
  const navbar = computed(() => appStore.navbar);
  const renderMenu = computed(() => appStore.menu && !appStore.topMenu);
  const hideMenu = computed(() => appStore.hideMenu);
  const footer = computed(() => appStore.footer);
  const menuWidth = computed(() => {
    return appStore.menuCollapse ? 48 : appStore.menuWidth;
  });
  const collapsed = computed(() => {
    return appStore.menuCollapse;
  });
  const paddingStyle = computed(() => {
    const paddingLeft =
      renderMenu.value && !hideMenu.value
        ? { paddingLeft: `${menuWidth.value}px` }
        : {};
    const paddingTop = navbar.value ? { paddingTop: navbarHeight } : {};
    return { ...paddingLeft, ...paddingTop };
  });
  const setCollapsed = (val: boolean) => {
    if (!isInit.value) return; // for page initialization menu state problem
    appStore.updateSettings({ menuCollapse: val });
  };
  const drawerVisible = ref(false);
  const drawerCancel = () => {
    drawerVisible.value = false;
  };
  provide('toggleDrawerMenu', () => {
    drawerVisible.value = !drawerVisible.value;
  });
  onMounted(() => {
    isInit.value = true;
  });
</script>

<style scoped lang="less">
  @nav-size-height: 60px;
  @layout-max-width: 1100px;

  .layout {
    width: 100%;
    height: 100%;

    // fill 页(实时预览): 打通 视口→内容区 的高度约束链。
    // 页面根(flex:1)在高度不定的父级里, 百分比 flex-basis 会退化为 content 高度,
    // 内容一多整页被撑出滚动条; 这里把中间两层 a-layout 与内容区全部定高为视口,
    // 页面即可精确填满, 由页面内部区域自行滚动。其余页面不受影响, 维持整页滚动。
    &.layout-fill {
      > :deep(.arco-layout) {
        height: 100%;
      }

      > :deep(.arco-layout > .arco-layout) {
        height: 100%;
      }

      .layout-content {
        height: 100%;
        min-height: 0;
      }
    }
  }

  .layout-navbar {
    position: fixed;
    top: 0;
    left: 0;
    z-index: 100;
    width: 100%;
    height: @nav-size-height;
  }

  .layout-sider {
    position: fixed;
    top: 0;
    left: 0;
    z-index: 99;
    height: 100%;
    transition: all 0.2s cubic-bezier(0.34, 0.69, 0.1, 1);
    &::after {
      position: absolute;
      top: 0;
      right: -1px;
      display: block;
      width: 1px;
      height: 100%;
      background-color: var(--color-border);
      content: '';
    }

    > :deep(.arco-layout-sider-children) {
      overflow-y: hidden;
    }
  }

  .menu-wrapper {
    height: 100%;
    overflow: auto;
    overflow-x: hidden;
    :deep(.arco-menu) {
      ::-webkit-scrollbar {
        width: 12px;
        height: 4px;
      }

      ::-webkit-scrollbar-thumb {
        border: 4px solid transparent;
        background-clip: padding-box;
        border-radius: 7px;
        background-color: var(--color-text-4);
      }

      ::-webkit-scrollbar-thumb:hover {
        background-color: var(--color-text-3);
      }
    }
  }

  .layout-content {
    min-height: 100vh;
    overflow-y: hidden;
    background-color: var(--color-fill-2);
    transition: padding 0.2s cubic-bezier(0.34, 0.69, 0.1, 1);
  }

  .page-area {
    flex: 1;
    display: flex;
    flex-direction: column;
    padding: 16px;

    // 内容列改为 flex 列: 页面组件可用 flex:1 精确撑满剩余高度(如预览宫格), 不出整页滚动条
    :deep(.arco-layout-content) {
      display: flex;
      flex-direction: column;
    }

    // fill 页(实时预览): 允许收缩到视口内并把剩余高度交给内容列,
    // 面包屑占自然高度, 页面根(flex:1)填满其余 → 左侧树在 side-body 内滚
    &.page-fill {
      min-height: 0;

      :deep(.arco-layout-content) {
        flex: 1;
        min-height: 0;
      }
    }
  }
</style>
