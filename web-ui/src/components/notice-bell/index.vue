<script setup lang="ts">
  import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
  import { Message } from '@arco-design/web-vue';
  import { listNotices, markAllRead, markRead, unreadCount } from '@/api/notice';
  import type { NoticeItem } from '@/api/notice';
  import { WsEvent, wsClient } from '@/utils/ws';
  import { fmtDateTime } from '@/utils/format';

  /**
   * B1 消息中心: 导航栏铃铛 + 右侧抽屉消息列表。
   * 角标: 挂载即拉取未读数, 60s 轮询兜底, WS NOTICE_NEW 实时 +1;
   * 已读: 点未读条目即标记（本地去重防重复请求）, 抽屉 footer 支持"全部已读"。
   */
  const PAGE_COUNT = 20;

  const visible = ref(false);
  const unread = ref(0);
  const list = ref<NoticeItem[]>([]);
  const total = ref(0);
  const loading = ref(false);
  const markingAll = ref(false);

  let timer: number | undefined;
  let unbindWs: (() => void) | undefined;
  /** 本会话已发过已读请求的消息 id（防重复提交） */
  const readSet = new Set<number>();

  const hasMore = computed(() => list.value.length < total.value);

  async function refreshUnread() {
    try {
      unread.value = await unreadCount();
    } catch {
      // 轮询场景不弹错（后端中断有全局提示条）
    }
  }

  async function loadList() {
    loading.value = true;
    try {
      const data = await listNotices({ page: 1, count: PAGE_COUNT });
      list.value = data.list;
      total.value = data.total;
    } finally {
      loading.value = false;
    }
    void refreshUnread();
  }

  async function loadMore() {
    if (!hasMore.value || loading.value) return;
    loading.value = true;
    try {
      const data = await listNotices({ page: Math.floor(list.value.length / PAGE_COUNT) + 1, count: PAGE_COUNT });
      const seen = new Set(list.value.map((i) => i.id));
      list.value.push(...data.list.filter((i) => !seen.has(i.id)));
      total.value = data.total;
    } finally {
      loading.value = false;
    }
  }

  /** 点击未读条目 → 标记已读并本地置已读（角标 -1） */
  async function onClickItem(item: NoticeItem) {
    if (item.readTime || readSet.has(item.id)) return;
    readSet.add(item.id);
    try {
      await markRead(item.id);
    } catch {
      readSet.delete(item.id);
      return; // 失败提示已由拦截器弹出
    }
    item.readTime = fmtDateTime(Date.now());
    unread.value = Math.max(0, unread.value - 1);
  }

  async function onMarkAll() {
    if (unread.value === 0 || markingAll.value) return;
    markingAll.value = true;
    try {
      await markAllRead();
      const now = fmtDateTime(Date.now());
      for (const item of list.value) {
        if (!item.readTime) item.readTime = now;
      }
      readSet.clear();
      unread.value = 0;
      Message.success('已全部标为已读');
    } finally {
      markingAll.value = false;
    }
  }

  /** WS 新消息: 角标 +1; 抽屉打开时头部插入一条未读（payload 不含 content） */
  function onWs(type: string, data: Record<string, unknown>) {
    if (type !== WsEvent.NoticeNew) return;
    unread.value += 1;
    if (visible.value) {
      list.value.unshift({
        id: Number(data.id ?? 0),
        type: String(data.type ?? ''),
        title: String(data.title ?? ''),
        content: '',
        level: Number(data.level ?? 1),
        bizId: '',
        readTime: null,
        createTime: String(data.createTime ?? '')
      });
      total.value += 1;
    }
  }

  watch(visible, (v) => {
    if (v) void loadList();
  });

  onMounted(() => {
    void refreshUnread();
    timer = window.setInterval(() => void refreshUnread(), 60_000);
    unbindWs = wsClient.on(onWs);
  });

  onUnmounted(() => {
    window.clearInterval(timer);
    unbindWs?.();
  });
</script>

<template>
  <a-badge :count="unread">
    <a-button class="bell-btn" type="text" @click="visible = true">
      <template #icon><icon-notification /></template>
    </a-button>
  </a-badge>

  <a-drawer
    :visible="visible"
    :width="560"
    unmount-on-close
    @cancel="visible = false"
    @close="visible = false"
  >
    <template #title>
      <span class="dlg-title">
        <icon-notification />
        消息中心
      </span>
    </template>

    <div class="notice-list">
      <a-spin v-if="loading && !list.length" class="list-loading" />
      <template v-else>
        <div v-if="!list.length" class="list-empty">
          <a-empty description="暂无消息" />
        </div>
        <div
          v-for="item in list"
          :key="item.id"
          class="notice-item"
          :class="{ unread: !item.readTime }"
          @click="onClickItem(item)"
        >
          <div class="notice-title">
            <span v-if="!item.readTime" class="dot" />
            <span class="title-text">{{ item.title }}</span>
            <a-tag v-if="item.level === 2" size="small" color="orangered">重要</a-tag>
          </div>
          <div v-if="item.content" class="notice-content">{{ item.content }}</div>
          <div class="notice-time">{{ fmtDateTime(item.createTime) }}</div>
        </div>
        <div v-if="hasMore" class="load-more" @click="loadMore">加载更多</div>
      </template>
    </div>

    <template #footer>
      <a-button block :loading="markingAll" :disabled="unread === 0" @click="onMarkAll">
        全部已读
      </a-button>
    </template>
  </a-drawer>
</template>

<style scoped lang="less">
  .bell-btn {
    font-size: 16px;
    color: var(--color-text-2);
  }

  .notice-list {
    .list-loading {
      display: block;
      padding: 32px 0;
      text-align: center;
    }

    .list-empty {
      padding: 32px 0;
    }

    .notice-item {
      padding: 10px 12px;
      border-radius: 4px;
      cursor: pointer;

      &:hover {
        background: var(--color-fill-2);
      }

      & + .notice-item {
        margin-top: 4px;
      }

      .notice-title {
        display: flex;
        align-items: center;
        gap: 6px;

        .dot {
          width: 6px;
          height: 6px;
          flex-shrink: 0;
          border-radius: 50%;
          background: rgb(var(--arcoblue-6));
        }

        .title-text {
          flex: 1;
          min-width: 0;
          overflow: hidden;
          white-space: nowrap;
          text-overflow: ellipsis;
          font-weight: 400;
        }

        :deep(.arco-tag) {
          flex-shrink: 0;
        }
      }

      &.unread .title-text {
        font-weight: 500;
        color: var(--color-text-1);
      }

      .notice-content {
        margin: 4px 0 0 12px;
        font-size: 12px;
        line-height: 18px;
        color: var(--color-text-3);
        // 最多两行省略
        display: -webkit-box;
        -webkit-box-orient: vertical;
        -webkit-line-clamp: 2;
        overflow: hidden;
      }

      .notice-time {
        margin: 4px 0 0 12px;
        font-size: 11px;
        color: var(--color-text-4);
      }
    }

    .load-more {
      padding: 10px 0 2px;
      font-size: 13px;
      text-align: center;
      color: rgb(var(--arcoblue-6));
      cursor: pointer;
    }
  }
</style>
