-- =====================================================================
-- V8: 菜单信息架构重构（M2 开工前骨架调整, 2026-09-09 与用户确认）
--   原则: 【不改已有菜单 id, 只挪挂载点/名称】—— sys_role_menu 授权行按
--   (role_id, menu_id) 引用, id 稳定 = 存量授权全部有效, 无需重刷。
--   变更:
--   1. 新目录「设备接入」(id=40): 设备管理(3)/流媒体节点(20) 从
--      「监控预览」「系统管理」挪入 —— 资产/媒体域与操作/权限域分离
--   2. 实时预览(2) 提为一级叶子菜单(path=/live), 删除只剩一页的目录
--      「监控预览」(id=1) —— 目录层只剩一页时是纯点击开销
--   3. 「会话监控」(14) 更名「在线用户」—— 消除与"监控预览/监控告警"的
--      命名歧义(它是在线会话安全运营功能, 不是视频监控)
--   4. 角色补授权(幂等): 新目录 40 → admin(V4 全量授权只跑过一次,
--      不会自动覆盖新增行) 与 operator(持有设备管理, 父目录必须可见);
--      删除目录 1 时同步清理三角色对它的授权行
--   前瞻: 录像回放=50 / 报警中心=60 / 运维监控=70 id 段预留(M2/M3 落地
--   时随各自迁移创建, 不建空壳目录), 见 docs/09 M2 批次执行顺序。
-- =====================================================================

-- 1. 新目录「设备接入」
INSERT INTO sys_menu(id, parent_id, menu_name, menu_type, path, component, perm_code, icon, sort_order, visible, status)
VALUES (40, 0, '设备接入', 1, '/access', '', '', 'LinkOutlined', 2, 1, 1);

-- 2. 设备管理(3): 挂到设备接入下; views 已同步移至 views/access/device
UPDATE sys_menu SET parent_id = 40, sort_order = 1, component = 'access/device/index' WHERE id = 3;
--    流媒体节点(20): 从系统管理挪入; views 已同步移至 views/access/mediaserver
UPDATE sys_menu SET parent_id = 40, sort_order = 2, component = 'access/mediaserver/index' WHERE id = 20;

-- 3. 实时预览(2) 提为一级叶子菜单; 组件位置不变(views/monitor/live)
UPDATE sys_menu SET parent_id = 0, path = '/live', sort_order = 1 WHERE id = 2;
--    删除目录「监控预览」(1) 及其对各角色的授权行(实时预览 2 已提为根, 不受影响)
DELETE FROM sys_role_menu WHERE menu_id = 1;
DELETE FROM sys_menu WHERE id = 1;

-- 4. 「会话监控」更名「在线用户」(path/组件/权限点均不变)
UPDATE sys_menu SET menu_name = '在线用户' WHERE id = 14;

-- 5. 角色补授权(幂等)
INSERT IGNORE INTO sys_role_menu(role_id, menu_id)
  SELECT r.id, m.id FROM sys_role r, sys_menu m
  WHERE r.role_code = 'admin' AND m.id = 40;
INSERT IGNORE INTO sys_role_menu(role_id, menu_id)
  SELECT r.id, m.id FROM sys_role r, sys_menu m
  WHERE r.role_code = 'operator' AND m.id = 40;

-- 6. 结果自检: 目录树应为 实时预览(根叶子) + 设备接入(40, 2 子) + 系统管理(10, 7 子)
SELECT id, parent_id, menu_name, path, sort_order FROM sys_menu
  WHERE parent_id = 0 AND deleted_time IS NULL ORDER BY sort_order;
