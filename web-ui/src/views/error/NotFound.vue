<template>
  <a-card :bordered="false">
    <a-result status="404" subtitle="页面不存在或无访问权限">
      <template #extra>
        <a-button type="primary" @click="$router.push('/')">返回首页</a-button>
      </template>
    </a-result>
  </a-card>
</template>
