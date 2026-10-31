-- ================================================================================
-- M4 菜单图标修复（用户反馈 2026-09-19）
--   实时预览/报警中心 侧边栏图标与其它菜单撞车（均落到前端 IconApps 兜底）:
--   1. 实时预览(2): icon 为空(V3 建行即 ''), 前端查不到 → 兜底 IconApps;
--      补 VideoCameraOutlined(前端已有 IconVideoCamera 映射, 与原「监控预览」目录同源)。
--   2. 报警中心(60): icon=AlertOutlined, 但 Arco 图标库无 IconAlert → 兜底 IconApps;
--      改为 NotificationOutlined(前端补 IconNotification 映射, 报警通知语义)。
--   系统管理(10) SettingOutlined 映射 IconSettings 正常, 不动。
--   仅改 icon; path/component/权限引用均不变。
-- ================================================================================

UPDATE sys_menu SET icon = 'VideoCameraOutlined' WHERE id = 2 AND (icon IS NULL OR icon = '');

UPDATE sys_menu SET icon = 'NotificationOutlined' WHERE id = 60 AND icon = 'AlertOutlined';

-- ---------------------------------------------------------------- 结果自检
SELECT id, parent_id, menu_name, icon FROM sys_menu
  WHERE parent_id = 0 AND deleted_time IS NULL ORDER BY sort_order;

-- ---------------------------------------------------------------- 回滚(手动执行, Flyway 不跑注释)
-- UPDATE sys_menu SET icon = '' WHERE id = 2;
-- UPDATE sys_menu SET icon = 'AlertOutlined' WHERE id = 60;
