<script setup lang="ts">
  import { computed } from 'vue';

  /**
   * 录像计划 7 天 × 3 时段勾选编辑器（00-08 / 08-16 / 16-24）。
   * 与 weekPlan 7x48 的映射在父组件做（每段 = 16 个 30 分钟格, 7×48）, 本组件只管 7x3 视图。
   */
  const props = defineProps<{ modelValue: boolean[][]; disabled?: boolean }>();
  const emit = defineEmits<{ (e: 'update:modelValue', v: boolean[][]): void }>();

  const DAYS = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];
  const SLOTS = ['00-08', '08-16', '16-24'];

  const grid = computed<boolean[][]>(() =>
    DAYS.map((_, d) => SLOTS.map((_, s) => !!props.modelValue?.[d]?.[s]))
  );

  function emitGrid(next: boolean[][]) {
    emit('update:modelValue', next);
  }

  function setCell(d: number, s: number, v: boolean) {
    const next = grid.value.map((row) => row.slice());
    next[d]![s] = v;
    emitGrid(next);
  }

  function setDay(d: number, v: boolean) {
    const next = grid.value.map((row) => row.slice());
    next[d] = SLOTS.map(() => v);
    emitGrid(next);
  }

  function setAll(v: boolean) {
    emitGrid(DAYS.map(() => SLOTS.map(() => v)));
  }
</script>

<template>
  <div class="wp" :class="{ disabled }">
    <div class="wp-row wp-head">
      <span class="wp-day" />
      <span v-for="s in SLOTS" :key="s" class="wp-slot">{{ s }}</span>
      <span class="wp-all wp-all-head">
        <a-space :size="6">
          <a-link :disabled="disabled" class="wp-fill" @click="setAll(true)">全天×7天</a-link>
          <a-link :disabled="disabled" class="wp-fill" @click="setAll(false)">清空</a-link>
        </a-space>
      </span>
    </div>
    <div v-for="(day, d) in DAYS" :key="day" class="wp-row">
      <span class="wp-day">{{ day }}</span>
      <span v-for="(slot, s) in SLOTS" :key="slot" class="wp-slot">
        <a-checkbox
          :model-value="grid[d]?.[s]"
          :disabled="disabled"
          @change="(v: unknown) => setCell(d, s, v === true)"
        />
      </span>
      <span class="wp-all">
        <a-checkbox
          :model-value="grid[d]?.every(Boolean)"
          :indeterminate="grid[d]?.some(Boolean) && !grid[d]?.every(Boolean)"
          :disabled="disabled"
          @change="(v: unknown) => setDay(d, v === true)"
        />
      </span>
    </div>
  </div>
</template>

<style scoped lang="less">
  .wp {
    display: flex;
    flex-direction: column;
    gap: 2px;
    padding: 8px 10px;
    background: var(--color-fill-1);
    border-radius: 4px;

    &.disabled {
      opacity: 0.5;
    }
  }

  .wp-row {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .wp-head {
    .wp-slot,
    .wp-all {
      font-size: 11px;
      color: var(--color-text-3);
      justify-content: center;
    }
  }

  .wp-day {
    width: 34px;
    flex-shrink: 0;
    font-size: 12px;
    color: var(--color-text-2);
  }

  .wp-slot {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 56px;
  }

  .wp-all {
    display: inline-flex;
    align-items: center;
    justify-content: flex-end;
    margin-left: auto;
  }

  .wp-all-head {
    padding-right: 0;
  }
</style>
