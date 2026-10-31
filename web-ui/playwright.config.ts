import { defineConfig } from '@playwright/test'

/**
 * 默认打本地 dev server（webServer 自动拉起）。
 * 设 E2E_BASE_URL 则改为打已部署环境（形如 http://<测试主机>:8090），此时不再拉起 dev server——
 * 用于"改完直接上测试环境验"的口径，省掉本机后端/依赖栈。
 *
 * 注意：baseURL 指向哪里完全由环境变量决定，配置里不留任何主机名/口令默认值——
 * 留了默认值，一次残留的 export 就会让打桩用例对着真实环境发登录请求。
 * 口令统一从 E2E_PW 取（见 e2e/snapshot-column.spec.ts 头部与 README 的跑法）。
 */
const REMOTE = process.env.E2E_BASE_URL
const LOCAL_URL = 'http://127.0.0.1:5173'

export default defineConfig({
  testDir: './e2e',
  timeout: 45_000,
  workers: 1,
  retries: 0,
  use: {
    baseURL: REMOTE || LOCAL_URL,
    screenshot: 'only-on-failure',
    // 显式关掉轨迹与录像: 打已部署环境时它们会把 Authorization 头、签名 URL 与监控画面
    // 一起写进 test-results/（已 gitignore, 但别让后人 --trace on 时无意留下这些）
    trace: 'off',
    video: 'off'
  },
  // 复用已起着的 dev server; 未起则自动拉起（打远程时无需本地 server）
  webServer: REMOTE
    ? undefined
    : {
        command: 'pnpm dev --host 0.0.0.0',
        url: LOCAL_URL,
        reuseExistingServer: true,
        timeout: 60_000
      }
})
