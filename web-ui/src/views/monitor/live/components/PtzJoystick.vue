<script setup lang="ts">
  import { computed, ref, watch } from 'vue';
  import { Message, Modal } from '@arco-design/web-vue';
  import { ptzApi } from '@/api/ptz';
  import type { PtzCommand, PtzPreset } from '@/api/ptz';
  import { useAuthStore } from '@/stores/auth';

  /**
   * 云台控制面板: 摇杆 4 向拖动 + 变倍/聚焦/光圈按住发送 + 预置位折叠区(B3)。
   * 连续指令 200ms 节流重发（设备丢包兜底）, 松开即发 STOP。
   */
  const props = defineProps<{ channelDbId?: number | null }>();
  const emit = defineEmits<{
    (e: 'move', command: PtzCommand, speed: number): void;
  }>();

  const auth = useAuthStore();
  const speed = ref(50);
  const padRef = ref<HTMLElement>();
  const currentDir = ref<PtzCommand | null>(null);

  // ---------- 预置位（ptz:control 语义下可见; 加载失败不影响摇杆） ----------
  const canPreset = computed(() => auth.hasPerm('ptz:control') && !!props.channelDbId);
  const presetKeys = ref<string[]>([]);
  const presets = ref<PtzPreset[]>([]);
  const presetLoading = ref(false);
  const presetLoaded = ref(false);
  const presetNum = ref(1);
  const presetBusy = ref(false);

  /** 预置位号取自设备应答的 PresetID（字符串）, 非法时不可调用/删除 */
  function numOf(p: PtzPreset): number {
    const n = Number(p.presetId);
    return Number.isInteger(n) && n >= 1 && n <= 255 ? n : 0;
  }

  async function loadPresets() {
    if (!props.channelDbId) return;
    presetLoading.value = true;
    try {
      presets.value = await ptzApi.presetList(props.channelDbId);
      presetLoaded.value = true;
    } catch (e) {
      // 设备不支持(1011)/离线(1001)等: 提示一次并降级为空列表, 不阻塞摇杆; 重新展开可重试
      Message.warning((e as Error).message || '预置位查询失败');
      presets.value = [];
      presetLoaded.value = false;
    } finally {
      presetLoading.value = false;
    }
  }

  watch(presetKeys, (v) => {
    if (v.length && !presetLoaded.value && !presetLoading.value) void loadPresets();
  });

  watch(
    () => props.channelDbId,
    () => {
      // 换通道: 收起并丢弃上一路预置位, 首次展开再查
      presetKeys.value = [];
      presets.value = [];
      presetLoaded.value = false;
    }
  );

  async function savePreset() {
    if (!props.channelDbId) return;
    const num = presetNum.value;
    presetBusy.value = true;
    try {
      await ptzApi.presetSet(props.channelDbId, num);
      Message.success(`已将当前画面保存到预置位 ${num}`);
      await loadPresets();
    } finally {
      presetBusy.value = false;
    }
  }

  function gotoPreset(p: PtzPreset) {
    if (!props.channelDbId) return;
    const num = numOf(p);
    if (!num) {
      Message.warning(`预置位号无效: ${p.presetId}`);
      return;
    }
    void ptzApi.presetGoto(props.channelDbId, num).catch(() => {});
  }

  function removePreset(p: PtzPreset) {
    if (!props.channelDbId) return;
    const num = numOf(p);
    if (!num) {
      Message.warning(`预置位号无效: ${p.presetId}`);
      return;
    }
    Modal.confirm({
      title: '删除预置位',
      content: `确认删除预置位 ${num}（${p.presetName || '未命名'}）?`,
      okText: '删除',
      cancelText: '取消',
      okButtonProps: { status: 'danger' },
      onOk: async () => {
        await ptzApi.presetDelete(props.channelDbId!, num);
        Message.success('预置位已删除');
        await loadPresets();
      }
    });
  }


  let lastSent = 0;
  let repeatTimer: number | undefined;

  function send(cmd: PtzCommand) {
    lastSent = Date.now();
    emit('move', cmd, speed.value);
  }

  function dirFromEvent(e: PointerEvent): PtzCommand {
    const rect = padRef.value!.getBoundingClientRect();
    const dx = e.clientX - (rect.left + rect.width / 2);
    const dy = e.clientY - (rect.top + rect.height / 2);
    return Math.abs(dx) >= Math.abs(dy)
      ? dx >= 0
        ? 'RIGHT'
        : 'LEFT'
      : dy >= 0
        ? 'DOWN'
        : 'UP';
  }

  function press(e: PointerEvent) {
    padRef.value?.setPointerCapture(e.pointerId);
    apply(dirFromEvent(e));
  }

  function move(e: PointerEvent) {
    if (!currentDir.value) return;
    apply(dirFromEvent(e));
  }

  function apply(dir: PtzCommand) {
    const changed = dir !== currentDir.value;
    currentDir.value = dir;
    if (changed || Date.now() - lastSent >= 200) {
      send(dir);
      window.clearInterval(repeatTimer);
      repeatTimer = window.setInterval(() => send(dir), 200);
    }
  }

  function release() {
    window.clearInterval(repeatTimer);
    repeatTimer = undefined;
    if (currentDir.value) {
      currentDir.value = null;
      send('STOP');
    }
  }

  /** 按住发送型按钮（变倍/聚焦/光圈）: 按下发指令, 松开发 STOP。
   *  pointerdown 即捕获指针——拖出按钮再松开也能收到 pointerup, 不会卡在持续指令 */
  function hold(cmd: PtzCommand) {
    return {
      // v-on 对象语法的键不能带 on 前缀: toHandlerKey 会再加一次 on 变成 onOnPointerdown,
      // 事件绑不上导致按钮点击无任何请求(2026-09-25 现场实证, M2-B3 起即坏)
      pointerdown: (e: PointerEvent) => {
        (e.currentTarget as HTMLElement | null)?.setPointerCapture?.(e.pointerId);
        send(cmd);
      },
      pointerup: () => send('STOP'),
      pointercancel: () => send('STOP')
    };
  }
