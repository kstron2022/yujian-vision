-- V7: zlm_version 扩容（C3）：版本串含分支/commit/构建时间（如 "master/296dceb (2026-09-03T13:23:48)" = 33 字符）
-- 超出原 VARCHAR(32), 放宽到 64。
ALTER TABLE gb_media_server MODIFY COLUMN zlm_version VARCHAR(64) DEFAULT '' COMMENT 'ZLM版本(on_server_started/keepalive补录更新)';
