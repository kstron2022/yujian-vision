-- ================================================================================
-- M5-OpenAPI 步骤5（docs/15）: 开放接口管理面菜单 + open_api_log 拒绝原因列
--   1. open_api_log 加 remark 列（401/403/429 拒绝原因文案, 管理页"签名失败原因"列数据源）
--   2. open_app.app_secret 列注释修正（建表时 AES 旧口径, 实为 SM4 "sm4$" 前缀密文）
--   3. 系统管理(10)下新增「开放接口」页(17) + 5 个按钮权限(1701~1705), 默认仅授权 admin
-- ================================================================================

ALTER TABLE open_api_log
  ADD COLUMN remark VARCHAR(255) NOT NULL DEFAULT '' COMMENT '拒绝原因(401/403/429)' AFTER cost_ms;

ALTER TABLE open_app
  MODIFY COLUMN app_secret VARCHAR(128) NOT NULL COMMENT 'SM4加密存储(sm4$前缀, docs/15 D3)';

-- ---------------------------------------------------------------- 菜单与权限点
INSERT INTO sys_menu(id, parent_id, menu_name, menu_type, path, component, perm_code, icon, sort_order, visible, status) VALUES
  (17, 10, '开放接口', 2, 'openapi', 'system/openapi/index', 'openapi:app:list', '', 7, 1, 1),
  (1701, 17, '应用新增', 3, '', '', 'openapi:app:create', '', 1, 1, 1),
  (1702, 17, '应用编辑', 3, '', '', 'openapi:app:update', '', 2, 1, 1),
  (1703, 17, '重置密钥', 3, '', '', 'openapi:app:reset', '', 3, 1, 1),
  (1704, 17, '应用删除', 3, '', '', 'openapi:app:delete', '', 4, 1, 1),
  (1705, 17, '调用日志', 3, '', '', 'openapi:log:list', '', 5, 1, 1);

-- 角色授权（幂等, 仅 admin——开放接口是管理敏感面）
INSERT IGNORE INTO sys_role_menu(role_id, menu_id)
  SELECT r.id, m.id FROM sys_role r, sys_menu m
  WHERE r.role_code = 'admin' AND m.id IN (17, 1701, 1702, 1703, 1704, 1705);

-- ---------------------------------------------------------------- 结果自检
SELECT id, parent_id, menu_name, path, component, perm_code FROM sys_menu WHERE id IN (17, 1701, 1702, 1703, 1704, 1705);
SELECT r.role_code, m.id AS menu_id, m.perm_code FROM sys_role_menu rm
  JOIN sys_role r ON r.id = rm.role_id JOIN sys_menu m ON m.id = rm.menu_id
  WHERE m.id IN (17, 1701, 1702, 1703, 1704, 1705) ORDER BY r.role_code, m.id;

-- ---------------------------------------------------------------- 回滚(手动执行, Flyway 不跑注释)
-- DELETE FROM sys_role_menu WHERE menu_id IN (17, 1701, 1702, 1703, 1704, 1705);
-- DELETE FROM sys_menu WHERE id IN (17, 1701, 1702, 1703, 1704, 1705);
-- ALTER TABLE open_api_log DROP COLUMN remark;
