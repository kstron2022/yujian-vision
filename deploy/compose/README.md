# 单机容器化部署包（docker compose）

> 本目录是容器化部署模板。快速上手见 [../README.md](../README.md)，systemd 裸机形态与排障见
> [../部署手册.md](../部署手册.md)，参数见 [../../docs/配置参考.md](../../docs/配置参考.md)。

## 部署物与镜像

| 服务 | 镜像（公共仓库联网拉取） | 宿主端口 | 数据落盘（实例目录内） |
|---|---|---|---|
| mysql | `mysql:8.4` | 127.0.0.1:3306 | `data/mysql/` |
| redis | `redis:7-alpine` | 127.0.0.1:6379 | `data/redis/` |
| zlm | `zlmediakit/zlmediakit:master` | 8081/554/1935/30000-30500(tcp+udp) | `data/zlm/` |
| platform | `eclipse-temurin:17-jre` + **挂载 jar** | 8082 / 5060(tcp+udp) | `logs/` `data/snapshot/` |
| webui | `nginx:1.28-alpine` + **挂载 dist** | **8090（唯一入口）** | — |

表结构由平台启动时 Flyway 自动创建（含种子菜单/角色/admin），无手工 SQL。
jar/dist 免构建镜像：升级 = 替换文件 + `docker compose restart`（步骤见下）。

## 首次部署（7 步）

```bash
# 0. 前置: 目标机装 docker + compose v2 插件（docker compose version 验证）; 端口放行见配置参考
# 1. 生成实例目录
cp -r deploy/compose/ /opt/vision/deployment && cd /opt/vision/deployment
# 2. 填配置（两份模板都要填真值; chmod 600; 密钥生成命令在模板注释里）
cp .env.example .env && cp vision.env.example vision.env && vim .env vision.env
#    ⚠️ vision.env 的 VISION_SIP_PUBLIC_IP 必填宿主对外 IP（容器化必配, 缺省=Via/Contact 宣告容器
#    内网 IP, 规范设备按 Via 回包即断; 单机 systemd 裸跑无需此配置）
# 3. 替换 ZLM secret（与后续平台页面配置的节点 secret 同一值）
sed -i 's/CHANGE_ME_FILL_AT_DEPLOY/<真实secret>/g' zlm/config.ini
# 4. 放构建产物（⚠️ 单文件挂载, up 前文件必须已存在, 否则 docker 会建同名目录）
cp vision-web/target/vision-platform.jar app/vision-platform.jar    # vision-web.jar 改名
cp -r web-ui/dist app/dist
# 5. 起全套（platform 等 mysql/redis 健康后自动启动）
docker compose up -d
docker compose ps        # 五服务 healthy/running
docker logs -f vision-platform   # 首次启动 Flyway 建表, 等 health UP
# 6. 平台初始化: 浏览器 http://<宿主IP>:8090 登录 admin（首登强制改密）→
#    「流媒体节点」新增节点: serverId=vision-zlm, hostIp=zlm（服务名, 平台→ZLM 走 compose 内网）,
#    publicIp=<宿主对外 IP>（SDP c= 地址用它）, httpPort=**80**（ZLM 容器内口——平台调 ZLM API 走
#    内网; 对外播放地址不走它, 由 vision.env 的 VISION_MEDIA_PUBLIC_BASE 承接）, rtp 端口段与 .env
#    一致, signSecret=第 3 步的 secret
#    ⚠️ 需要拉 RTSP 流的模拟设备/脚本先在「设备接入 → 设备列表」预建档（白名单模式, 未建档注册被 403）
# 7. 验收: 摄像头 SIP 服务器指向 <宿主IP>:5060 注册 → 设备页在线 → 点播出图（走 8090 播放）
```

## 日常升级

```bash
# 后端: 停→换→起（jar 被覆盖瞬间进程还在会抛 NoClassDefFoundError 噪音, 与裸机形态同规）
docker compose stop platform && cp vision-platform.jar app/vision-platform.jar && docker compose up -d platform
# 前端: 就地覆盖 dist 后重启 webui（挂载按目录, 覆盖文件内容即可; 保险起见 restart webui）
tar -xzf webui-dist.tgz -C app/dist && docker compose restart webui
# 配置: 改 .env/vision.env/zlm/config.ini 后 docker compose up -d（重建对应容器生效）
# 升级后按部署手册 §6 做上线自检
```

## 与裸机（systemd）形态的差异速查

| 事项 | 裸机形态 | 本容器化形态 |
|---|---|---|
| 平台进程 | systemd + vision.env | compose 服务 + 挂载 jar；`journalctl` 换 `docker logs vision-platform` |
| ZLM hook 目标 | `http://<宿主IP>:8082/api/hooks/*` | `http://platform:8082/api/hooks/*`（compose 内网 DNS） |
| 平台→ZLM API | host_ip=127.0.0.1 | 节点 hostIp=`zlm`（服务名） |
| webui 反代上游 | 宿主 IP:8082（双上游 HA） | `platform:8082` 单上游；`/rtp/`→`zlm:80`（容器内口） |
| SIP 宣告地址 | 兜底宿主网卡 IP（无需配置） | **必须配 `VISION_SIP_PUBLIC_IP`**=<宿主对外 IP> |
| 可信代理 | webui 容器 IP（重建会漂） | compose 固定网段 CIDR `172.30.0.0/24`（漂移免疫） |
| MySQL/Redis host | 127.0.0.1 | compose 写死 `mysql`/`redis`（服务名, vision.env 无需配 host） |

## 注意事项

- **防火墙口径**：终端用户（浏览器）只放行 8090；设备网段放行 5060(tcp+udp) + 30000-30500(tcp+udp)；
  8082/3306/6379 不对终端网段开放（3306/6379 仅绑宿主回环）。
- **RTP 段改动**：`.env` 的 `ZLM_RTP_START/END` 与 `zlm/config.ini` 的 `rtp_proxy.port_range` 两处同步
  （段大小 = 最大并发收流路数；全段 tcp+udp 映射 = 每端口两个 docker-proxy 进程，段很大时留意）。
- **compose 网段改动**：`docker-compose.yml` 的 subnet 与 `vision.env` 的
  `VISION_SECURITY_TRUSTEDPROXIES_0` 两处同步。
- **不部署监控栈**：告警/指标旁路（deploy/monitoring/）独立于本包，需要时按其 README 另起。
- **HA**：本包仅单机标准形态；双实例 HA 走 deploy/ha/（systemd 裸机形态）。
