# 监控告警旁路部署（可选）

本目录是平台的**旁路监控栈**：Prometheus 抓取平台（Micrometer `/actuator/prometheus`）与宿主机（node-exporter）指标并评估 `b4-alerts.yml` 阈值规则，触发后推给 Alertmanager，Alertmanager 以 webhook 回调平台 `/api/alerts/webhook`（携带 `X-Vision-Alert-Token` 鉴权头），由站内消息中心落库推送——**常态告警不依赖邮件/短信等外部渠道**。例外是 `PlatformDown`（平台进程失联, 站内链路同断）：当前版本它同时走**带外渠道**（企微/钉钉/SMTP 任选其一, 见 §六）。Grafana 提供两个预置仪表盘（平台总览 / ZLM 节点与会话），数据源与仪表盘均为 provisioning 自动装载，开箱即用。

## 一、容器职责 / 端口 / 零暴露

| 容器 | 镜像（tag 已定稿勿改） | 宿主端口 | 说明 |
| --- | --- | --- | --- |
| vision-prometheus | prom/prometheus:v2.53.3 | **无（零暴露）** | 抓取/存储/规则评估；保留 30 天；仅 docker 内网可访问 9090 |
| vision-alertmanager | prom/alertmanager:v0.28.1 | **无（零暴露）** | 告警分组/去重/重试后回调平台；仅 docker 内网可访问 9093 |
| vision-grafana | grafana/grafana:11.4.0 | **3300 → 3000** | 可视化；宿主 3000 端口被占用时映射 3300 |
| vision-node-exporter | prom/node-exporter:v1.8.2 | **9100 → 9100** | 宿主机 CPU/内存/磁盘/网络指标（/proc、/sys、/ 只读挂载） |

- 四个服务都在 **compose 默认网络**（用户自定义 bridge）里，服务名/别名互通；prometheus.yml 用 `alertmanager:9093`、数据源用 `http://prometheus:9090`（compose 侧已为容器加同名短别名）。
- 平台 jar 跑在**宿主机 systemd（8082）**，不在本 compose 内；容器访问宿主机统一走 docker 默认桥网关 **172.17.0.1**（抓取 `172.17.0.1:8082`、`172.17.0.1:9100`，webhook 回调 `http://172.17.0.1:8082`）。
- Prometheus / Alertmanager 不映射任何宿主端口：宿主防火墙、公网侧都摸不到它们，只能通过 Grafana 看板或在部署机本机 `docker exec` 进入操作。

## 二、部署步骤

```bash
# 1. 上传本目录到部署机（目录结构保持原样）
rsync -av deploy/monitoring/ root@<部署机>:/opt/vision/monitoring/

# 2. Grafana 管理员密码放 /opt/vision/monitoring/.env（up-monitoring.sh source 它；chmod 600，不入仓库）
echo "GRAFANA_ADMIN_PASSWORD=$(openssl rand -hex 12)" > /opt/vision/monitoring/.env && chmod 600 /opt/vision/monitoring/.env

# 3. 告警回调 token（两处一致）：
#    - alertmanager/alertmanager.yml 的 CHANGE_ME_FILL_AT_DEPLOY 占位符 sed 替换为真实值（AM yaml 不支持 ${ENV} 替换）
sed -i 's/CHANGE_ME_FILL_AT_DEPLOY/<TOKEN>/' /opt/vision/monitoring/alertmanager/alertmanager.yml
chmod -R go-rwx /opt/vision/monitoring/alertmanager
#    - 平台侧 vision.env 追加同名值（宽松绑定名 VISION_ALERTWEBHOOKTOKEN，升级新 jar 前配好，改完 systemctl restart vision-platform 生效）
# 3b. 带外渠道（可选, PlatformDown 兜底, 见 §六）: 不用则把 alertmanager.yml 里 oob-out-of-band
#     的 email_configs 块整块注释; 用则 sed 替换 4 处 CHANGE_ME_SMTP_* / example.com 占位符
#     （同文件 sed, 替换完 chmod 步骤在上方已覆盖; AM 会在 next PlatformDown 时真发出去, 勿留占位符上线）

# 4. Grafana 数据目录属主（容器内 uid 472），否则起不来
mkdir -p /opt/vision/monitoring/grafana-data
chown 472:472 /opt/vision/monitoring/grafana-data

# 5. 起栈（无 docker compose 插件时——up-monitoring.sh 为实际部署路径，幂等重建四容器）
cd /opt/vision/monitoring
bash up-monitoring.sh

# 6. 看状态（docker compose 文件仅作同构参考，非脚本部署路径）
docker ps --format '{{.Names}}\t{{.Status}}' | grep vision-
```

