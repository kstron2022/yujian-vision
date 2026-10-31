<script setup lang="ts">
  import { computed, ref, watch } from 'vue';
  import { Message } from '@arco-design/web-vue';
  import { deviceApi, SNAPSHOT_SOURCE_COLOR, SNAPSHOT_SOURCE_TEXT } from '@/api/device';
  import type { ChannelSnapshot, GbChannel } from '@/api/device';
  import { errorTextOf } from '@/api/http';
  import { fmtDateTime } from '@/utils/format';
  import { useAuthStore } from '@/stores/auth';

  /**
   * 通道截图预览（M3-B7 阶段3）: 列表缩略图/占位点击 → 大图 + 抓图时间/来源 + 重新抓图。
   *
   * 图是服务端抓的（非浏览器画布截图）, url/thumbUrl 自带签名可直接作 img src。
   * 重新抓图走 POST /api/channels/{id}/snapshot: 有流则复用当前流, 无流且未开空闲抓图 → 1014。
   * 本组件不预判"通道是否有流"（列表接口不返回该信息）, 一律直接调, 按后端业务码给处置指引。
   */
  const props = defineProps<{
    visible: boolean;
    channel: GbChannel | null;
  }>();

  const emit = defineEmits<{
    (e: 'update:visible', v: boolean): void;
    /** 带上 id: 抓图可能耗时 20s+, 期间用户可能已关窗/切到别的行, 由父级按 id 派发才不会错挂 */
    (e: 'updated', payload: { id: number; snapshot: ChannelSnapshot }): void;
  }>();

  const auth = useAuthStore();
  /** 抓图与点播同权限（后端 ChannelSnapshotController 是 device:play + 通道数据权限）。
   *  看图不需要——取图 GET 是签名 URL 免登录, 所以只藏按钮、不藏列。 */
  const canCapture = computed(() => auth.hasPerm('device:play'));

  const current = ref<ChannelSnapshot | null>(null);
  const capturing = ref(false);
  /** 大图加载失败（签名过期/文件被清理任务删）→ 遮罩提示而不是破图 */
  const imgBroken = ref(false);
  /** 抓图失败的处置指引（拦截器已弹过后端文案, 这里只做上下文内的补充说明） */
  const hint = ref('');

  const title = computed(() => props.channel?.customName || props.channel?.name || '未知通道');
  const sourceText = computed(
    () => SNAPSHOT_SOURCE_TEXT[current.value?.source ?? ''] ?? current.value?.source ?? '-'
  );
  const sourceColor = computed(
    () => SNAPSHOT_SOURCE_COLOR[current.value?.source ?? ''] ?? 'gray'
  );

  /** 抽屉每次打开都从行数据重新取值（列表刷新后行上的 snapshot 才是最新的）;
   *  关闭即清空, 不把签名 URL 与已解码位图留在组件内存里 */
  watch(
    () => props.visible,
    (v) => {
      if (v) {
        current.value = props.channel?.snapshot ?? null;
        imgBroken.value = false;
        hint.value = '';
      } else {
        current.value = null;
      }
    }
  );

  function close() {
    emit('update:visible', false);
  }

  async function recapture() {
    const ch = props.channel;
    if (!ch) return;
    capturing.value = true;
    hint.value = '';
    imgBroken.value = false;
    try {
      const snap = await deviceApi.captureSnapshot(ch.id);
      // 响应到达时可能已经换到别的通道（抓图最坏 20s+, 用户能关窗再点另一行）:
      // 不复验就会把 A 的画面当成 B 显示在弹窗和列表行上——监控场景下这是可信度事故
      if (props.channel?.id !== ch.id) return;
      current.value = snap;
      emit('updated', { id: ch.id, snapshot: snap });
      Message.success('抓图完成');
    } catch (e) {
      if (props.channel?.id !== ch.id) return;
      // 手动抓图后端的 allowIdle=true: idleEnabled 关时无流回 1014, 开时建流失败/超时回 1013
      // （1014+idleEnabled=true 只出现在自动抓图路径, 这里不会命中）
      const code = (e as { code?: number }).code;
      hint.value =
        code === 1014
          ? '该通道当前无流。可先在本页点「播放」起流后再抓图，或让管理员开启服务端空闲抓图开关。'
          : code === 1013
            ? '抓图失败：临时建流未成功（设备离线或媒体节点无响应）。请确认设备在线后重试。'
            : errorTextOf(e, '抓图失败，请稍后重试');
    } finally {
      capturing.value = false;
    }
  }
