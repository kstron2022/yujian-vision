<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Message } from '@arco-design/web-vue'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const form = reactive({ oldPassword: '', newPassword: '', confirm: '' })
const loading = ref(false)

const complexity = (v: string) => /^(?=.*[A-Za-z])(?=.*\d).{8,}$/.test(v)

async function submit() {
  if (!form.oldPassword || !form.newPassword) {
    Message.warning('请填写完整')
    return
  }
  if (!complexity(form.newPassword)) {
    Message.warning('新密码需至少8位且同时包含字母和数字')
    return
  }
  if (form.newPassword !== form.confirm) {
    Message.warning('两次输入的新密码不一致')
    return
  }
  loading.value = true
  try {
    await auth.changePassword(form.oldPassword, form.newPassword)
    Message.success('密码已修改，请重新登录')
    router.replace({ name: 'login' })
  } finally {
    loading.value = false
  }
}

function skip() {
  auth.reset()
  router.replace({ name: 'login' })
}
</script>

<template>
  <div class="page">
    <a-card class="card" :bordered="false">
      <a-alert type="warning" class="tip">
        首次登录（或密码被重置后）必须修改初始密码，修改成功后需重新登录。
      </a-alert>
      <a-form :model="form" layout="vertical" @submit-success="submit">
        <a-form-item field="oldPassword" label="原密码">
          <a-input-password v-model="form.oldPassword" placeholder="原密码" />
        </a-form-item>
        <a-form-item field="newPassword" label="新密码（至少8位，含字母和数字）">
          <a-input-password v-model="form.newPassword" placeholder="新密码" />
        </a-form-item>
        <a-form-item field="confirm" label="确认新密码">
          <a-input-password v-model="form.confirm" placeholder="再次输入新密码" />
        </a-form-item>
        <a-space direction="vertical" fill style="width: 100%">
          <a-button type="primary" html-type="submit" long :loading="loading">确认修改</a-button>
          <a-button long @click="skip">退出登录</a-button>
        </a-space>
      </a-form>
    </a-card>
  </div>
</template>

<style scoped>
.page { height: 100vh; display: flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, #1677ff15 0%, #722ed115 100%), var(--color-bg-1); }
.card { width: 420px; border-radius: 8px; }
.tip { margin-bottom: 20px; }
</style>
