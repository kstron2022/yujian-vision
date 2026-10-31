-- =====================================================================
-- V21: M3-B4 报警域入口（docs/09 M3-B4, V8 前瞻预留的 id=60 段位）
--   1. 一级目录「报警中心」(60) + 叶子页「报警列表」(61, alarm:view)
--      + 处理按钮 (6101, alarm:handle, 命名沿用 V13 的 510x=parent*100+n 风格)。
--      数据源 GET /api/alarms + PUT /api/alarms/{id}/handle —— gb_alarm_event
--      (V1 建表) 由 M3-B4 信令链路(Alarm 订阅/上报)写入。
--   2. sort_order=5: 排在 运维监控(4, V14) 之后、系统管理(10) 之前(V3/V8/V14 现序)。
--   3. 授权: admin 需显式(V4 全量授权只跑过一次, 不覆盖新增行, V10 教训同源);
--      operator 同授(报警查看+处理属值守职责); viewer 不授。
--      按 id 显式授权(幂等) —— V10 教训: 按 perm_code 关联会漏父目录。
--   4. gb_alarm_event.handle_user BIGINT → VARCHAR(64): B4 契约存处理人登录名
--      (非用户 id); 该列在 B4 前无任何写入方(恒 NULL), 调整无存量数据风险。
--   5. 字典 alarm_type 种子: 国标 GB/T 28181 报警依据类型 1~8 常用值
--      (完整国标类型表可后续扩充, 追加 INSERT IGNORE 行即可)。
-- =====================================================================

-- 1. 报警中心菜单(目录/页面/按钮)
INSERT INTO sys_menu(id, parent_id, menu_name, menu_type, path, component, perm_code, icon, sort_order, visible, status) VALUES
  (60, 0, '报警中心', 1, '/alarm', '', '', 'AlertOutlined', 5, 1, 1),
  (61, 60, '报警列表', 2, 'alarm-list', 'alarm/index', 'alarm:view', '', 1, 1, 1),
  (6101, 61, '报警处理', 3, '', '', 'alarm:handle', '', 1, 1, 1);

-- 2. handle_user 列类型对齐(B4 契约: 处理人用户名; MODIFY 天然幂等)
ALTER TABLE gb_alarm_event
  MODIFY COLUMN handle_user VARCHAR(64) DEFAULT NULL COMMENT '处理人用户名(B4: 存登录名, 原BIGINT无写入方)';

-- 3. 角色授权(幂等, 显式 id)
INSERT IGNORE INTO sys_role_menu(role_id, menu_id)
  SELECT r.id, m.id FROM sys_role r, sys_menu m
  WHERE r.role_code IN ('admin', 'operator') AND m.id IN (60, 61, 6101);

-- 4. 报警类型字典(国标报警依据类型 1~8; dict_type 行 V1 已建, 幂等补数据行)
INSERT IGNORE INTO sys_dict_data(dict_type, dict_value, dict_label, sort_order) VALUES
  ('alarm_type', '1', '视频报警', 1),
  ('alarm_type', '2', '设备故障', 2),
  ('alarm_type', '3', '围墙门窗', 3),
  ('alarm_type', '4', '入侵报警', 4),
  ('alarm_type', '5', '求助', 5),
  ('alarm_type', '6', '珠宝箱报警', 6),
  ('alarm_type', '7', '火警', 7),
  ('alarm_type', '8', '紧急报警', 8);
-- 注: 以上为常用 1~8 值, 完整国标报警依据类型表可后续扩充(INSERT IGNORE 追加即可)。

-- ---------------------------------------------------------------- 结果自检
SELECT id, parent_id, menu_name, path, component, perm_code FROM sys_menu WHERE id IN (60, 61, 6101);
SELECT r.role_code, m.id AS menu_id, m.perm_code FROM sys_role_menu rm
  JOIN sys_role r ON r.id = rm.role_id JOIN sys_menu m ON m.id = rm.menu_id
  WHERE m.id IN (60, 61, 6101) ORDER BY r.role_code, m.id;
SELECT dict_value, dict_label, sort_order FROM sys_dict_data
  WHERE dict_type = 'alarm_type' ORDER BY sort_order;
SHOW COLUMNS FROM gb_alarm_event LIKE 'handle_user';
-- 目录树应为 实时预览(1) + 设备接入(40) + 录像回放(50) + 运维监控(70) + 报警中心(60) + 系统管理(10)
SELECT id, parent_id, menu_name, sort_order FROM sys_menu
  WHERE parent_id = 0 AND deleted_time IS NULL ORDER BY sort_order;

-- ---------------------------------------------------------------- 回滚(手动执行, Flyway 不跑注释)
-- DELETE FROM sys_dict_data WHERE dict_type = 'alarm_type' AND dict_value IN ('1','2','3','4','5','6','7','8');
-- DELETE FROM sys_role_menu WHERE menu_id IN (60, 61, 6101);
-- DELETE FROM sys_menu WHERE id IN (60, 61, 6101);
-- ALTER TABLE gb_alarm_event MODIFY COLUMN handle_user BIGINT DEFAULT NULL;
