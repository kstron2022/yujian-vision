-- =====================================================================
-- V10: B1 评审修复（P1 权限缺陷, e2e-T12 抓出）
--   V9 的授权只按 perm_code='record:query' 命中菜单 51, 目录 50（录像回放）
--   未授给任何角色（V4 的 admin 全量授权只跑过一次, 不覆盖新增行）。
--   菜单树构建时子节点因父目录缺失被整树丢弃 —— 三个角色都看不到「录像回放」。
--   修复: 补齐 50/51 对 admin/operator/viewer 的授权（幂等）。
-- =====================================================================
INSERT IGNORE INTO sys_role_menu(role_id, menu_id)
  SELECT r.id, m.id FROM sys_role r, sys_menu m
  WHERE r.role_code IN ('admin', 'operator', 'viewer') AND m.id IN (50, 51);

-- 结果自检: 三角色 × 2 菜单 = 6 行
SELECT r.role_code, m.id FROM sys_role_menu rm
  JOIN sys_role r ON r.id = rm.role_id JOIN sys_menu m ON m.id = rm.menu_id
  WHERE m.id IN (50, 51) ORDER BY r.role_code, m.id;
