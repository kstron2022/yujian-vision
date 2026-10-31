<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Message } from '@arco-design/web-vue'
import { useAuthStore } from '@/stores/auth'

/**
 * 修改密码弹窗（全局）: 右上角头像下拉入口, 替代原跳独立页。
 * 首次登录强制改密仍走 /change-password 独立页（门禁语义: 登录后进系统前阻断一切导航,
 * 且该阶段主布局未加载, 弹窗无宿主）——两场景表单逻辑同源, 仅载体不同。
 */
const props = defineProps<{ visible: boolean }>()
const emit = defineEmits<{ (e: 'update:visible', v: boolean): void }>()

const auth = useAuthStore()
const router = useRouter()
const form = reactive({ oldPassword: '', newPassword: '', confirm: '' })
const loading = ref(false)

const complexity = (v: string) => /^(?=.*[A-Za-z])(?=.*\d).{8,}$/.test(v)

// 每次打开都清空上一次的输入, 不残留密码明文
watch(
  () => props.visible,
  v => {
    if (v) {
      form.oldPassword = ''
      form.newPassword = ''
      form.confirm = ''
    }
  }
)

function close() {
  emit('update:visible', false)
}

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
    // changePassword 成功后内部 reset() 登出（后端改密即全量下线）, 回登录页重登
    await auth.changePassword(form.oldPassword, form.newPassword)
    close()
    Message.success('密码已修改，请重新登录')
    router.replace({ name: 'login' })
  } catch {
    // 失败提示(原密码错误等)由 http 拦截器弹出, 弹窗保持打开可重试
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <a-modal :visible="visible" :width="440" :mask-closable="false" unmount-on-close @cancel="close" @close="close">
    <template #title>
      <span class="dlg-title">
        <icon-lock />
        修改密码
      </span>
    </template>

    <a-form :model="form" layout="vertical">
      <a-form-item field="oldPassword" label="原密码">
        <a-input-password v-model="form.oldPassword" placeholder="原密码" />
      </a-form-item>
      <a-form-item field="newPassword" label="新密码" help="至少8位，且同时包含字母和数字">
        <a-input-password v-model="form.newPassword" placeholder="新密码" />
      </a-form-item>
      <a-form-item field="confirm" label="确认新密码">
        <a-input-password v-model="form.confirm" placeholder="再次输入新密码" />
      </a-form-item>
    </a-form>

    <template #footer>
      <a-button @click="close">取 消</a-button>
      <a-button type="primary" :loading="loading" @click="submit">确认修改</a-button>
    </template>
  </a-modal>
</template>
