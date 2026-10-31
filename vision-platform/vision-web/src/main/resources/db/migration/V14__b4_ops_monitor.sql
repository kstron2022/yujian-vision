-- =====================================================================
-- V14: M2-B4 运维监控入口（docs/09 M2 #8, V8 前瞻预留的 id=70 段位）
--   1. 一级目录「运维监控」(70) + 叶子页「监控概览」(71, monitor:view)
--      数据源 GET /api/metrics/overview —— NodeMetricsTask 内存快照直出,
--      历史曲线/告警仍走旁路 Prometheus + Grafana, 平台不落库。
--   2. sort_order=4: 排在 录像回放(3) 之后、系统管理(10) 之前(V3/V8 现序)。
--   3. 授权: admin 需显式(V4 全量授权只跑过一次, 不覆盖新增行, V10 教训同源);
--      operator 可看不可改; viewer 不授(只读预览角色, 运维面不开放)。
--      按 id 显式授权(幂等) —— V10 教训: 按 perm_code 关联会漏父目录。
-- =====================================================================

INSERT INTO sys_menu(id, parent_id, menu_name, menu_type, path, component, perm_code, icon, sort_order, visible, status) VALUES
  (70, 0, '运维监控', 1, '/ops', '', '', 'DashboardOutlined', 4, 1, 1),
  (71, 70, '监控概览', 2, 'overview', 'ops/overview/index', 'monitor:view', '', 1, 1, 1);

-- 角色授权(幂等, 显式 id)
INSERT IGNORE INTO sys_role_menu(role_id, menu_id)
  SELECT r.id, m.id FROM sys_role r, sys_menu m
  WHERE r.role_code IN ('admin', 'operator') AND m.id IN (70, 71);

-- ---------------------------------------------------------------- 结果自检
SELECT id, parent_id, menu_name, path, component, perm_code FROM sys_menu WHERE id IN (70, 71);
SELECT r.role_code, m.id AS menu_id, m.perm_code FROM sys_role_menu rm
  JOIN sys_role r ON r.id = rm.role_id JOIN sys_menu m ON m.id = rm.menu_id
  WHERE m.id IN (70, 71) ORDER BY r.role_code, m.id;
-- 目录树应为 实时预览(1) + 设备接入(40) + 录像回放(50) + 运维监控(70) + 系统管理(10)
SELECT id, parent_id, menu_name, sort_order FROM sys_menu
  WHERE parent_id = 0 AND deleted_time IS NULL ORDER BY sort_order;

-- ---------------------------------------------------------------- 回滚(手动执行, Flyway 不跑注释)
-- DELETE FROM sys_role_menu WHERE menu_id IN (70, 71);
-- DELETE FROM sys_menu WHERE id IN (70, 71);
