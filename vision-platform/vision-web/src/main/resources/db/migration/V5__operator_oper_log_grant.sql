-- =====================================================================
-- V5: B5 契约核对修复 —— §2.1 权限矩阵对齐
--   docs/03 §2.1「操作日志 operator=查看」为设计意图, V3 种子漏授
--   （operator 仅授 1,2,3,201,202,301,302）。本迁移补齐 menu_id=15（操作日志,
--   perm_code=log:oper:list）。幂等: INSERT IGNORE。
--   登录日志(log:login:list)按矩阵仍为 admin 专属, 不授。
-- =====================================================================

INSERT IGNORE INTO sys_role_menu(role_id, menu_id)
  SELECT r.id, m.id FROM sys_role r, sys_menu m
  WHERE r.role_code = 'operator' AND m.id = 15;
