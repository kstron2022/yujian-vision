#!/bin/sh
# keepalived notify 编排(M3-B6 阶段2): 幂等, 全部走平台内部管理口(/api/ha/internal/*)
# 安装位: /opt/vision/ha/notify.sh (chmod +x)
#   fault/backup -> B 接管(B 起栈抢租约); master/stop -> 收敛回 A(先 B 停栈让口, 再 A 起栈)
# 依赖: 环境变量 VISION_HA_TOKEN(A/B 同值, 从 vision-standby.env 或环境注入)
set -u
# token 优先级: 环境变量 > 安装位 env 文件里的 VISION_HA_TOKEN(A/B 同值) > 占位默认。
# 部署物不内嵌真实 token——从 vision-standby.env 自取, 避免重传脚本时覆盖注入值(演练踩坑)
if [ -z "${VISION_HA_TOKEN:-}" ] && [ -r /opt/vision/vision-standby.env ]; then
    VISION_HA_TOKEN=$(sed -n 's/^VISION_HA_TOKEN=//p' /opt/vision/vision-standby.env | head -1)
fi
HA_TOKEN="${VISION_HA_TOKEN:-dev-ha-token-CHANGE-IN-PROD}"
A="http://10.0.0.242:8082"
B="http://10.0.0.243:8082"
# 平台错误包络=HTTP 200+业务 code: 只看 HTTP 码看不见失败, 必须校验响应体 code=0;
# start/stop 是 POST-only 端点, 缺 -X POST 时 405 也被包成 200（演练实测踩坑）
api() { # api <base> <path> → 0 仅当 HTTP 可达且业务 code=0
    resp=$(curl -fsS -m 5 -X POST -H "X-Vision-Ha-Token: $HA_TOKEN" "$1$2" 2>/dev/null) || return 1
    printf '%s' "$resp" | grep -q '"code": *0'
}
state="${1:-}"
case "$state" in
  fault)
    # A 判死: B 接管(重试 3 次, promote 内部起栈失败会回滚返回非 2xx)
    for i in 1 2 3; do
        api "$B" "/api/ha/internal/sip-stack/start" && break
        sleep 2
    done
    ;;
  master|backup|stop)
    # A 恢复(或 keepalived 停止): 收敛回 A —— 先 B 让口再 A 升回, A 起栈失败重试 3 次
    api "$B" "/api/ha/internal/sip-stack/stop" || true
    for i in 1 2 3; do
        api "$A" "/api/ha/internal/sip-stack/start" && break
        sleep 2
    done
    ;;
esac
exit 0
