-- =====================================================================
-- V17: M3-B3 阶段1 追加——国标接入配置（docs/08 §3.1）
--   gb_access_config 单行表(id=1): 自动建档设备的默认接入密码（AES 密文, 可空=用
--   vision.gb.default-device-password 内置值）。接入信息(serverId/domain/SIP端口)
--   保持配置文件管理, 此表仅存运行时可改项; 密码列口径同 gb_device.password(V6 教训取 255)。
-- =====================================================================

CREATE TABLE IF NOT EXISTS gb_access_config (
  id               BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id        BIGINT       NOT NULL DEFAULT 0 COMMENT '租户(决策 D12, 租户插件注入)',
  default_password VARCHAR(255) DEFAULT NULL COMMENT '自动建档默认接入密码(AES 密文, NULL=用内置默认)',
  update_time      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) COMMENT '国标接入配置(单行)';

INSERT INTO gb_access_config (id, default_password)
  SELECT 1, NULL WHERE NOT EXISTS (SELECT 1 FROM gb_access_config WHERE id = 1);

-- ---------------------------------------------------------------- 结果自检
SELECT id, default_password IS NULL AS using_builtin, update_time FROM gb_access_config;

-- ---------------------------------------------------------------- 回滚(手动执行, Flyway 不跑注释)
-- DROP TABLE IF EXISTS gb_access_config;
