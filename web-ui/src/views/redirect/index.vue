<script lang="ts" setup>
  import { watch } from 'vue';
  import { useRoute, useRouter } from 'vue-router';

  const route = useRoute();
  const router = useRouter();

  // tab「重新加载」中转: /redirect/<fullPath> → replace 回目标页
  watch(
    () => route.params.path,
    () => {
      if (route.params.path) {
        const raw = String(route.params.path);
        router.replace({ path: raw.startsWith('/') ? raw : `/${raw}`, query: route.query });
      }
    },
    { immediate: true }
  );
</script>

<template>
  <div></div>
</template>
