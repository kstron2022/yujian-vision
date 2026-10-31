<template>
  <a-card :bordered="false">
    <a-result status="403" subtitle="无权限访问该页面">
      <template #extra>
        <a-button type="primary" @click="$router.push('/')">返回首页</a-button>
      </template>
    </a-result>
  </a-card>
</template>
