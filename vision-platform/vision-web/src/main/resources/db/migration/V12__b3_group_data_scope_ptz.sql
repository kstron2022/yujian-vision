-- =====================================================================
-- V12: M2-B3 权限完善（docs/09 M2 #5 + M1-8 评审挂账三项）
--   1. 组织/分组管理落地: 菜单/按钮种子（gb_group/gb_group_channel_ref 表 V1 已建）
--   2. 通道级授权(数据权限): sys_role.data_scope + sys_role_group/sys_role_channel
--   3. PTZ 挂账: 通道级"强制云台"开关列（preset 查询 API/PTZ 限流不涉库）
-- 约定: 存量三角色 data_scope 默认 1=全部通道, 行为向后兼容;
--       仅当管理员把角色切为 2=自定义授权 后数据过滤才生效。
-- =====================================================================

-- ---------------------------------------------------------------- 1. 通道级"强制云台"开关
-- NULL=按 ptzType 自动(现状) 1=强制开启(设备未上报 PTZType 也允许控) 0=禁用(上报了也拒)
ALTER TABLE gb_device_channel
  ADD COLUMN ptz_enabled TINYINT DEFAULT NULL
  COMMENT '云台开关: NULL=按ptzType自动 1=强制开启 0=禁用' AFTER ptz_type;

-- ---------------------------------------------------------------- 2. 数据权限: 角色数据范围 + 授权关联
ALTER TABLE sys_role
  ADD COLUMN data_scope TINYINT DEFAULT 1
  COMMENT '数据范围: 1=全部通道 2=按分组/通道授权' AFTER remark;

-- 角色授权的分组(子树生效: 经 gb_group.ancestors 展开)
CREATE TABLE sys_role_group (
  id        BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  role_id   BIGINT UNSIGNED NOT NULL,
  group_id  BIGINT UNSIGNED NOT NULL COMMENT 'gb_group.id',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_role_grp (role_id, group_id),
  KEY idx_group (group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色-分组数据授权(data_scope=2 时生效)';

-- 角色直选的单通道(不经分组; 树上勾叶子时落这里)
CREATE TABLE sys_role_channel (
  id            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  role_id       BIGINT UNSIGNED NOT NULL,
  channel_db_id BIGINT UNSIGNED NOT NULL COMMENT 'gb_device_channel.id',
  create_time   DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_role_ch (role_id, channel_db_id),
  KEY idx_channel (channel_db_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色-通道数据授权(data_scope=2 时生效)';

-- ---------------------------------------------------------------- 3. 菜单/按钮种子（id 沿用 V3/V8 段位: 40 设备接入下 21=分组管理, 按钮按 {menuId}01+）
INSERT INTO sys_menu(id, parent_id, menu_name, menu_type, path, component, perm_code, icon, sort_order, visible, status) VALUES
  (21,   40, '分组管理', 2, 'group', 'access/group/index', 'group:list', '', 3, 1, 1),
  (2101, 21, '分组新增', 3, '', '', 'group:add', '', 1, 1, 1),
  (2102, 21, '分组编辑', 3, '', '', 'group:update', '', 2, 1, 1),
  (2103, 21, '分组删除', 3, '', '', 'group:delete', '', 3, 1, 1),
  (2104, 21, '通道绑定', 3, '', '', 'group:bind-channel', '', 4, 1, 1),
  (1205, 12, '数据授权', 3, '', '', 'sys:role:data-scope', '', 5, 1, 1);

-- 角色授权(幂等, 显式 id —— V10 教训: 目录/父节点必须一并授, 按 perm_code 关联会漏)
INSERT IGNORE INTO sys_role_menu(role_id, menu_id)
  SELECT r.id, m.id FROM sys_role r, sys_menu m
  WHERE r.role_code = 'admin' AND m.id IN (21, 2101, 2102, 2103, 2104, 1205);

-- ---------------------------------------------------------------- 4. 结果自检
SELECT id, parent_id, menu_name, perm_code FROM sys_menu WHERE id IN (21, 2101, 2102, 2103, 2104, 1205);
SELECT role_code, data_scope FROM sys_role;
