<template>
  <div class="navbar">
    <div class="left-side">
      <a-space>
        <LogoMark :size="30" />
        <a-typography-title :style="{ margin: 0, fontSize: '18px' }" :heading="5">
          煜见视觉
        </a-typography-title>
        <icon-menu-fold
          v-if="!topMenu && appStore.device === 'mobile'"
          style="font-size: 22px; cursor: pointer"
          @click="toggleDrawerMenu"
        />
      </a-space>
    </div>
    <div class="center-side"></div>
    <ul class="right-side">
      <li>
        <NoticeBell />
      </li>
      <li>
        <a-tooltip :content="theme === 'light' ? '切换暗色' : '切换亮色'">
          <a-button class="nav-btn" type="outline" :shape="'circle'" @click="handleToggleTheme">
            <template #icon>
              <icon-moon-fill v-if="theme === 'dark'" />
              <icon-sun-fill v-else />
            </template>
          </a-button>
        </a-tooltip>
      </li>
      <li>
        <a-tooltip :content="isFullscreen ? '退出全屏' : '全屏'">
          <a-button class="nav-btn" type="outline" :shape="'circle'" @click="toggleFullScreen">
            <template #icon>
              <icon-fullscreen-exit v-if="isFullscreen" />
              <icon-fullscreen v-else />
            </template>
          </a-button>
        </a-tooltip>
      </li>
      <li>
        <a-dropdown trigger="click">
          <a-avatar :size="32" :style="{ marginRight: '8px', cursor: 'pointer', backgroundColor: '#165DFF' }">
            {{ (auth.user?.nickname || auth.user?.username || 'U').slice(0, 1) }}
          </a-avatar>
          <template #content>
            <a-doption @click="pwdVisible = true">
              <a-space>
                <icon-lock />
                <span>修改密码</span>
              </a-space>
            </a-doption>
            <a-doption @click="handleLogout">
              <a-space>
                <icon-export />
                <span>退出登录</span>
              </a-space>
            </a-doption>
          </template>
        </a-dropdown>
      </li>
    </ul>
    <ChangePasswordModal v-model:visible="pwdVisible" />
  </div>
</template>

<script lang="ts" setup>
  import { computed, ref, inject } from 'vue';
  import { useRouter } from 'vue-router';
  import { useDark, useToggle, useFullscreen } from '@vueuse/core';
  import { useAppStore } from '@/store';
  import { useAuthStore } from '@/stores/auth';
  import { wsClient } from '@/utils/ws';
  import LogoMark from '@/components/LogoMark.vue';
  import NoticeBell from '@/components/notice-bell/index.vue';
  import ChangePasswordModal from '@/components/ChangePasswordModal.vue';

  const appStore = useAppStore();
  const auth = useAuthStore();
  const router = useRouter();
  // 修改密码弹窗开关（原跳 /change-password 独立页, 现改为弹窗; 强制改密场景仍走独立页）
  const pwdVisible = ref(false);
  const { isFullscreen, toggle: toggleFullScreen } = useFullscreen();
  const theme = computed(() => appStore.theme);
  const topMenu = computed(() => appStore.topMenu && appStore.menu);
  const isDark = useDark({
    selector: 'body',
    attribute: 'arco-theme',
    valueDark: 'dark',
    valueLight: 'light',
    storageKey: 'arco-theme',
    onChanged(dark: boolean) {
      appStore.toggleTheme(dark);
    },
  });
  const toggleTheme = useToggle(isDark);
  const handleToggleTheme = () => {
    toggleTheme();
  };
  const handleLogout = async () => {
    wsClient.close();
    await auth.logout();
    router.push({ name: 'login' });
  };
  const toggleDrawerMenu = inject('toggleDrawerMenu') as () => void;

</script>
<style scoped lang="less">
  .navbar {
    display: flex;
    justify-content: space-between;
    height: 100%;
    background-color: var(--color-bg-2);
    border-bottom: 1px solid var(--color-border);
  }

  .left-side {
    display: flex;
    align-items: center;
    padding-left: 20px;
  }

  .center-side {
    flex: 1;
  }

  .right-side {
    display: flex;
    padding-right: 20px;
    list-style: none;
    :deep(.locale-select) {
      border-radius: 20px;
    }
    li {
      display: flex;
      align-items: center;
      padding: 0 10px;
    }

    a {
      color: var(--color-text-1);
      text-decoration: none;
    }
    .nav-btn {
      border-color: rgb(var(--gray-2));
      color: rgb(var(--gray-8));
      font-size: 16px;
    }
    .trigger-btn,
    .ref-btn {
      position: absolute;
      bottom: 14px;
    }
    .trigger-btn {
      margin-left: 14px;
    }
  }
</style>
