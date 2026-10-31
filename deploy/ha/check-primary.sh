#!/bin/sh
# keepalived vrrp_script 探活: 主实例 A(vision-platform) 健康口; 退出码 0=健康
# 安装位: /opt/vision/ha/check-primary.sh (chmod +x)
curl -fsS -m 3 -o /dev/null http://10.0.0.242:8082/actuator/health
