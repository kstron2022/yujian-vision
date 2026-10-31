import { defineStore } from 'pinia'
import { get, post, put } from '@/api/http'

export interface UserInfo {
  id: number
  username: string
  nickname: string
  mustChangePwd: number
}

export interface MenuNode {
  id: number
  parentId: number
  menuName: string
  menuType: number
  path: string
  component: string
  permCode: string
  icon: string
  sortOrder: number
  children: MenuNode[]
}

interface LoginPayload {
  token: string | null
  user: UserInfo
  roles: string[]
  perms: string[]
  menus: MenuNode[]
}

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('vi.token') || '',
    user: null as UserInfo | null,
    roles: [] as string[],
    perms: [] as string[],
    menus: [] as MenuNode[]
  }),
  getters: {
    isLoggedIn: (s) => !!s.token,
    mustChangePwd: (s) => s.user?.mustChangePwd === 1,
    hasPerm: (s) => (code: string) => s.perms.includes(code)
  },
  actions: {
    async login(username: string, password: string) {
      const data = await post<LoginPayload>('/auth/login', { username, password })
      if (data.token) localStorage.setItem('vi.token', data.token)
      this.apply(data)
    },
    async loadMe() {
      const data = await get<LoginPayload>('/auth/me')
      this.apply({ ...data, token: this.token })
    },
    apply(data: LoginPayload) {
      if (data.token) this.token = data.token
      this.user = data.user
      this.roles = data.roles
      this.perms = data.perms
      this.menus = data.menus
    },
    async changePassword(oldPassword: string, newPassword: string) {
      await put<void>('/auth/password', { oldPassword, newPassword })
      this.reset()
    },
    async logout() {
      try {
        await post<void>('/auth/logout')
      } finally {
        this.reset()
      }
    },
    reset() {
      this.token = ''
      this.user = null
      this.roles = []
      this.perms = []
      this.menus = []
      localStorage.removeItem('vi.token')
      // 清理动态路由与页签, 防止换账号后残留上一用户的可访问页面
      void import('@/router').then((m) => m.clearDynamicRoutes())
      void import('@/store').then((m) => m.useTabBarStore().resetTabList())
    }
  }
})
