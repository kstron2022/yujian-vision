-- V6: 敏感字段密文长度扩展（C4 安全基线, docs/02 §0/§2.3）
-- AES-256-GCM 密文格式 enc:v1:{Base64(iv12B+ct+tag16B)}: 64 位明文加密后约 131 字符,
-- 原 sign_secret VARCHAR(64)/password VARCHAR(128) 容不下密文, 统一扩到 255。
ALTER TABLE gb_media_server MODIFY COLUMN sign_secret VARCHAR(255) DEFAULT '' COMMENT '播放URL签名密钥(每节点独立,AES存储,可轮换)';
ALTER TABLE gb_device MODIFY COLUMN password VARCHAR(255) DEFAULT '' COMMENT '接入密码(Digest认证,AES加密存储)';
