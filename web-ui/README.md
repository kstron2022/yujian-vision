# web-ui — 视频平台前端

Vue 3 + TypeScript + Vite + Pinia + Arco Design Vue（移植 Arco Design Pro 布局）。
包管理器：**pnpm**（registry 用国内镜像 npmmirror，安装不走代理）。

## 环境要求

- Node.js ≥ 22.18（或 ≥ 24.12）
- pnpm ≥ 10（已在 package.json `packageManager` 固定版本）

## 常用命令

```sh
pnpm install        # 安装依赖
pnpm dev --host 0.0.0.0   # 开发服务器（默认 5173，--host 便于局域网/WSL 访问）
pnpm build          # 类型检查 (vue-tsc) + 生产构建
pnpm exec playwright test # e2e（webServer 自动复用/拉起 dev 5173，需后端 8080 在跑）
```

## e2e 怎么跑

口令、测试主机、设备编号**都不入库**，一律由环境变量给（仓库里留默认值等于把"拿到仓库即可登录测试环境"
的完整配方一起交出去）。没给就整组 skip，不会拿一个假默认值去打环境。

```sh
# ① 打桩用例（登录打真后端，列表/图片/点播全部 page.route 打桩）
E2E_PW='<测试环境登录口令>' pnpm exec playwright test \
  e2e/smoke.spec.ts e2e/rtc-probe.spec.ts e2e/snapshot-column.spec.ts

# ② 打已部署环境（不走 dev server）
E2E_PW='<口令>' E2E_BASE_URL=http://<测试主机>:8090 pnpm exec playwright test e2e/snapshot-column.spec.ts

# ③ 真机用例（真实国标摄像机 + 真 ZLM，默认 skip；主机/设备编号/口令都由环境给）
E2E_REAL=1 E2E_BASE_URL=http://<测试主机>:8090 E2E_REAL_API=http://<测试主机>:8082 \
  E2E_REAL_DEVICE=<设备国标编号> E2E_PW='<口令>' \
  pnpm exec playwright test e2e/real-device.spec.ts
```

`E2E_REAL_DEVICE` 需选一台**在线、且已有截图与录像**的设备，真机用例会经 API 自建一路流并在
`afterEach` 里停掉（断言中途失败也不会把流留在平台上）。

## 结构速览

- `src/layout/default-layout.vue` — 全局布局（导航栏/侧边菜单/页签栏/内容区）。内容区 `page-area` 统一 16px 四周 padding，业务页无需单独处理
- `src/views/` — 业务页面；动态路由由登录后后端 `menus[]` 驱动（`meta.perm` 对应菜单 perm_code，无权访问跳 /403）
- `src/stores/auth.ts` — 认证/菜单；`src/utils/` 封装 axios 与 WS（`/ws/api?token=`）
- `e2e/` — Playwright：`smoke.spec.ts` 冒烟（登录/布局/WS/页签/权限）、`rtc-probe.spec.ts` RTC 探针、
  `snapshot-column.spec.ts` 通道截图列与播放弹窗（B7 阶段3，打桩 8 用例）、`real-device.spec.ts` 真机验证（默认 skip）

## 注意

- 开发代理：`/api`、`/ws` → `http://127.0.0.1:8080`（后端 vision-web）
- 生产部署由 Nginx 同源终结（docs/03 §1.2）
