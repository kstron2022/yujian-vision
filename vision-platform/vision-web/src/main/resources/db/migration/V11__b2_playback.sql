-- =====================================================================
-- V11: B2-a 回放后端
--   1. gb_media_session 加回放窗口两列（起止时间, LocalDateTime）
--      注: 会话类型不另加列 —— V1 已有 session_type TINYINT(1:实况 2:回放 3:下载
--      4:对讲 5:级联转发), 实体字段 GbMediaSessionEntity.sessionType 一直在用,
--      本迁移只补回放专用窗口, 实况/回放共用既有类型列。
--   2. uk_active 唯一键纳入 session_type: 原键 (channel_db_id, stream_profile,
--      active_flag) 会让同通道「实况进行中再开回放」撞唯一键(两者 active_flag 同为 1);
--      docs/07 §2.2 要求回放端口与实况相互独立, 故按类型分键放行并存。
--   3. 回放权限点(菜单按钮行, 挂在 51 录像检索页下;
--      docs/03 §2.1 矩阵: 录像回放三角色可用)
-- =====================================================================

-- 1. 回放窗口
ALTER TABLE gb_media_session ADD COLUMN playback_start DATETIME(3) NULL COMMENT '回放窗口起(LocalDateTime)';
ALTER TABLE gb_media_session ADD COLUMN playback_end   DATETIME(3) NULL COMMENT '回放窗口止';

-- 2. 唯一键按会话类型分流（实况/回放同通道并存）
ALTER TABLE gb_media_session DROP INDEX uk_active,
  ADD UNIQUE KEY uk_active (channel_db_id, session_type, stream_profile, active_flag);

-- 3. 回放权限点 + 三角色授权（幂等）
INSERT INTO sys_menu(id, parent_id, menu_name, menu_type, path, component, perm_code, icon, sort_order, visible, status) VALUES
  (210, 51, '录像回放', 3, '', '', 'record:play', '', 2, 1, 1);

INSERT IGNORE INTO sys_role_menu(role_id, menu_id)
  SELECT r.id, m.id FROM sys_role r, sys_menu m
  WHERE r.role_code IN ('admin', 'operator', 'viewer') AND m.perm_code = 'record:play';

-- 结果自检: 三角色 × 1 权限点 = 3 行
SELECT r.role_code, m.perm_code FROM sys_role_menu rm
  JOIN sys_role r ON r.id = rm.role_id JOIN sys_menu m ON m.id = rm.menu_id
  WHERE m.perm_code = 'record:play' ORDER BY r.role_code;