## 三、验收

```bash
# 1) 两个抓取目标必须 healthy（prometheus 无宿主端口，进容器查）
docker exec vision-prometheus \
  wget -qO- 'http://localhost:9090/api/v1/targets?state=active' \
  | grep -o '"health":"[a-z]*"'          # 期望两处 "health":"up"

# 2) 平台指标已暴露（宿主机直接验证）
curl -s http://127.0.0.1:8082/actuator/prometheus | grep -E '^vision_(session_active|node_status|node_zlm_reachable)'

# 3) 告警规则已装载、无错误
docker exec vision-prometheus wget -qO- http://localhost:9090/api/v1/rules | grep -c b4-platform-alerts

# 4) 告警链路端到端（可选）：手工推一条测试告警，站内消息中心应收一条
docker exec vision-alertmanager sh -c 'wget -qO- --post-data='"'"'{"alerts":[{"labels":{"alertname":"DeployCheck","server":"main"},"annotations":{"summary":"部署自检","description":"部署自检测试告警，可忽略"}}]}'"'"' --header="Content-Type: application/json" http://localhost:9093/api/v2/alerts'
# 5) Grafana：浏览器开 http://<部署机IP>:3300 ，admin / $GRAFANA_ADMIN_PASSWORD 登录，
#    首页应能看到 "Vision 平台" 文件夹下的《平台总览》《ZLM 节点与会话》两块看板
```

## 四、数据 / 密码 / 保留

- **TSDB 数据**：named volume `prometheus-data`，15s 采样、`--storage.tsdb.retention.time=30d` 滚动清理；删除该 volume = 清空历史。
- **Grafana 数据**：`./grafana-data`（数据源/用户/看板改动都落这里）；看板源文件在本目录 `grafana/dashboards/*.json`，改完约 30s 自动同步（provider `updateIntervalSeconds: 30`）。
- **密码/token**：Grafana 管理员密码在 `/opt/vision/vision.env`（不入库）；alert token 同样只在部署机落盘，`alertmanager/alertmanager.yml` 里是明文，权限已收敛到 root。改 token 要同步改平台配置，两处必须一致。
- **告警保留语义**：`repeat_interval=12h`（同一告警 12h 重发一次）、`group_interval=5m`、`group_wait=30s`；告警恢复（`send_resolved: true`）也会推站内，展示为已恢复。

## 五、告警 → 站内链路（一句话）

Prometheus 按 `b4-alerts.yml` 评估规则 → 触发后推 `alertmanager:9093` → 按 `alertname+server` 分组去重 → webhook 回调平台 `POST http://172.17.0.1:8082/api/alerts/webhook`（头 `X-Vision-Alert-Token`）→ 平台校验 token 后写入站内消息中心，正文取告警的中文 `description`。

两点须知：

- **恢复不发站内**（`send_resolved: false`，平台侧 resolved 也不落通知）：告警恢复以 Grafana 面板曲线为准，消息中心只报不撤。
- **PlatformDown 双通道**：带外渠道（§六）即时触达值守 + 站内链路 AM 自动补投（平台恢复后落库留痕"宕过机"）；带外未配置时仅有站内链路，平台失联期间以 Prometheus UI / 主机巡检为准。

