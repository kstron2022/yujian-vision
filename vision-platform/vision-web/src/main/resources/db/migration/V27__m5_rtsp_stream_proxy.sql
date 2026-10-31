-- =====================================================================
-- V27: M5-B1 拉流代理接入（docs/14 §4, 2026-09-19 定稿）
--   批量独立 RTSP 流地址以设备形态接入: gb_device.access_protocol='RTSP'
--   单设备单通道(IPC 形态), 通道行后端自动建, 国标编码复用 OnvifCodeAllocator
--   合成(域前缀+132+序列), 供级联共享目录上报。
--   source_url 口径同 password 列(V6 教训取宽): rtsp://user:pass@host/path
--   整串 AES-GCM 加密存储, 编辑盲显(留空=不修改)。
--   rtsp_transport 透传 ZLM addStreamProxy rtp_type(0=TCP 1=UDP), 默认 TCP 抗丢包。
--   always_on 常驻拉流: 0=按需(有观看/上级 INVITE 才拉流, 与 ONVIF 会话生命周期对称)
--   1=常驻(启动拉起+断线收敛重建, 会话清理豁免, 独立索引 gb:proxy:always:{streamId})。
-- =====================================================================

ALTER TABLE gb_device
  ADD COLUMN source_url VARCHAR(768) DEFAULT NULL
    COMMENT 'RTSP拉流地址(AES密文, rtsp://user:pass@host 整串加密, 口径同 password 列; 仅 access_protocol=RTSP 行使用)' AFTER onvif_password,
  ADD COLUMN rtsp_transport VARCHAR(8) NOT NULL DEFAULT 'tcp'
    COMMENT 'RTSP拉流传输 tcp/udp(默认TCP抗丢包; 透传 ZLM addStreamProxy rtp_type)' AFTER source_url,
  ADD COLUMN always_on TINYINT NOT NULL DEFAULT 0
    COMMENT '常驻拉流 0=按需(默认) 1=常驻(启动拉起+断线重建, 观众走光不断流)' AFTER rtsp_transport;

-- ---------------------------------------------------------------- 结果自检
SELECT access_protocol, COUNT(*) FROM gb_device WHERE deleted_time IS NULL GROUP BY access_protocol;
SELECT rtsp_transport, always_on, COUNT(*) FROM gb_device WHERE access_protocol = 'RTSP' AND deleted_time IS NULL GROUP BY rtsp_transport, always_on;

-- ---------------------------------------------------------------- 回滚(手动执行, Flyway 不跑注释)
-- ALTER TABLE gb_device DROP COLUMN source_url, DROP COLUMN rtsp_transport, DROP COLUMN always_on;