</script>

<template>
  <div class="ptz-panel">
    <div class="ptz-speed">
      <span class="ptz-label">速度</span>
      <a-slider v-model="speed" :min="1" :max="100" :format-tooltip="() => `${speed}%`" />
      <span class="ptz-speed-num">{{ speed }}</span>
    </div>

    <div class="ptz-pad-row">
      <div
        ref="padRef"
        class="ptz-pad"
        @pointerdown="press"
        @pointermove="move"
        @pointerup="release"
        @pointercancel="release"
        @contextmenu.prevent
      >
        <span class="ptz-center" />
        <span class="ptz-arrow arrow-up" :class="{ active: currentDir === 'UP' }">▲</span>
        <span class="ptz-arrow arrow-down" :class="{ active: currentDir === 'DOWN' }">▼</span>
        <span class="ptz-arrow arrow-left" :class="{ active: currentDir === 'LEFT' }">◀</span>
        <span class="ptz-arrow arrow-right" :class="{ active: currentDir === 'RIGHT' }">▶</span>
      </div>
    </div>

    <div class="ptz-groups">
      <div class="ptz-group">
        <span class="ptz-label">变倍</span>
        <a-button size="mini" v-on="hold('ZOOM_IN')">＋</a-button>
        <a-button size="mini" v-on="hold('ZOOM_OUT')">－</a-button>
      </div>
      <div class="ptz-group">
        <span class="ptz-label">聚焦</span>
        <a-button size="mini" v-on="hold('FOCUS_NEAR')">近</a-button>
        <a-button size="mini" v-on="hold('FOCUS_FAR')">远</a-button>
      </div>
      <div class="ptz-group">
        <span class="ptz-label">光圈</span>
        <a-button size="mini" v-on="hold('IRIS_OPEN')">开</a-button>
        <a-button size="mini" v-on="hold('IRIS_CLOSE')">关</a-button>
      </div>
    </div>

    <a-collapse
      v-if="canPreset"
      v-model:active-key="presetKeys"
      :bordered="false"
      class="preset-collapse"
    >
      <a-collapse-item name="preset" header="预置位">
        <template #header><span class="preset-head">预置位</span></template>
        <div class="preset-add">
          <a-input-number
            v-model="presetNum"
            :min="1"
            :max="255"
            size="mini"
            style="width: 76px"
          />
          <a-button size="mini" type="primary" :loading="presetBusy" @click="savePreset">
            设置到当前位
          </a-button>
        </div>

        <div v-if="presetLoading" class="preset-empty">查询中…</div>
        <div v-else-if="!presets.length" class="preset-empty">设备无预置位</div>
        <div v-else class="preset-list">
          <div v-for="p in presets" :key="p.presetId" class="preset-row">
            <span class="preset-name" :title="p.presetName">
              <b>{{ numOf(p) || '?' }}</b>
              {{ p.presetName || '未命名' }}
            </span>
            <a-space :size="0">
              <a-button size="mini" type="text" @click="gotoPreset(p)">调用</a-button>
              <a-button size="mini" type="text" status="danger" @click="removePreset(p)">
                <template #icon><icon-delete /></template>
              </a-button>
            </a-space>
          </div>
        </div>
      </a-collapse-item>
    </a-collapse>
  </div>
