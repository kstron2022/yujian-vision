import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ArcoVue from '@arco-design/web-vue'
import ArcoVueIcon from '@arco-design/web-vue/es/icon'
import App from './App.vue'
import router, { buildDynamicRoutes } from './router'
import { useAuthStore } from './stores/auth'
import { permission } from './directives/permission'
import '@arco-design/web-vue/dist/arco.css'
import '@/assets/style/global.less'
import './assets/main.css'

const app = createApp(App)
const pinia = createPinia()
app.use(pinia)

// 登录态下先拉菜单并注册动态路由, 再挂载:
// 消除刷新/直达子页时 vue-router 首次解析的 R0004 "No match" 告警;
// 失败(token 失效/网络断)不阻断, 由路由守卫兜底跳登录
async function preloadMenus() {
  const auth = useAuthStore(pinia)
  if (!auth.isLoggedIn) return
  try {
    if (!auth.menus.length) await auth.loadMe()
  } catch {
    return
  }
  buildDynamicRoutes(auth.menus)
}
await preloadMenus()

app.use(router)
app.use(ArcoVue)
app.use(ArcoVueIcon)
app.directive('permission', permission)
await router.isReady()
app.mount('#app')