## 六、带外渠道（PlatformDown 兜底）

PlatformDown = 平台进程失联，此时站内 webhook 链路同断，告警只经带外渠道触达值守。**企微/钉钉/SMTP 任选其一**，配置位置都在 `alertmanager/alertmanager.yml` 的 `oob-out-of-band` receiver（路由已接线：PlatformDown 双投——带外先发 `continue: true`，站内仍投、平台恢复后补投留痕）。AM yaml 不支持 `${ENV}` 替换，凭据一律部署时 sed 占位符（§部署步骤 3b）。**未启用带外的站点把 `email_configs` 块整块注释即可**，路由保留、行为回退 B4 原状，无报错副作用。

| 方案 | 机制 | 启用成本 |
| --- | --- | --- |
| A. SMTP 邮件（默认模板） | AM 原生 `email_configs`，STARTTLS（587）；465 隐式 TLS 不支持 | 填 4 处 CHANGE_ME 占位符，零新增组件 |
| B. 企业微信应用消息 | AM 原生 `wechat_configs`（企微**应用** API，非群机器人；收信人=应用可见范围） | 注释解封 + 填 corp_id/secret/agent_id |
| C. 钉钉群机器人 | AM 原生报文与其格式不兼容，需旁路转换器 `prometheus-webhook-dingtalk` | 新增一个旁路容器（片段如下） |

方案 C 的 compose 追加片段（加进 `docker-compose.monitoring.yml` 的 `services:` 下，与 alertmanager 同网络互通）：

```yaml
  dingtalk-webhook:                       # prometheus-webhook-dingtalk（转换器，仅方案 C 需要）
    image: timonwong/prometheus-webhook-dingtalk:v2.1.0
    container_name: vision-dingtalk-webhook
    command: --web.listen-address=:8060 --config.file=/etc/prometheus-webhook-dingtalk/config.yml
    volumes:
      - ./alertmanager/dingtalk.yml:/etc/prometheus-webhook-dingtalk/config.yml:ro
```

对应 `alertmanager/dingtalk.yml`（机器人 token 放部署机，不入仓库）：

```yaml
targets:
  ops:
    url: https://oapi.dingtalk.com/robot/send?access_token=CHANGE_ME_DINGTALK_TOKEN
```

启用 C 后把 `oob-out-of-band` 的 `email_configs` 换成 `webhook_configs: [{url: http://dingtalk-webhook:8060/dingtalk/ops/send, send_resolved: true}]`。带外渠道统一 `send_resolved: true`（平台恢复时值守收到 RESOLVED 闭环；站内 resolved 不落的口径不变）。

## 七、本地开发 overlay（Windows dev 机）

`vision-platform/docker/docker-compose-dev.yml` 已追加两个服务，`docker compose -f vision-platform/docker/docker-compose-dev.yml up -d prometheus grafana` 即可：

- **prometheus**：`9091 → 9090`，配置为 `prometheus-dev.yml`（抓本地 Windows 平台 8080，无 alerting、无规则文件）。目标地址注意：部分企业 VPN 客户端会丢弃 `host.docker.internal`（WSL 网关）流量，`prometheus-dev.yml` 用 WLAN 网卡 IP，随网络漂移需同步改。
- **grafana**：`3301 → 3000`，挂同一套 provisioning 与 dashboards，数据目录 `vision-platform/docker/data/grafana/`，管理员密码默认 `admin / admin123`（可用环境变量 `GRAFANA_ADMIN_PASSWORD` 覆盖，不 fail-fast）。
- 相对路径从 `vision-platform/docker/` 出发是 `../../deploy/monitoring/...`，勿移动目录。
- dev 看板里"主机 CPU/内存/磁盘/网络"等 node-exporter 面板在本地无数据（本地不起 node-exporter），属预期。
