<template>
  <BackendStatus />
  <router-view />
</template>

<script lang="ts" setup>
  import { watch } from 'vue';
  import BackendStatus from '@/components/backend-status/index.vue';
  import { useAuthStore } from '@/stores/auth';
  import { wsClient } from '@/utils/ws';

  // WS 连接生命周期挂在 App 层(而非仅登录页): 登录/刷新/退出后 token 变化时自动建/断连。
  // 此前 connect 只在 LoginView 调用, 刷新页面后永远没有 WS, 收不到 STREAM_READY 等推送。
  const auth = useAuthStore();
  watch(
    () => auth.token,
    (token) => {
      if (token) wsClient.connect();
      else wsClient.close();
    },
    { immediate: true }
  );
</script>
