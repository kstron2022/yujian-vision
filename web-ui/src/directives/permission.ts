import type { Directive } from 'vue'
import { useAuthStore } from '@/stores/auth'

/**
 * v-permission="'device:play'" 或 v-permission="['a','b']"（任一满足即保留）
 * 无权限时直接移除元素（按钮显隐, docs/03 §2.1）
 */
export const permission: Directive<HTMLElement, string | string[]> = {
  mounted(el, binding) {
    const auth = useAuthStore()
    const need = Array.isArray(binding.value) ? binding.value : [binding.value]
    if (!need.some((code) => auth.hasPerm(code))) {
      el.parentNode?.removeChild(el)
    }
  }
}
