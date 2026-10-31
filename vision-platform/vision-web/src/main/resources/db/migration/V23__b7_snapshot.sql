-- =====================================================================
-- V23: M3-B7 阶段2 服务端抓图（docs/09 M3-B7）: gb_channel_snapshot 通道最新截图元数据
--   设计（docs/09 风险条目）: 图片不入库 BLOB——备份膨胀/查询变慢; 落盘
--   {vision.snapshot.dir}/{channelDbId}_{epochMs}.jpg（原图）与 _t.jpg（缩略图宽160）。
--   PK=channel_db_id 每通道只留最新一张（写新删旧）, 容量有界:
--   1000 通道 ×（原图~150KB + 缩略图~8KB）≈ 160MB, 不随时间增长。
-- =====================================================================

-- 幂等依赖 Flyway 版本表防重放（V22 句式）
CREATE TABLE gb_channel_snapshot (
  channel_db_id BIGINT       NOT NULL COMMENT '通道库内ID(gb_device_channel.id)',
  tenant_id     BIGINT       NOT NULL DEFAULT 0 COMMENT '租户ID(v1 单域固定 0, D12)',
  file_name     VARCHAR(128) NOT NULL COMMENT '原图文件名 {channelDbId}_{epochMs}.jpg',
  thumb_name    VARCHAR(128) DEFAULT NULL COMMENT '缩略图文件名 {channelDbId}_{epochMs}_t.jpg（缩略失败 NULL, 列表回退原图）',
  capture_time  DATETIME     NOT NULL,
  source        VARCHAR(8)   NOT NULL COMMENT '来源: AUTO=点播自动 IDLE=空闲抓图 MANUAL=手动',
  server_id     VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '抓图时流所在媒体节点',
  width         INT          DEFAULT NULL,
  height        INT          DEFAULT NULL,
  size_bytes    BIGINT       DEFAULT NULL,
  PRIMARY KEY (channel_db_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通道最新截图（M3-B7 阶段2, 每通道只留最新一张）';

-- 结果自检: 表存在即通过（V21/V22 句式）
SELECT IF(COUNT(*) = 1, 'OK', 'FAIL: gb_channel_snapshot 未建成') AS v23_check FROM information_schema.tables
 WHERE table_schema = DATABASE() and table_name = 'gb_channel_snapshot';

-- 回滚: DROP TABLE gb_channel_snapshot;（图片文件与库行一一对应, 挂账无; 目录残留文件可手工清）

