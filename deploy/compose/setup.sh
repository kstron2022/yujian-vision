#!/usr/bin/env bash
# ============================================================================
# YuJianVision 单机容器化 · 一键部署脚本（目标机执行）
# 用法:
#   1. 把 deploy/compose/ 整个目录拷到目标机（如 /opt/vision/deployment），进入该目录
#   2. 放构建产物:  app/vision-platform.jar   (vision-web/target/vision-web.jar 改名)
#                   app/dist/                 (web-ui/dist 整目录)
#   3. ./setup.sh <宿主对外IP> [webui端口,默认8090]
# 脚本自动完成: 密钥全部生成 -> 配置填写 -> ZLM secret 替换 -> 起容器等健康 ->
#              admin 密码初始化 -> 流媒体节点注册(hostIp=zlm 容器内口80)
# 结束后打印: 访问地址 / admin 新密码 / 端口放行清单 / 设备接入参数
# ============================================================================
set -euo pipefail
cd "$(dirname "$0")"

HOST_IP="${1:?用法: ./setup.sh <宿主对外IP> [webui端口]}"
WEBUI_PORT="${2:-8090}"
API_PORT="${API_PORT:-8082}"; SIP_PORT="${SIP_PORT:-5060}"
ZLM_HTTP="${ZLM_HTTP_PORT:-8081}"; RTP_START="${ZLM_RTP_START:-30000}"; RTP_END="${ZLM_RTP_END:-30500}"
# 与宿主上既有服务端口冲突时, 均可用同名环境变量覆盖（如 MYSQL_PORT=13306 ./setup.sh <IP>）
MYSQL_PORT="${MYSQL_PORT:-3306}"; REDIS_PORT="${REDIS_PORT:-6379}"
ZLM_RTSP="${ZLM_RTSP_PORT:-554}"; ZLM_RTMP="${ZLM_RTMP_PORT:-1935}"

# ---- 0. 前置检查 -----------------------------------------------------------
command -v docker >/dev/null || { echo "FAIL: 未安装 docker"; exit 1; }
docker compose version >/dev/null 2>&1 || { echo "FAIL: 无 compose v2 插件"; exit 1; }
[ -f app/vision-platform.jar ] || { echo "FAIL: 缺 app/vision-platform.jar"; exit 1; }
[ -f app/dist/index.html ]   || { echo "FAIL: 缺 app/dist/（web-ui 构建产物）"; exit 1; }
[ -f .env ] && { echo "FAIL: .env 已存在（重复部署? 如需重装先删除 .env 与 data/）"; exit 1; }

gen() { openssl rand -hex "$1" 2>/dev/null || head -c 64 /dev/urandom | od -An -tx1 | tr -d ' \n' | head -c "$(( $1 * 2 ))"; }
MYSQL_ROOT_PW=$(gen 16); MYSQL_PW=$(gen 16); REDIS_PW=$(gen 16)
ZLM_SECRET=$(gen 16); JWT=$(gen 32); AES=$(gen 32); ALERT_TOKEN=$(gen 16)
ADMIN_PW="Adm-$(gen 8)"   # 首登强制改密后的新口令, 部署完打印给用户

# ---- 1. 写配置 ------------------------------------------------------------
cat > .env << EOF
MYSQL_ROOT_PASSWORD=$MYSQL_ROOT_PW
MYSQL_DATABASE=vision_platform
MYSQL_USER=vision
MYSQL_PASSWORD=$MYSQL_PW
MYSQL_PORT=$MYSQL_PORT
REDIS_PASSWORD=$REDIS_PW
REDIS_PORT=$REDIS_PORT
ZLM_HTTP_PORT=$ZLM_HTTP
ZLM_RTSP_PORT=$ZLM_RTSP
ZLM_RTMP_PORT=$ZLM_RTMP
ZLM_RTP_START=$RTP_START
ZLM_RTP_END=$RTP_END
API_PORT=$API_PORT
SIP_PORT=$SIP_PORT
WEBUI_PORT=$WEBUI_PORT
EOF

