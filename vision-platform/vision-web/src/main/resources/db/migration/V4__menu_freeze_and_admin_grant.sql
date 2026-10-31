-- =====================================================================
-- V4: M1-9 评审修复（决策 D22）
--   1. 【部署阻断修复】全新数据库上 V3 授权 admin 时 sys_role 尚无 admin 行
--      （AdminInitializer 在 Flyway 之后才运行），导致 admin 零权限。
--      本迁移补齐：先补插 admin 角色（幂等），再以 INSERT IGNORE 全量授权（幂等）。
--      注意：AdminInitializer 中的 ON DUPLICATE 建角色语句因此变为兜底，保留。
--   2. 【D22 菜单定稿式】菜单/权限点随版本种子演进，运行期仅保留"查看树+角色授权"。
--      收回全部角色的 sys:menu:add/update/delete 授权（按钮与权限点保留，接口保留作兜底）。
-- =====================================================================

-- 1. admin 角色补插（幂等；全新库上 V4 先于 AdminInitializer 执行, 保证角色存在）
INSERT INTO sys_role(role_code, role_name, remark)
VALUES ('admin', '系统管理员', '内置角色')
ON DUPLICATE KEY UPDATE role_name = VALUES(role_name);

-- 2. admin 全量授权（幂等补差; 主键 (role_id, menu_id) 冲突自动跳过）
INSERT IGNORE INTO sys_role_menu(role_id, menu_id)
  SELECT r.id, m.id FROM sys_role r, sys_menu m WHERE r.role_code = 'admin';

-- 3. D22: 按权限码收回菜单增删改授权（含未来可能误授的角色）
DELETE rm FROM sys_role_menu rm
  JOIN sys_menu m ON m.id = rm.menu_id
  WHERE m.perm_code IN ('sys:menu:add', 'sys:menu:update', 'sys:menu:delete');

-- 4. 等保最小授权自检：本次操作后 admin 亦不应持有上述权限点
SELECT 'admin-menu-crud-grants-remaining' AS check_item, COUNT(*) AS remaining
  FROM sys_role_menu rm
  JOIN sys_role r ON r.id = rm.role_id
  JOIN sys_menu m ON m.id = rm.menu_id
  WHERE r.role_code = 'admin'
    AND m.perm_code IN ('sys:menu:add', 'sys:menu:update', 'sys:menu:delete');
