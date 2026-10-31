<template>
  <transition name="backend-status">
    <div
      v-if="backendDown"
      class="backend-status"
      title="点击立即重试"
      @click="probeNow"
    >
      <icon-loading spin class="status-icon" />
      <span>服务连接已断开, 正在自动重试…</span>
    </div>
  </transition>
</template>

<script lang="ts" setup>
  // 全局后端可达性提示条: http 网关失败/WS 异常断开置位, 探测成功自动消失
  import { backendDown, probeNow } from '@/utils/backend-health';
</script>

<style scoped lang="less">
  .backend-status {
    position: fixed;
    top: 68px;
    left: 50%;
    z-index: 200;
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 6px 18px;
    color: rgb(var(--red-6));
    font-size: 13px;
    background: rgb(var(--red-1));
    border: 1px solid rgb(var(--red-3));
    border-radius: 999px;
    box-shadow: 0 4px 10px rgb(0 0 0 / 10%);
    transform: translateX(-50%);
    cursor: pointer;
    user-select: none;

    .status-icon {
      color: rgb(var(--red-6));
    }
  }

  .backend-status-enter-active,
  .backend-status-leave-active {
    transition:
      opacity 0.25s,
      transform 0.25s;
  }

  .backend-status-enter-from,
  .backend-status-leave-to {
    opacity: 0;
    transform: translateX(-50%) translateY(-8px);
  }
</style>
