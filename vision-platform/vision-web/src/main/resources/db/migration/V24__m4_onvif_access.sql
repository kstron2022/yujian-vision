-- =====================================================================
-- V24: M4-B1 ONVIF 接入（docs/13 §4 数据模型, 2026-09-18 定稿）
--   复用 gb_device/gb_device_channel 加列不加表: 分组树/数据权限/级联共享/
--   预览回放页全部按通道 db id 关联, 复用表即全量继承。
--   gb_device.access_protocol='ONVIF' 行: ip/port 语义=设备 IP+HTTP 端口(默认 80),
--   device_id 为合成 20 位编码(域前缀+132+序列, OnvifCodeAllocator 生成),
--   onvif_password 口径同 gb_device.password(V6 教训取 255)。
--   gb_device_channel.channel_id 对 ONVIF 通道同样存合成 20 位编码
--   (目录树/级联上报/分组统一吃这列); onvif_profiles 缓存 GetProfiles 结果 JSON。
-- =====================================================================

ALTER TABLE gb_device
  ADD COLUMN access_protocol VARCHAR(8) NOT NULL DEFAULT 'GB' COMMENT '接入协议 GB/ONVIF' AFTER protocol_version,
  ADD COLUMN onvif_username  VARCHAR(64)  DEFAULT '' COMMENT 'ONVIF 用户名' AFTER access_protocol,
  ADD COLUMN onvif_password  VARCHAR(255) DEFAULT '' COMMENT 'ONVIF 密码(AES 密文, 口径同 password 列)' AFTER onvif_username;

ALTER TABLE gb_device_channel
  ADD COLUMN onvif_source_token  VARCHAR(64) DEFAULT '' COMMENT 'ONVIF VideoSource token(通道身份键, 目录收敛按它匹配)' AFTER mobile,
  ADD COLUMN onvif_profile_token VARCHAR(64) DEFAULT '' COMMENT 'ONVIF 主码流 Profile token' AFTER onvif_source_token,
  ADD COLUMN onvif_profiles      TEXT        COMMENT 'ONVIF Profiles 缓存 JSON(token/分辨率/编码, 主/子档位按分辨率降序)' AFTER onvif_profile_token;

-- ---------------------------------------------------------------- 结果自检
SELECT access_protocol, COUNT(*) FROM gb_device WHERE deleted_time IS NULL GROUP BY access_protocol;

-- ---------------------------------------------------------------- 回滚(手动执行, Flyway 不跑注释)
-- ALTER TABLE gb_device DROP COLUMN access_protocol, DROP COLUMN onvif_username, DROP COLUMN onvif_password;
-- ALTER TABLE gb_device_channel DROP COLUMN onvif_source_token, DROP COLUMN onvif_profile_token, DROP COLUMN onvif_profiles;
