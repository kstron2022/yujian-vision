-- ================================================================================
-- M3-B3 阶段2: 级联点播权限点（docs/03 §2.1 预留 cascade:play M3）
--   场景: 点播下级平台通道(设备 deviceType=2)时的专用权限——与既有 device:play(本域设备点播)
--   分开校验, 便于「允许看本域、限制看下级共享」的部署差异化授权。
--   挂载: 实时预览(菜单 2)按钮段 204（201 开始点播/202 停止点播/203 云台控制 之后）。
--   授权: admin + operator（viewer 不授——下级共享内容默认管控更严, 需要时手动加）。
-- ================================================================================

INSERT INTO sys_menu(id, parent_id, menu_name, menu_type, path, component, perm_code, icon, sort_order, visible, status) VALUES
  (204, 2, '级联点播', 3, '', '', 'cascade:play', '', 4, 1, 1);

INSERT IGNORE INTO sys_role_menu(role_id, menu_id)
  SELECT r.id, m.id FROM sys_role r, sys_menu m
  WHERE r.role_code IN ('admin', 'operator') AND m.id = 204;

-- ---------------------------------------------------------------- 结果自检
SELECT id, parent_id, menu_name, perm_code FROM sys_menu WHERE id = 204;
SELECT r.role_code, m.id AS menu_id FROM sys_role_menu rm
  JOIN sys_role r ON r.id = rm.role_id JOIN sys_menu m ON m.id = rm.menu_id
  WHERE m.id = 204 ORDER BY r.role_code;

-- ---------------------------------------------------------------- 回滚(手动执行, Flyway 不跑注释)
-- DELETE FROM sys_role_menu WHERE menu_id = 204;
-- DELETE FROM sys_menu WHERE id = 204;