</script>

<template>
  <a-modal
    :visible="visible"
    :width="720"
    :mask-closable="false"
    unmount-on-close
    @cancel="close"
    @close="close"
  >
    <template #title>
      <span class="dlg-title">
        <icon-camera />
        通道截图 · {{ title }}
      </span>
    </template>

    <!-- 大图区: 定宽 16:9, 与回放区同款黑底 -->
    <div class="snap-stage">
      <img v-if="current && !imgBroken" class="snap-img" :src="current.url" alt="通道截图" @error="imgBroken = true" />

      <div v-else class="snap-empty">
        <icon-image-close v-if="imgBroken" :size="30" />
        <icon-camera v-else :size="30" />
        <span>{{ imgBroken ? '截图已失效（签名过期或被清理），请重新抓图' : '该通道还没有截图' }}</span>
      </div>

      <div v-if="capturing" class="snap-mask">
        <a-spin :size="20" />
        <span>正在抓图…（通道无流时需先临时建流，最长约 23 秒）</span>
      </div>
    </div>

    <!-- 元信息: 抓图时间 + 来源 -->
    <div class="snap-meta">
      <template v-if="current && !imgBroken">
        <span class="snap-meta-item">
          <icon-clock-circle />
          {{ fmtDateTime(current.time) }}
        </span>
        <a-tag size="small" :color="sourceColor">{{ sourceText }}</a-tag>
      </template>
      <span v-else class="snap-meta-item snap-meta-muted">尚无截图信息</span>
    </div>

    <a-alert v-if="hint" type="warning" class="snap-hint">{{ hint }}</a-alert>

    <!-- footer: 规范要求弹窗有独立的 footer 区（按钮不跟内容滚动） -->
    <template #footer>
      <span v-if="!canCapture" class="snap-noperm">当前账号无抓图权限（device:play）</span>
      <a-button v-else type="primary" :loading="capturing" @click="recapture">
        <template #icon><icon-refresh /></template>
        {{ current ? '重新抓图' : '立即抓图' }}
      </a-button>
      <a-button @click="close">关 闭</a-button>
    </template>
  </a-modal>
</template>

<style scoped lang="less">
  .snap-stage {
    position: relative;
    display: flex;
    align-items: center;
    justify-content: center;
    width: 100%;
    aspect-ratio: 16 / 9;
    overflow: hidden;
    background: #000;
    border-radius: 4px;

    .snap-img {
      width: 100%;
      height: 100%;
      object-fit: contain;
    }

    .snap-empty {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 10px;
      font-size: 13px;
      color: rgba(255, 255, 255, 0.65);
    }

    .snap-mask {
      position: absolute;
      inset: 0;
      z-index: 2;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: 10px;
      padding: 0 24px;
      font-size: 13px;
      color: #fff;
      text-align: center;
      background: rgba(0, 0, 0, 0.55);
    }
  }

  .snap-meta {
    display: flex;
    align-items: center;
    gap: 10px;
    min-height: 24px;
    margin-top: 10px;
    font-size: 13px;

    .snap-meta-item {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      color: var(--color-text-2);
    }

    .snap-meta-muted {
      color: var(--color-text-3);
    }
  }

  .snap-hint {
    margin-top: 10px;
  }

  /* 无 device:play 时的替代说明: 占住按钮位, footer 布局不塌 */
  .snap-noperm {
    margin-right: 12px;
    font-size: 13px;
    color: var(--color-text-3);
  }
</style>
