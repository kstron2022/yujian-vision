-- =====================================================================
-- V16: M3-B3 级联-上级 阶段1（docs/09 M3 B3, docs/08 §3）
--   下级平台接入基础: 设备预建档 API（POST /api/devices, device:add）
--   + 设备类型可改（PUT 增加 name/deviceType patch）。
--   gb_device.device_type 列 V1 已有（1:设备 2:下级平台），无表结构变更。
--   菜单: 设备管理(3) 下按钮 305「设备新增」(device:add)。
--   授权: admin + operator（与 302 设备编辑同级, viewer 不授; 按 id 显式授权, V10 教训）。
-- =====================================================================

INSERT INTO sys_menu(id, parent_id, menu_name, menu_type, path, component, perm_code, icon, sort_order, visible, status) VALUES
  (305, 3, '设备新增', 3, '', '', 'device:add', '', 5, 1, 1);

INSERT IGNORE INTO sys_role_menu(role_id, menu_id)
  SELECT r.id, m.id FROM sys_role r, sys_menu m
  WHERE r.role_code IN ('admin', 'operator') AND m.id = 305;

-- ---------------------------------------------------------------- 结果自检
SELECT id, parent_id, menu_name, perm_code FROM sys_menu WHERE id = 305;
SELECT r.role_code FROM sys_role_menu rm
  JOIN sys_role r ON r.id = rm.role_id WHERE rm.menu_id = 305 ORDER BY r.role_code;

-- ---------------------------------------------------------------- 回滚(手动执行, Flyway 不跑注释)
-- DELETE FROM sys_role_menu WHERE menu_id = 305;
-- DELETE FROM sys_menu WHERE id = 305;
