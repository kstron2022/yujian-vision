-- =====================================================================
-- V9: B1 批次（M2-1 录像检索 + M2-9 消息中心）
--   1. 菜单: 「录像回放」目录(50) + 「录像检索」页(51), 权限点 record:query
--      （docs/03 §2.1 矩阵: 录像回放 admin ✅ / operator ✅ / viewer 查看）
--   2. 消息中心双表: sys_notice(通知事件, 带 tenant_id) +
--      sys_user_message(用户已读态, 纯关联表 —— 加入租户插件白名单, 见 MybatisPlusConfig)
-- =====================================================================

-- 1. 菜单与权限点
INSERT INTO sys_menu(id, parent_id, menu_name, menu_type, path, component, perm_code, icon, sort_order, visible, status) VALUES
  (50, 0, '录像回放', 1, '/record', '', '', 'PlayCircleOutlined', 3, 1, 1),
  (51, 50, '录像检索', 2, 'query', 'record/query/index', 'record:query', '', 1, 1, 1);

INSERT IGNORE INTO sys_role_menu(role_id, menu_id)
  SELECT r.id, m.id FROM sys_role r, sys_menu m
  WHERE r.role_code IN ('admin', 'operator', 'viewer') AND m.perm_code = 'record:query';

-- 2. 站内通知事件表
CREATE TABLE sys_notice (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  tenant_id    BIGINT       NOT NULL DEFAULT 0,
  type         VARCHAR(32)  NOT NULL COMMENT '事件类型: DEVICE_OFFLINE/CATALOG_SYNC_FAIL/ALARM(M3)',
  title        VARCHAR(128) NOT NULL,
  content      VARCHAR(512) NOT NULL DEFAULT '',
  level        TINYINT      NOT NULL DEFAULT 1 COMMENT '1=信息 2=重要',
  biz_id       VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '业务主键, 如设备 deviceId',
  create_time  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  deleted_time DATETIME(3)  NULL,
  PRIMARY KEY (id),
  KEY idx_notice_create (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='站内通知事件';

-- 3. 用户消息(已读态): 扇出行, 一用户一通知一行
CREATE TABLE sys_user_message (
  id         BIGINT      NOT NULL AUTO_INCREMENT,
  notice_id  BIGINT      NOT NULL,
  user_id    BIGINT      NOT NULL,
  read_time  DATETIME(3) NULL,
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_notice (user_id, notice_id),
  KEY idx_user_read (user_id, read_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户消息已读态';
