import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import type { MenuNode } from '@/stores/auth'
import { setRouteEmitter } from '@/utils/route-listener'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { public: true, noAffix: true }
    },
    {
      path: '/change-password',
      name: 'change-password',
      component: () => import('@/views/ChangePasswordView.vue'),
      meta: { noAffix: true }
    },
    {
      path: '/',
      name: 'layout',
      component: () => import('@/layout/default-layout.vue'),
      children: [
        // 空路径子路由必须有名字, 否则父路由带 name 时触发 vue-router R0103 告警
        { path: '', name: 'home', redirect: '/live' },
        {
          path: '/redirect/:path(.*)',
          name: 'Redirect',
          component: () => import('@/views/redirect/index.vue'),
          meta: { noAffix: true }
        },
        {
          path: '/403',
          name: 'forbidden',
          component: () => import('@/views/error/403.vue'),
          meta: { title: '403', noAffix: true }
        }
      ]
    }
  ]
})

let dynamicAdded = false
const dynamicRouteNames: string[] = []

/** 单个页面菜单 → 路由; component 约定 views/{component}.vue; path 由调用方拼好 */
function pushMenuRoute(
  children: RouteRecordRaw[],
  views: Record<string, () => Promise<unknown>>,
  menu: MenuNode,
  path: string
) {
  if (menu.menuType !== 2 || !menu.component) return
  const loader = views[`../views/${menu.component}.vue`]
  if (!loader) return
  children.push({
    path,
    name: `menu-${menu.id}`,
    component: loader as () => Promise<unknown>,
    meta: {
      title: menu.menuName,
      requiresAuth: true,
      // 路由级权限(防御纵深): 菜单行 perm_code 非空则进入前校验
      perm: menu.permCode || undefined,
      // 预览页不缓存: 切走页签即销毁组件并断流(D17 资源最小化)
      ignoreCache: menu.component === 'monitor/live/index',
      // fill 页(工作台形态: 左树右内容): 精确填满视口剩余高度, 左树/右栏在自身区域内滚动,
      // 整页不出滚动条
      fill: ['monitor/live/index', 'record/query/index'].includes(menu.component)
    }
  })
}

/** 后端菜单树 → 动态路由; 顶层支持两种形态: 目录(menuType=1, 挂子页)与一级叶子页(menuType=2, 如实时预览 /live) */
export function buildDynamicRoutes(menus: MenuNode[]) {
  if (dynamicAdded) return
  const views = import.meta.glob('../views/**/*.vue')
  const children: RouteRecordRaw[] = []
  for (const dir of menus) {
    if (dir.menuType === 2) {
      pushMenuRoute(children, views, dir, dir.path)
      continue
    }
    for (const menu of dir.children ?? []) {
      pushMenuRoute(children, views, menu, `${dir.path}/${menu.path}`)
    }
  }
  children.push({
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/error/NotFound.vue'),
    meta: { title: '404', noAffix: true }
  })
  for (const r of children) {
    router.addRoute('layout', r)
    dynamicRouteNames.push(String(r.name))
  }
  dynamicAdded = true
}

/** 退出登录/切换用户时清理动态路由，避免残留上一账号可访问的页面 */
export function clearDynamicRoutes() {
  if (!dynamicAdded) return
  for (const name of dynamicRouteNames) {
    if (router.hasRoute(name)) router.removeRoute(name)
  }
  dynamicRouteNames.length = 0
  dynamicAdded = false
}

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (to.name === 'login' && auth.isLoggedIn) return '/'
  if (to.meta.public) return true
  if (!auth.isLoggedIn) return { name: 'login', query: { redirect: to.fullPath } }
  if (auth.mustChangePwd && to.name !== 'change-password') return { name: 'change-password' }
  if (!dynamicAdded) {
    if (!auth.menus.length) {
      try {
        await auth.loadMe()
      } catch {
        return { name: 'login' }
      }
    }
    buildDynamicRoutes(auth.menus)
    return to.fullPath
  }
  if (to.meta.perm && !auth.hasPerm(to.meta.perm as string)) {
    return { name: 'forbidden' }
  }
  return true
})

// 路由变化广播(页签栏依赖此事件增删 tab); B1 移植时遗漏导致多页签失效
router.afterEach((to) => {
  setRouteEmitter(to)
})

export default router
