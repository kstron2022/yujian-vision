-- =====================================================================
-- V15: M3-B1 级联-下级基础（docs/09 M3 B1, docs/02 §2.7/§2.8, docs/08 §2）
--   1. 表: gb_cascade_upper(上级平台配置) / gb_cascade_shared_channel(共享通道)
--      password 列 VARCHAR(255) —— V6 教训: 128 容不下 AES 密文(约131字符)。
--      配置表不设软删列(同 sys_role_group 口径), 删除即物理删。
--   2. 菜单: 设备接入(40) 下二级页「级联上级」(22, cascade:list, access/cascade/index)
--      + 按钮 2201「级联配置」(cascade:config: 增删改/启停/推送目录/共享通道)。
--   3. 授权: 仅 admin(配置类页面, operator/viewer 不授; 按 id 显式授权, V10 教训)。
-- =====================================================================

CREATE TABLE IF NOT EXISTS gb_cascade_upper (
  id                  BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  name                VARCHAR(128) NOT NULL COMMENT '上级平台名称',
  tenant_id           BIGINT       NOT NULL DEFAULT 0,
  upper_device_id     VARCHAR(50)  NOT NULL COMMENT '上级平台国标编码',
  local_device_id     VARCHAR(50)  NOT NULL COMMENT '本平台在上级侧的国标编码',
  upper_ip            VARCHAR(64)  NOT NULL,
  upper_port          INT          NOT NULL DEFAULT 5060,
  password            VARCHAR(255) DEFAULT '' COMMENT '级联密码(AES 密文)',
  transport           VARCHAR(8)   DEFAULT 'UDP',
  expires             INT          DEFAULT 3600,
  keepalive_interval  INT          DEFAULT 60,
  catalog_mode        TINYINT      DEFAULT 1 COMMENT '目录上报 1:仅共享通道 2:全部通道',
  auto_push_catalog   TINYINT      DEFAULT 0 COMMENT '目录变化时主动通知上级',
  ptz_forward         TINYINT      DEFAULT 1 COMMENT '上级云台指令透传(B2 中转使用)',
  status              TINYINT      DEFAULT 0 COMMENT '0未启用 1启用',
  online              TINYINT      DEFAULT 0 COMMENT '上级注册状态',
  register_time       DATETIME     DEFAULT NULL,
  create_time         DATETIME     DEFAULT CURRENT_TIMESTAMP,
  update_time         DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_upper_device (upper_device_id)
) COMMENT '上级平台(本平台作为下级注册)';

CREATE TABLE IF NOT EXISTS gb_cascade_shared_channel (
  id                BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  upper_id          BIGINT UNSIGNED NOT NULL COMMENT 'gb_cascade_upper.id',
  tenant_id           BIGINT       NOT NULL DEFAULT 0,
  channel_db_id     BIGINT UNSIGNED NOT NULL COMMENT '本平台通道id',
  virtual_device_id VARCHAR(50)  DEFAULT '' COMMENT '映射给上级的虚拟编码(空=用原编码)',
  enabled           TINYINT      DEFAULT 1,
  create_time       DATETIME     DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_upper_ch (upper_id, channel_db_id)
) COMMENT '共享给上级的通道';

INSERT INTO sys_menu(id, parent_id, menu_name, menu_type, path, component, perm_code, icon, sort_order, visible, status) VALUES
  (22, 40, '级联上级', 2, 'cascade', 'access/cascade/index', 'cascade:list', '', 4, 1, 1),
  (2201, 22, '级联配置', 3, '', '', 'cascade:config', '', 1, 1, 1);

INSERT IGNORE INTO sys_role_menu(role_id, menu_id)
  SELECT r.id, m.id FROM sys_role r, sys_menu m
  WHERE r.role_code IN ('admin') AND m.id IN (22, 2201);

-- ---------------------------------------------------------------- 结果自检
SELECT id, parent_id, menu_name, path, component, perm_code FROM sys_menu WHERE id IN (22, 2201);
SELECT r.role_code, m.id AS menu_id FROM sys_role_menu rm
  JOIN sys_role r ON r.id = rm.role_id JOIN sys_menu m ON m.id = rm.menu_id
  WHERE m.id IN (22, 2201) ORDER BY r.role_code, m.id;
SELECT COUNT(*) AS uppers FROM gb_cascade_upper;
SELECT COUNT(*) AS shared FROM gb_cascade_shared_channel;

-- ---------------------------------------------------------------- 回滚(手动执行, Flyway 不跑注释)
-- DELETE FROM sys_role_menu WHERE menu_id IN (22, 2201);
-- DELETE FROM sys_menu WHERE id IN (22, 2201);
-- DROP TABLE IF EXISTS gb_cascade_shared_channel;
-- DROP TABLE IF EXISTS gb_cascade_upper;
