-- ---------------------------------------------------------------- V2 M1-5 点播闭环
-- ZLM 节点种子（docs/04 §6 部署基线；节点管理 CRUD 属 M1 任务9 平台功能，先种子行联调）
-- dev 环境：vision-zlm 容器（docker-compose-dev.yml），宿主经端口映射访问
INSERT INTO gb_media_server
  (tenant_id, server_id, host_ip, public_ip, http_port, http_ssl_port, rtsp_port, rtmp_port,
   rtc_port, rtp_port_start, rtp_port_end, hook_enable, sign_secret, status)
VALUES
  (0, 'vision-zlm', '127.0.0.1', '127.0.0.1', 8081, 8443, 554, 1935,
   8000, 30000, 30500, 1, 'vision-zlm-secret', 1);
