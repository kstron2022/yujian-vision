-- =====================================================================
-- V13: M2-B3 云端录像按钮权限（docs/09 M2 #3）
--   表 gb_cloud_record / gb_record_plan V1 已建, 本迁移仅补按钮种子:
--   5101 云端录像删除(cloud-record:delete) / 5102 录像计划管理(record-plan:manage)
--   5103 云端录像下载(cloud-record:download, B3 评审 P1: 下载令牌签发由 record:query 收紧为独立按钮)
--   文件下载走一次性 token 免登录(token 即凭证 + IP 限流), 列表复用既有 record:query(V9 已授权)。
-- 约定: 按钮挂在「录像检索」菜单 51 下(V9), id 按 V12 段位 {menuId}01+; 授权幂等。
-- =====================================================================

INSERT INTO sys_menu(id, parent_id, menu_name, menu_type, path, component, perm_code, icon, sort_order, visible, status) VALUES
  (5101, 51, '云端录像删除', 3, '', '', 'cloud-record:delete', '', 1, 1, 1),
  (5102, 51, '录像计划管理', 3, '', '', 'record-plan:manage', '', 2, 1, 1),
  (5103, 51, '云端录像下载', 3, '', '', 'cloud-record:download', '', 3, 1, 1);

-- 角色授权(幂等, 显式 id —— V10 教训: 按 perm_code 关联会漏; 父菜单 51 V10 已授 admin/operator/viewer)
INSERT IGNORE INTO sys_role_menu(role_id, menu_id)
  SELECT r.id, m.id FROM sys_role r, sys_menu m
  WHERE r.role_code IN ('admin', 'operator') AND m.id IN (5101, 5102, 5103);

-- ---------------------------------------------------------------- 结果自检
SELECT id, parent_id, menu_name, perm_code FROM sys_menu WHERE id IN (5101, 5102, 5103);
SELECT r.role_code, m.id AS menu_id, m.perm_code FROM sys_role_menu rm
  JOIN sys_role r ON r.id = rm.role_id JOIN sys_menu m ON m.id = rm.menu_id
  WHERE m.id IN (5101, 5102, 5103) ORDER BY r.role_code, m.id;
