#!/usr/bin/env bash
# 监控栈启动脚本（无 docker compose 插件时用独立 docker run；compose 文件为同构参考文档）
# 幂等：已存在同名容器先删除再建。用法：cd /opt/vision/monitoring && bash up-monitoring.sh
set -euo pipefail
cd "$(dirname "$0")"

docker network create vision-mon 2>/dev/null || true

docker rm -f vision-prometheus vision-alertmanager vision-grafana vision-node-exporter 2>/dev/null || true

docker run -d --name vision-prometheus --restart unless-stopped \
  --network vision-mon --network-alias prometheus \
  -v /opt/vision/monitoring/prometheus.yml:/etc/prometheus/prometheus.yml:ro \
  -v /opt/vision/monitoring/b4-alerts.yml:/etc/prometheus/b4-alerts.yml:ro \
  -v prometheus-data:/prometheus \
  prom/prometheus:v2.53.3 \
  --web.enable-lifecycle \
  --config.file=/etc/prometheus/prometheus.yml \
  --storage.tsdb.retention.time=30d

docker run -d --name vision-alertmanager --restart unless-stopped \
  --network vision-mon --network-alias alertmanager \
  -v /opt/vision/monitoring/alertmanager/alertmanager.yml:/etc/alertmanager/alertmanager.yml:ro \
  prom/alertmanager:v0.28.1

# grafana 管理口令从 .env 读（GRAFANA_ADMIN_PASSWORD，chmod 600，不入仓库）
source .env
docker run -d --name vision-grafana --restart unless-stopped \
  --network vision-mon --network-alias grafana \
  -p 3300:3000 \
  -e GF_SECURITY_ADMIN_USER=admin \
  -e "GF_SECURITY_ADMIN_PASSWORD=$GRAFANA_ADMIN_PASSWORD" \
  -v /opt/vision/monitoring/grafana/provisioning:/etc/grafana/provisioning:ro \
  -v /opt/vision/monitoring/grafana/dashboards:/etc/grafana/dashboards:ro \
  -v /opt/vision/monitoring/grafana-data:/var/lib/grafana \
  grafana/grafana:11.4.0

docker run -d --name vision-node-exporter --restart unless-stopped \
  --network vision-mon --network-alias node-exporter \
  -p 9100:9100 \
  -v /proc:/host/proc:ro -v /sys:/host/sys:ro -v /:/rootfs:ro \
  prom/node-exporter:v1.8.2

docker ps --format '{{.Names}}\t{{.Status}}' | grep vision-
