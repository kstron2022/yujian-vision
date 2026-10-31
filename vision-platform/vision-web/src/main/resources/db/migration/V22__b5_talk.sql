-- =====================================================================
-- V22: M3-B5 语音对讲（docs/09 M3-B5, 2022 Talk 流程）
--   1. gb_media_session 增列 audio_stream_id / audio_port: 对讲音频源流（app=rtp,
--      浏览器 WebRTC push 或注流端口推入 ZLM; startSendRtp 的源流）与其 openRtpServer
--      收流端口。session.stream_id 继续表示设备腿（SDP offer 端口绑定）。
--   2. 对讲按钮权限点 talk:talk 挂实时预览页(menu 2)按钮区, id=205
--      (V3 命名 201~204 已占, 顺延); admin/operator 同授, viewer 不授。
--   3. session_type 字典补 4=语音对讲（V1 预留注释, 幂等补种子）。
-- =====================================================================

-- 1. 音频源流列（幂等: MySQL 8.0 无 IF NOT EXISTS, 依赖 Flyway 版本表防重放）
ALTER TABLE gb_media_session
  ADD COLUMN audio_stream_id VARCHAR(128) DEFAULT NULL
  COMMENT '对讲音频源流ID(app=rtp, 浏览器WebRTC/注流端口推入ZLM; session_type=4专用, V22)' AFTER stream_id,
  ADD COLUMN audio_port INT DEFAULT NULL
  COMMENT '对讲音频源收流端口(openRtpServer(audio_stream_id)分配, 注流工具推音频目标, V22)' AFTER audio_stream_id;

-- 2. 对讲按钮（实时预览页按钮区第 5 位）
INSERT INTO sys_menu(id, parent_id, menu_name, menu_type, path, component, perm_code, icon, sort_order, visible, status) VALUES
  (205, 2, '语音对讲', 3, '', '', 'talk:talk', '', 5, 1, 1);

-- 3. 角色授权(幂等, 显式 id —— V10 教训: 按 perm_code 关联会漏父目录)
INSERT IGNORE INTO sys_role_menu(role_id, menu_id)
  SELECT r.id, m.id FROM sys_role r, sys_menu m
  WHERE r.role_code IN ('admin', 'operator') AND m.id = 205;

-- 4. session_type 字典种子(幂等)
INSERT IGNORE INTO sys_dict_data(dict_type, dict_value, dict_label, sort_order) VALUES
  ('session_type', '4', '语音对讲', 4);