cat > vision.env << EOF
SPRING_PROFILES_ACTIVE=prod
SERVER_PORT=8082
MYSQL_USERNAME=vision
MYSQL_PASSWORD=$MYSQL_PW
SPRING_DATA_REDIS_PASSWORD=$REDIS_PW
REDIS_DB=0
VISION_JWT_SECRET=$JWT
VISION_AES_KEY=$AES
VISION_ALERTWEBHOOKTOKEN=$ALERT_TOKEN
VISION_PLAY_PUBLIC_BASE=http://$HOST_IP:$ZLM_HTTP
VISION_SIP_PUBLIC_IP=$HOST_IP
LOG_DIR=/app/logs
VISION_SNAPSHOT_DIR=/app/data/snapshot
VISION_SECURITY_TRUSTEDPROXIES_0=172.30.0.0/24
EOF
chmod 600 .env vision.env

sed -i "s/CHANGE_ME_FILL_AT_DEPLOY/$ZLM_SECRET/g" zlm/config.ini
# RTP 收流段同步(容器内 port_range 必须=compose 发布段, SDP 端口=宿主端口; 错位=媒体黑洞)
sed -i "s/^port_range=.*/port_range=$RTP_START-$RTP_END/" zlm/config.ini
mkdir -p logs data/snapshot

# ---- 2. 起容器等健康 -------------------------------------------------------
docker compose up -d
echo "容器启动中, 等待健康（首次启动含建表, 约 1~2 分钟）..."
# 容器 ID 动态获取（不依赖 container_name 命名约定, 实例侧改名也不受影响）
CID=""; for i in $(seq 1 36); do
  sleep 10
  [ -z "$CID" ] && CID=$(docker compose ps -q platform 2>/dev/null)
  ST=$([ -n "$CID" ] && docker inspect --format '{{.State.Health.Status}}' "$CID" 2>/dev/null || echo starting)
  echo "  t=$((i*10))s platform=$ST"
  [ "$ST" = "healthy" ] && break
  [ "$ST" = "unhealthy" ] && { echo "FAIL: platform unhealthy, 查日志: docker compose logs platform"; exit 1; }
done
[ "$ST" = "healthy" ] || { echo "FAIL: 健康等待超时（容器已保留, 可 docker compose ps 查看后重试初始化）"; exit 1; }

# ---- 3. 平台初始化（admin 改密 + 注册流媒体节点） ---------------------------
BASE="http://127.0.0.1:$API_PORT"
TOKEN=$(curl -s -X POST $BASE/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
[ -n "$TOKEN" ] || { echo "FAIL: admin 登录失败"; exit 1; }
curl -s -X PUT $BASE/api/auth/password -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"oldPassword\":\"admin123\",\"newPassword\":\"$ADMIN_PW\"}" >/dev/null
TOKEN=$(curl -s -X POST $BASE/api/auth/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$ADMIN_PW\"}" | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
curl -s -X PUT $BASE/api/servers/vision-zlm -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{
  "hostIp": "zlm", "publicIp": "'"$HOST_IP"'", "httpPort": 80,
  "rtpPortStart": '"$RTP_START"', "rtpPortEnd": '"$RTP_END"', "signSecret": "'"$ZLM_SECRET"'"
}' >/dev/null
echo "节点已注册, 等待 ZLM keepalive 打通（~30s）..."; sleep 35
NODE_STATUS=$(curl -s $BASE/api/servers -H "Authorization: Bearer $TOKEN" | grep -o '"status":[0-9]*' | head -1 | cut -d: -f2)

# ---- 4. 部署汇总 -----------------------------------------------------------
cat << EOF

========================= 部署完成 =========================
 前端入口    : http://$HOST_IP:$WEBUI_PORT
 平台 API    : http://$HOST_IP:$API_PORT
 admin 账号  : admin / $ADMIN_PW   （请妥善保存, 不再二次显示）
 流媒体节点  : vision-zlm 在线状态=$([ "$NODE_STATUS" = "1" ] && echo 在线 || echo "离线(查 docker logs vision-platform)")
 设备接入    : SIP 服务器地址 = $HOST_IP:$SIP_PORT（摄像头/NVR 对时也指向本机）
------------------------------------------------------------
 防火墙放行  : 终端网段 -> $WEBUI_PORT/tcp
              设备网段 -> $SIP_PORT/tcp+udp, $RTP_START-$RTP_END/tcp+udp
 常用命令    : docker compose ps|logs -f|restart vision-platform
 升级        : 替换 app/vision-platform.jar 后 docker compose up -d platform
============================================================
EOF
