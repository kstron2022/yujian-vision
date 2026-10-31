# 煜见视频平台 YuJianVision

面向 **GB/T 28181（国标）**、**ONVIF** 与 **RTSP** 设备的一体化视频接入与流转发平台。
服务端 Java 17（Spring Boot 3）+ ZLMediaKit 流媒体引擎，管理端 Vue 3 + Arco Design。

## 功能特性

- **多协议设备接入**：GB28181（UDP/TCP 双栈 SIP）、ONVIF（含 WS-Discovery 探测）、RTSP 拉流代理；
  三类设备共用统一的设备/通道模型，支持自动注册与批量导入。
- **实时预览**：HTTP-FLV / WS-FLV / HTTP-TS / HLS / WebRTC 五种播放地址；多观众复用一路流；
  UDP 收流超时自动降级 TCP；浏览器无 HEVC 硬解时自动切 WASM 软解。
- **录像与回放**：设备录像检索与回放（暂停、变速、拖动）、云端录像与保留策略、服务端抓图。
- **控制能力**：云台方向/变倍/聚焦/光圈、预置位、语音对讲（2016 广播与平台直推）。
- **级联**：作为下级向上级注册、作为上级接收下级注册；目录同步、点播转推、回放控制、云台透传。
- **权限**：项目隔离、内置与自定义角色、按分组的数据范围授权。
- **开放接口**：appKey/appSecret + HMAC-SM3（可配置回退 HMAC-SHA256）签名，含频控、防重放、白名单与调用日志。
- **运维**：Prometheus 指标、站内告警、可选 HA 双实例与旁路监控栈。

## 仓库结构

```
├── vision-platform/          后端 Maven 多模块（Java 17 + Spring Boot 3.5）
│   ├── vision-common/        通用响应、错误码、异常
│   ├── vision-sip/           SIP 传输抽象层（jain-sip 封装）
│   ├── vision-protocol-gb28181/  国标协议适配（版本 SPI、SDP 构建）
│   ├── vision-onvif/         ONVIF 协议适配（零依赖 SOAP、WS-Discovery 探测、媒体/云台客户端）
│   ├── vision-media/         ZLMediaKit 客户端、Hook、媒体资源管理
│   ├── vision-service/       业务域：设备/通道/点播/回放/级联（MyBatis-Plus + Flyway）
│   ├── vision-web/           启动模块：REST/WS、安全、OpenAPI、全局异常
│   └── docker/               本地开发编排（MySQL/Redis/ZLM）
├── web-ui/                   前端（Vue 3 + Arco Design + Vite）
├── deploy/                   快速部署、部署手册、环境变量模板、systemd、compose、HA、监控
├── docs/                     使用与对接文档（入口：docs/README.md）
├── CHANGELOG.md              版本变更记录
├── LICENSE · NOTICE          Apache-2.0 许可与第三方组件声明
└── SECURITY.md               安全漏洞报告方式
```

## 快速开始

### 本地开发

构建环境：JDK 17、Node.js 22.18+（或 24.12+）与 pnpm 10；只运行发行包不需要 Node.js。

```bash
# 1. 基础设施（需 Docker）：MySQL 8 / Redis / ZLMediaKit
cd vision-platform/docker && docker compose -f docker-compose-dev.yml up -d

# 2. 后端构建与启动（JDK 17）
#    存在 web-ui/dist 时会打进 jar；开发时只想跑后端可加 -Dembed.web.skip=true
cd vision-platform && mvn -DskipTests package
java -jar vision-web/target/vision-web.jar

# 3. 前端开发服务器（热更新，走 Vite 代理）
cd web-ui && pnpm install && pnpm dev
```

- 后端默认端口 `8080`，健康检查 `GET /actuator/health`，
  接口文档 `swagger-ui.html`，指标 `/actuator/prometheus`；jar 带前端时可直接访问 `http://127.0.0.1:8080`。
- 初始管理员 `admin / admin123`，首次登录强制改密。

### 生产部署

- 快速开始：[deploy/README.md](deploy/README.md)；细节与排障：[部署手册](deploy/部署手册.md)；参数与容量：[配置参考](docs/配置参考.md)
- 容器化（Docker Compose）：[compose/README.md](deploy/compose/README.md)，`setup.sh` 一键起栈
- 上线前务必按 [SECURITY.md](SECURITY.md) 逐项替换占位口令与密钥（弱值/占位值在 prod 环境会拒绝启动）

## 文档

| 我想…… | 看这篇 |
|---|---|
| 找到全部文档 | [docs/README.md](docs/README.md) |
| 接入国标摄像头 / 下级平台 | [GB28181 设备接入指南](docs/GB28181设备接入指南.md) |
| 接入 ONVIF 摄像头 | [ONVIF 设备接入指南](docs/ONVIF设备接入指南.md) |
| 接入 RTSP 流 | [拉流代理接入指南](docs/拉流代理接入指南.md) |
| 与上级 / 下级平台级联 | [级联对接指南](docs/级联对接指南.md) |
| 对接 REST / 开放接口 | [REST-API](docs/03-REST-API设计.md) · [OpenAPI 对接手册](docs/16-OpenAPI对接手册.md) |
| 了解数据表结构 | [数据库设计](docs/02-数据库设计.md) |

## 版本与许可

- 当前版本：**v1.0.1**（见 [CHANGELOG.md](CHANGELOG.md)）
- 许可：[Apache License 2.0](LICENSE)，第三方组件见 [NOTICE](NOTICE)
- 安全漏洞请按 [SECURITY.md](SECURITY.md) 的方式私下报告，勿公开提交 Issue