</template>

<style scoped lang="less">
  .ptz-panel {
    width: 220px;
    user-select: none;
  }

  .ptz-speed {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 8px;
  }

  .ptz-speed :deep(.arco-slider) {
    flex: 1;
  }

  .ptz-speed-num {
    width: 28px;
    font-size: 12px;
    color: var(--color-text-2);
    text-align: right;
  }

  .ptz-label {
    font-size: 12px;
    color: var(--color-text-3);
  }

  .ptz-pad-row {
    display: flex;
    justify-content: center;
    margin-bottom: 12px;
  }

  .ptz-pad {
    position: relative;
    width: 140px;
    height: 140px;
    background: var(--color-fill-2);
    border: 1px solid var(--color-border);
    border-radius: 50%;
    touch-action: none;
    cursor: move;

    .ptz-center {
      position: absolute;
      top: 50%;
      left: 50%;
      width: 10px;
      height: 10px;
      background: var(--color-text-4);
      border-radius: 50%;
      transform: translate(-50%, -50%);
    }

    .ptz-arrow {
      position: absolute;
      font-size: 14px;
      color: var(--color-text-3);
      transition: color 0.15s;

      &.active {
        color: rgb(var(--arcoblue-6));
      }
    }

    .arrow-up {
      top: 10px;
      left: 50%;
      transform: translateX(-50%);
    }

    .arrow-down {
      bottom: 10px;
      left: 50%;
      transform: translateX(-50%);
    }

    .arrow-left {
      left: 10px;
      top: 50%;
      transform: translateY(-50%);
    }

    .arrow-right {
      right: 10px;
      top: 50%;
      transform: translateY(-50%);
    }
  }

  .ptz-groups {
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  .ptz-group {
    display: flex;
    align-items: center;
    gap: 8px;

    .ptz-label {
      width: 28px;
    }
  }

  .preset-collapse {
    margin: 4px -8px 0;

    :deep(.arco-collapse-item-content) {
      background: transparent;
      padding: 0;
    }

    :deep(.arco-collapse-item-content-box) {
      padding: 4px 8px 8px;
    }

    .preset-head {
      font-size: 12px;
      color: var(--color-text-2);
    }
  }

  .preset-add {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 8px;
  }

  .preset-empty {
    padding: 8px 0;
    font-size: 12px;
    color: var(--color-text-3);
    text-align: center;
  }

  .preset-list {
    max-height: 180px;
    overflow: auto;

    .preset-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 8px;
      padding: 2px 0;

      .preset-name {
        flex: 1;
        min-width: 0;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        font-size: 12px;
        color: var(--color-text-1);

        b {
          margin-right: 4px;
          color: rgb(var(--arcoblue-6));
        }
      }
    }
  }
</style>
