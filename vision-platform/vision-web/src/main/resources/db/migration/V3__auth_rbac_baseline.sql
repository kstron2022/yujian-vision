-- =====================================================================
-- V3: M1-9 平台功能·后端 基线（docs/03 §2 / docs/10 §1 / 决策 D11）
--   1. sys_user 增加 must_change_pwd（首登/被重置后强制改密，等保身份鉴别基线）
--   2. 菜单树 + 内置角色(admin/operator/viewer) + 角色授权种子（决策：种子入迁移，可重复部署）
-- 约定：perm_code 既是 sys_menu 权限标识，也是后端 @PreAuthorize 的 authority
-- =====================================================================

-- ---------------------------------------------------------------- 1. 强制改密标记
ALTER TABLE sys_user
  ADD COLUMN must_change_pwd TINYINT DEFAULT 0 COMMENT '1=需强制改密(首登/被重置)' AFTER status;

-- 已存在的初始 admin（骨架阶段创建）同样要求首次登录改密
UPDATE sys_user SET must_change_pwd = 1 WHERE username = 'admin' AND must_change_pwd = 0;

-- ---------------------------------------------------------------- 2. 菜单树种子（menu_type: 1目录 2菜单 3按钮）
-- 列表类查询权限放在菜单行(perm_code)；操作类按钮权限放按钮行。前端 menus[] 建路由、perms[] 控按钮。
INSERT INTO sys_menu(id, parent_id, menu_name, menu_type, path, component, perm_code, icon, sort_order, visible, status) VALUES
  -- 监控预览
  (1,  0, '监控预览', 1, '/monitor', '', '', 'VideoCameraOutlined', 1, 1, 1),
  (2,  1, '实时预览', 2, 'live', 'monitor/live/index', '', '', 1, 1, 1),
  (201, 2, '开始点播', 3, '', '', 'device:play', '', 1, 1, 1),
  (202, 2, '停止点播', 3, '', '', 'play:stop', '', 2, 1, 1),
  (203, 2, '云台控制', 3, '', '', 'ptz:control', '', 3, 1, 1),
  (3,  1, '设备管理', 2, 'device', 'monitor/device/index', '', '', 2, 1, 1),
  (301, 3, '目录刷新', 3, '', '', 'device:refresh', '', 1, 1, 1),
  (302, 3, '设备编辑', 3, '', '', 'device:update', '', 2, 1, 1),
  (303, 3, '设备删除', 3, '', '', 'device:delete', '', 3, 1, 1),
  (304, 3, '通道改名', 3, '', '', 'channel:update', '', 4, 1, 1),
  -- 系统管理
  (10, 0, '系统管理', 1, '/system', '', '', 'SettingOutlined', 10, 1, 1),
  (11, 10, '用户管理', 2, 'user', 'system/user/index', 'sys:user:list', '', 1, 1, 1),
  (1101, 11, '用户新增', 3, '', '', 'sys:user:add', '', 1, 1, 1),
  (1102, 11, '用户编辑', 3, '', '', 'sys:user:update', '', 2, 1, 1),
  (1103, 11, '用户删除', 3, '', '', 'sys:user:delete', '', 3, 1, 1),
  (1104, 11, '重置密码', 3, '', '', 'sys:user:reset-pwd', '', 4, 1, 1),
  (1105, 11, '分配角色', 3, '', '', 'sys:user:assign-role', '', 5, 1, 1),
  (12, 10, '角色管理', 2, 'role', 'system/role/index', 'sys:role:list', '', 2, 1, 1),
  (1201, 12, '角色新增', 3, '', '', 'sys:role:add', '', 1, 1, 1),
  (1202, 12, '角色编辑', 3, '', '', 'sys:role:update', '', 2, 1, 1),
  (1203, 12, '角色删除', 3, '', '', 'sys:role:delete', '', 3, 1, 1),
  (1204, 12, '角色授权', 3, '', '', 'sys:role:auth', '', 4, 1, 1),
  (13, 10, '菜单管理', 2, 'menu', 'system/menu/index', 'sys:menu:list', '', 3, 1, 1),
  (1301, 13, '菜单新增', 3, '', '', 'sys:menu:add', '', 1, 1, 1),
  (1302, 13, '菜单编辑', 3, '', '', 'sys:menu:update', '', 2, 1, 1),
  (1303, 13, '菜单删除', 3, '', '', 'sys:menu:delete', '', 3, 1, 1),
  (14, 10, '会话监控', 2, 'online', 'system/online/index', 'sys:online:list', '', 4, 1, 1),
  (1401, 14, '强制下线', 3, '', '', 'sys:online:kick', '', 1, 1, 1),
  (15, 10, '操作日志', 2, 'oper-log', 'system/operlog/index', 'log:oper:list', '', 5, 1, 1),
  (16, 10, '登录日志', 2, 'login-log', 'system/loginlog/index', 'log:login:list', '', 6, 1, 1),
  (20, 10, '流媒体节点', 2, 'media-server', 'system/mediaserver/index', 'server:list', '', 7, 1, 1),
  (2001, 20, '节点新增', 3, '', '', 'server:add', '', 1, 1, 1),
  (2002, 20, '节点编辑', 3, '', '', 'server:update', '', 2, 1, 1),
  (2003, 20, '节点删除', 3, '', '', 'server:delete', '', 3, 1, 1);

-- ---------------------------------------------------------------- 3. 内置角色（admin 若已由 AdminInitializer 建过则幂等跳过）
INSERT INTO sys_role(role_code, role_name, remark) VALUES
  ('operator', '操作员', '内置角色: 可预览/云台/设备编辑刷新'),
  ('viewer',   '只读用户', '内置角色: 仅实时预览')
  ON DUPLICATE KEY UPDATE role_name = VALUES(role_name);

-- ---------------------------------------------------------------- 4. 角色授权（docs/03 §2.1 内置矩阵）
-- admin: 全量菜单+按钮（以 SELECT 关联，AdminInitializer 何时建 admin 角色均可）
INSERT INTO sys_role_menu(role_id, menu_id)
  SELECT r.id, m.id FROM sys_role r, sys_menu m WHERE r.role_code = 'admin';

-- operator: 预览+云台+停止, 设备目录刷新/编辑（不含删除）
INSERT INTO sys_role_menu(role_id, menu_id)
  SELECT r.id, m.id FROM sys_role r, sys_menu m
  WHERE r.role_code = 'operator' AND m.id IN (1, 2, 3, 201, 202, 203, 301, 302);

-- viewer: 仅实时预览+停止
INSERT INTO sys_role_menu(role_id, menu_id)
  SELECT r.id, m.id FROM sys_role r, sys_menu m
  WHERE r.role_code = 'viewer' AND m.id IN (1, 2, 201, 202);
