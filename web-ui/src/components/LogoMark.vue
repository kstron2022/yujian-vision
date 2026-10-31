<script setup lang="ts">
/** 煜见视觉 logo：盾形轮廓 + 光圈瞳孔 + 扫描波（蓝青渐变, 科技感） */
withDefaults(defineProps<{ size?: number }>(), { size: 32 })
</script>

<template>
  <svg :width="size" :height="size" viewBox="0 0 48 48" fill="none" xmlns="http://www.w3.org/2000/svg">
    <defs>
      <linearGradient id="yjv-logo-g" x1="6" y1="4" x2="42" y2="44" gradientUnits="userSpaceOnUse">
        <stop offset="0" stop-color="#165DFF" />
        <stop offset="1" stop-color="#14C9C9" />
      </linearGradient>
    </defs>
    <path d="M24 3 L41 12.5 V32 L24 45 L7 32 V12.5 Z" fill="url(#yjv-logo-g)" opacity="0.12" />
    <path d="M24 3 L41 12.5 V32 L24 45 L7 32 V12.5 Z" stroke="url(#yjv-logo-g)"
      stroke-width="2.6" stroke-linejoin="round" fill="none" />
    <circle cx="24" cy="22" r="7.5" stroke="url(#yjv-logo-g)" stroke-width="2.6" fill="none" />
    <circle cx="24" cy="22" r="3" fill="url(#yjv-logo-g)" />
    <path d="M13 33.5 Q24 40.5 35 33.5" stroke="url(#yjv-logo-g)" stroke-width="2.6"
      stroke-linecap="round" fill="none" />
  </svg>
</template>
