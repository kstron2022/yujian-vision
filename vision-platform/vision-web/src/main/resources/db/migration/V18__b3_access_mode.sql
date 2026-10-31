-- =====================================================================
-- V18: M3-B3 阶段1 追加——接入模式白名单化 + 接入配置独立页（docs/08 §3.1, 2026-09-13 用户确认）
--   1. gb_access_config 加 allow_auto_register(默认 0=白名单模式):
--      0=仅预建档设备可注册(未知编码 403 拒绝+审计通知); 1=开放模式(未知编码用默认密码自动建档)。
--      ⚠️ 升级注意: 存量自动建档接入的真机在白名单模式下会掉线, 部署时须先按现有
--      编码/密码预建档(deploy 手册由运维补)。
--   2. 菜单: 设备接入(40) 下二级页 23「接入配置」(access/accessconfig/index, perm=device:update,
--      admin/operator 可见; 页面含对接信息只读展示+自动注册开关+默认密码修改)。
-- =====================================================================

ALTER TABLE gb_access_config
  ADD COLUMN allow_auto_register TINYINT NOT NULL DEFAULT 0 COMMENT '允许自动注册 0:白名单(仅预建档) 1:开放' AFTER tenant_id;

INSERT INTO sys_menu(id, parent_id, menu_name, menu_type, path, component, perm_code, icon, sort_order, visible, status) VALUES
  (23, 40, '接入配置', 2, 'access-config', 'access/accessconfig/index', 'device:update', '', 5, 1, 1);

INSERT IGNORE INTO sys_role_menu(role_id, menu_id)
  SELECT r.id, m.id FROM sys_role r, sys_menu m
  WHERE r.role_code IN ('admin', 'operator') AND m.id = 23;

-- ---------------------------------------------------------------- 结果自检
SELECT id, allow_auto_register FROM gb_access_config;
SELECT id, parent_id, menu_name, path, perm_code FROM sys_menu WHERE id = 23;
SELECT r.role_code FROM sys_role_menu rm JOIN sys_role r ON r.id = rm.role_id
  WHERE rm.menu_id = 23 ORDER BY r.role_code;

-- ---------------------------------------------------------------- 回滚(手动执行, Flyway 不跑注释)
-- DELETE FROM sys_role_menu WHERE menu_id = 23;
-- DELETE FROM sys_menu WHERE id = 23;
-- ALTER TABLE gb_access_config DROP COLUMN allow_auto_register;
