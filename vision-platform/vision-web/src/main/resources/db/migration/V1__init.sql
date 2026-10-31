-- =====================================================================
-- YuJianVision 平台基线表结构
-- 对应设计文档: docs/02-数据库设计.md
-- 全局约定(docs/02 §0)落地:
--   1. 所有业务表含 tenant_id BIGINT NOT NULL DEFAULT 0 (决策 D12)
--   2. 软删表增加 deleted_time DATETIME NULL, 唯一键含 deleted_time (NULL 可重复)
--   3. 时区统一 Asia/Shanghai (连接串已带 serverTimezone)
-- =====================================================================

-- ---------------------------------------------------------------- gb_device 设备/下级平台
CREATE TABLE gb_device (
  id                  BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id           BIGINT       NOT NULL DEFAULT 0,
  device_id           VARCHAR(50)  NOT NULL COMMENT '国标20位设备编码(SIP用户名)',
  name                VARCHAR(128) DEFAULT '' COMMENT '设备名称(注册上报)',
  device_type         TINYINT      NOT NULL DEFAULT 1 COMMENT '1:设备(IPC/NVR) 2:下级平台',
  protocol_version    VARCHAR(8)   NOT NULL DEFAULT '2016' COMMENT '协议版本 2016/2022',
  manufacturer        VARCHAR(64)  DEFAULT '',
  model               VARCHAR(64)  DEFAULT '',
  firmware            VARCHAR(64)  DEFAULT '' COMMENT '固件版本',
  transport           VARCHAR(8)   DEFAULT 'UDP' COMMENT '信令传输 UDP/TCP',
  password            VARCHAR(128) DEFAULT '' COMMENT '接入密码(Digest认证,AES加密存储)',
  expires             INT          DEFAULT 3600 COMMENT '注册有效期(秒)',
  heartbeat_interval  INT          DEFAULT 60 COMMENT '心跳周期(秒)',
  heartbeat_timeout   INT          DEFAULT 3 COMMENT '心跳超时次数(判离线)',
  media_tcp_mode      TINYINT      DEFAULT 1 COMMENT '默认收流方式 1:TCP被动 0:UDP',
  ip                  VARCHAR(64)  DEFAULT '' COMMENT '注册联系地址IP',
  port                INT          DEFAULT 5060,
  host_ip             VARCHAR(64)  DEFAULT '' COMMENT '实际来源IP(NAT后)',
  online              TINYINT      DEFAULT 0 COMMENT '0离线 1在线',
  time_diff_ms        INT          DEFAULT 0 COMMENT '设备时间与平台偏差(毫秒),回放校时提示依据',
  register_time       DATETIME     DEFAULT NULL,
  keepalive_time      DATETIME     DEFAULT NULL,
  offline_time        DATETIME     DEFAULT NULL,
  deleted_time        DATETIME     DEFAULT NULL COMMENT '软删(NULL=有效)',
  create_time         DATETIME     DEFAULT CURRENT_TIMESTAMP,
  update_time         DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_device_id (device_id, deleted_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='GB28181设备/下级平台';

-- ---------------------------------------------------------------- gb_device_channel 通道目录
CREATE TABLE gb_device_channel (
  id                BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id         BIGINT       NOT NULL DEFAULT 0,
  device_db_id      BIGINT UNSIGNED NOT NULL COMMENT '所属设备gb_device.id',
  channel_id        VARCHAR(50)  NOT NULL COMMENT '国标20位通道编码',
  name              VARCHAR(128) DEFAULT '' COMMENT '设备上报名称',
  custom_name       VARCHAR(128) DEFAULT '' COMMENT '平台自定义名称(展示优先)',
  manufacturer      VARCHAR(64)  DEFAULT '',
  model             VARCHAR(64)  DEFAULT '',
  owner             VARCHAR(64)  DEFAULT '',
  civil_code        VARCHAR(64)  DEFAULT '' COMMENT '行政区划',
  address           VARCHAR(128) DEFAULT '',
  parental          TINYINT      DEFAULT 0 COMMENT '1:有子节点(虚拟分组)',
  parent_id         VARCHAR(50)  DEFAULT '' COMMENT '父节点通道编码',
  business_group_id VARCHAR(50)  DEFAULT '' COMMENT '业务分组编码',
  status            TINYINT      DEFAULT 1 COMMENT '通道状态 1:ON 0:OFF',
  register_way      TINYINT      DEFAULT 1,
  secrecy           TINYINT      DEFAULT 0,
  ip                VARCHAR(64)  DEFAULT '',
  port              INT          DEFAULT 0,
  longitude         DECIMAL(10,6) DEFAULT NULL,
  latitude          DECIMAL(10,6) DEFAULT NULL,
  ptz_type          TINYINT      DEFAULT 0 COMMENT '0:无PTZ 1:球机 2:半球 3:固定枪机 4:遥控 5:不支持',
  mobile            TINYINT      DEFAULT 0 COMMENT '1:移动设备(支持位置订阅)',
  sub_count         INT          DEFAULT 0 COMMENT '子节点数',
  catalog_time      DATETIME     DEFAULT NULL COMMENT '最近一次目录同步时间',
  deleted_time      DATETIME     DEFAULT NULL COMMENT '软删(NULL=有效)',
  create_time       DATETIME     DEFAULT CURRENT_TIMESTAMP,
  update_time       DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_dev_ch (device_db_id, channel_id, deleted_time),
  KEY idx_channel_id (channel_id),
  KEY idx_parent (device_db_id, parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通道目录';

-- ---------------------------------------------------------------- gb_media_server ZLM节点
CREATE TABLE gb_media_server (
  id             BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id      BIGINT      NOT NULL DEFAULT 0,
  server_id      VARCHAR(64) NOT NULL COMMENT '节点标识(自定)',
  host_ip        VARCHAR(64) NOT NULL COMMENT '内网/平台可达IP',
  public_ip      VARCHAR(64) NOT NULL COMMENT '设备可达IP(点播SDP c=行使用)',
  http_port      INT         DEFAULT 80,
  http_ssl_port  INT         DEFAULT 443,
  rtsp_port      INT         DEFAULT 554,
  rtmp_port      INT         DEFAULT 1935,
  rtc_port       INT         DEFAULT 8000,
  rtp_port_start INT         DEFAULT 30000 COMMENT 'RTP接收端口段起',
  rtp_port_end   INT         DEFAULT 30500 COMMENT 'RTP接收端口段止',
  hook_enable    TINYINT     DEFAULT 1,
  sign_secret    VARCHAR(64) DEFAULT '' COMMENT '播放URL签名密钥(每节点独立,AES存储,可轮换)',
  zlm_version    VARCHAR(32) DEFAULT '' COMMENT 'ZLM版本(on_server_started更新)',
  status         TINYINT     DEFAULT 0 COMMENT '0离线 1在线(on_server_keepalive判定)',
  stream_count   INT         DEFAULT 0 COMMENT '当前流数(定时对账刷新)',
  create_time    DATETIME    DEFAULT CURRENT_TIMESTAMP,
  update_time    DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_server_id (server_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ZLM流媒体节点';

-- ---------------------------------------------------------------- gb_media_session 媒体会话
CREATE TABLE gb_media_session (
  id             BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id      BIGINT      NOT NULL DEFAULT 0,
  session_type   TINYINT     NOT NULL COMMENT '1:实况 2:回放 3:下载 4:对讲 5:级联转发',
  stream_id      VARCHAR(128) NOT NULL COMMENT 'ZLM streamId',
  app            VARCHAR(32) DEFAULT 'rtp',
  device_db_id   BIGINT UNSIGNED NOT NULL,
  channel_db_id  BIGINT UNSIGNED NOT NULL,
  device_id      VARCHAR(50) NOT NULL COMMENT '国标编码(冗余)',
  channel_id     VARCHAR(50) NOT NULL,
  server_id      VARCHAR(64) NOT NULL COMMENT '承载节点',
  ssrc           VARCHAR(16) NOT NULL COMMENT '10位十进制SSRC',
  tcp_mode       TINYINT     DEFAULT 1 COMMENT '1:TCP被动 0:UDP',
  stream_profile TINYINT     DEFAULT 1 COMMENT '码流 1:主码流 2:子码流',
  rtp_port       INT         DEFAULT 0 COMMENT 'ZLM分配的收流端口',
  call_id        VARCHAR(96) DEFAULT '' COMMENT 'SIP CallID',
  user_id        BIGINT      DEFAULT NULL COMMENT '发起用户(审计)',
  upper_id       BIGINT      DEFAULT NULL COMMENT '级联上级id(session_type=5关联gb_cascade_upper)',
  status         VARCHAR(16) NOT NULL DEFAULT 'INIT' COMMENT 'INIT/INVITING/STREAMING/CLOSING/CLOSED/FAILED/CANCELLED',
  active_flag    TINYINT GENERATED ALWAYS AS (IF(status IN ('INIT','INVITING','STREAMING','CLOSING'),1,NULL)) STORED COMMENT '活跃标记生成列(配合唯一约束防并发双开流)',
  viewer_count   INT         DEFAULT 0 COMMENT '观看者引用计数',
  stop_reason    VARCHAR(32) DEFAULT '' COMMENT 'user_stop/none_reader/device_offline/stream_lost/invite_timeout/invite_rejected/no_stream/platform_restart/cascade_bye',
  invite_time    DATETIME    DEFAULT NULL,
  start_time     DATETIME    DEFAULT NULL COMMENT '流就绪时间',
  stop_time      DATETIME    DEFAULT NULL,
  error_msg      VARCHAR(255) DEFAULT '',
  create_time    DATETIME    DEFAULT CURRENT_TIMESTAMP,
  update_time    DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_call_id (call_id),
  KEY idx_stream (stream_id),
  KEY idx_channel (channel_db_id, session_type, status),
  KEY idx_status (status),
  UNIQUE KEY uk_active (channel_db_id, stream_profile, active_flag),
  KEY idx_stop (stop_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体会话';

-- ---------------------------------------------------------------- gb_cloud_record 云端录像
CREATE TABLE gb_cloud_record (
  id            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id     BIGINT      NOT NULL DEFAULT 0,
  server_id     VARCHAR(64) NOT NULL COMMENT '落盘节点',
  app           VARCHAR(32) DEFAULT 'rtp',
  stream_id     VARCHAR(128) NOT NULL,
  device_id     VARCHAR(50) DEFAULT '',
  channel_db_id BIGINT UNSIGNED DEFAULT NULL COMMENT '通道id(归因主键)',
  channel_id    VARCHAR(50) DEFAULT '' COMMENT '国标编码(冗余)',
  file_path     VARCHAR(512) NOT NULL COMMENT '文件相对路径',
  file_name     VARCHAR(255) DEFAULT '',
  file_size     BIGINT      DEFAULT 0 COMMENT '字节',
  duration      INT         DEFAULT 0 COMMENT '秒',
  start_time    DATETIME    DEFAULT NULL,
  end_time      DATETIME    DEFAULT NULL,
  upload_state  TINYINT     DEFAULT 0 COMMENT '0:本地 1:已上传对象存储',
  create_time   DATETIME    DEFAULT CURRENT_TIMESTAMP,
  KEY idx_ch_time (channel_db_id, start_time),
  KEY idx_end (end_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='云端录像(服务端MP4落盘)';

-- ---------------------------------------------------------------- gb_record_item 录像检索缓存
CREATE TABLE gb_record_item (
  id            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id     BIGINT      NOT NULL DEFAULT 0,
  device_db_id  BIGINT UNSIGNED NOT NULL,
  channel_db_id BIGINT UNSIGNED NOT NULL,
  name          VARCHAR(255) DEFAULT '',
  start_time    DATETIME NOT NULL,
  end_time      DATETIME NOT NULL,
  record_type   VARCHAR(16) DEFAULT 'time' COMMENT 'time/alarm/manual',
  query_key     VARCHAR(64) DEFAULT '' COMMENT '查询批次(通道+起止)缓存键',
  create_time   DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_ch (channel_db_id, start_time),
  KEY idx_query (query_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RecordInfo检索结果(收敛合并后)';



-- ---------------------------------------------------------------- gb_alarm_event 报警事件
CREATE TABLE gb_alarm_event (
  id            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id     BIGINT      NOT NULL DEFAULT 0,
  device_db_id  BIGINT UNSIGNED NOT NULL,
  channel_db_id BIGINT UNSIGNED DEFAULT NULL,
  device_id     VARCHAR(50) NOT NULL,
  channel_id    VARCHAR(50) DEFAULT '',
  alarm_method  TINYINT     DEFAULT 1 COMMENT '1:电话 2:短信 3:视频 4:设备联网 5:图片 6:人工',
  alarm_type    INT         DEFAULT 1 COMMENT '报警依据类型(国标表,字典 alarm_type)',
  alarm_priority TINYINT    DEFAULT 1 COMMENT '1一级 2二级 3三级 4四级',
  alarm_time    DATETIME    DEFAULT NULL,
  description   VARCHAR(512) DEFAULT '',
  snap_path     VARCHAR(512) DEFAULT '' COMMENT '报警图片路径(设备/上级附带)',
  longitude     DECIMAL(10,6) DEFAULT NULL,
  latitude      DECIMAL(10,6) DEFAULT NULL,
  handled       TINYINT     DEFAULT 0,
  handle_time   DATETIME    DEFAULT NULL,
  handle_user   BIGINT      DEFAULT NULL,
  create_time   DATETIME    DEFAULT CURRENT_TIMESTAMP,
  KEY idx_time (alarm_time),
  KEY idx_dev (device_db_id, alarm_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报警事件';

-- ---------------------------------------------------------------- gb_group 自定义分组(平台主树)
CREATE TABLE gb_group (
  id           BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id    BIGINT      NOT NULL DEFAULT 0,
  parent_id    BIGINT UNSIGNED DEFAULT 0 COMMENT '父节点, 0=根',
  ancestors    VARCHAR(255) DEFAULT '' COMMENT '祖级路径(如 0,1,5), 便于子树查询',
  group_name   VARCHAR(128) NOT NULL,
  sort_order   INT         DEFAULT 0,
  deleted_time DATETIME    DEFAULT NULL COMMENT '软删(NULL=有效)',
  create_time  DATETIME    DEFAULT CURRENT_TIMESTAMP,
  update_time  DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_parent (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='自定义分组(决策D13: 前端主树, 国标Catalog为原始视图)';

-- ---------------------------------------------------------------- gb_group_channel_ref 分组-通道
CREATE TABLE gb_group_channel_ref (
  id            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id     BIGINT      NOT NULL DEFAULT 0,
  group_id      BIGINT UNSIGNED NOT NULL,
  channel_db_id BIGINT UNSIGNED NOT NULL,
  create_time   DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_grp_ch (group_id, channel_db_id),
  KEY idx_channel (channel_db_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分组-通道多对多';

-- ---------------------------------------------------------------- gb_record_plan 录像计划
CREATE TABLE gb_record_plan (
  id            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id     BIGINT      NOT NULL DEFAULT 0,
  name          VARCHAR(128) NOT NULL,
  plan_type     TINYINT      DEFAULT 1 COMMENT '1:全局默认 2:通道',
  channel_db_id BIGINT UNSIGNED DEFAULT NULL COMMENT 'plan_type=2 时必填',
  week_plan     JSON         DEFAULT NULL COMMENT '7x48半小时布尔矩阵(周一~周日)',
  enable        TINYINT      DEFAULT 1,
  create_time   DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time   DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_channel (channel_db_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='云端录像计划';

-- ---------------------------------------------------------------- gb_device_position 位置轨迹
CREATE TABLE gb_device_position (
  id            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id     BIGINT      NOT NULL DEFAULT 0,
  device_db_id  BIGINT UNSIGNED NOT NULL,
  channel_db_id BIGINT UNSIGNED DEFAULT NULL,
  position_time DATETIME NOT NULL,
  longitude     DECIMAL(10,6) DEFAULT NULL,
  latitude      DECIMAL(10,6) DEFAULT NULL,
  altitude      DECIMAL(8,2)  DEFAULT NULL,
  speed         DECIMAL(8,2)  DEFAULT NULL,
  direction     DECIMAL(6,2)  DEFAULT NULL,
  create_time   DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_ch_time (channel_db_id, position_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='位置轨迹(MobilePosition订阅, 保留90天)';

-- ---------------------------------------------------------------- gb_session_viewer 观看者明细
CREATE TABLE gb_session_viewer (
  id           BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id    BIGINT      NOT NULL DEFAULT 0,
  session_id   BIGINT UNSIGNED NOT NULL,
  user_id      BIGINT UNSIGNED NOT NULL,
  enter_time   DATETIME DEFAULT CURRENT_TIMESTAMP,
  refresh_time DATETIME DEFAULT NULL,
  UNIQUE KEY uk_session_user (session_id, user_id),
  KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话观看者明细(谁在看/审计)';

-- ---------------------------------------------------------------- sys_user 用户
CREATE TABLE sys_user (
  id           BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id    BIGINT      NOT NULL DEFAULT 0,
  username     VARCHAR(64)  NOT NULL,
  password     VARCHAR(128) NOT NULL COMMENT 'BCrypt',
  nickname     VARCHAR(64)  DEFAULT '',
  status       TINYINT DEFAULT 1 COMMENT '1启用 0停用',
  last_login   DATETIME DEFAULT NULL,
  deleted_time DATETIME DEFAULT NULL COMMENT '软删(NULL=有效)',
  create_time  DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_username (username, deleted_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统用户';

-- ---------------------------------------------------------------- sys_role 角色
CREATE TABLE sys_role (
  id         BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id  BIGINT      NOT NULL DEFAULT 0,
  role_code  VARCHAR(64) NOT NULL,
  role_name  VARCHAR(64) NOT NULL,
  remark     VARCHAR(255) DEFAULT '',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_role_code (role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色(admin/operator/viewer 内置)';

CREATE TABLE sys_user_role (
  user_id BIGINT UNSIGNED NOT NULL,
  role_id BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (user_id, role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户-角色';

-- ---------------------------------------------------------------- sys_menu 菜单与按钮权限
CREATE TABLE sys_menu (
  id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id   BIGINT      NOT NULL DEFAULT 0,
  parent_id   BIGINT UNSIGNED DEFAULT 0 COMMENT '父菜单id, 0=根',
  menu_name   VARCHAR(64)  NOT NULL,
  menu_type   TINYINT      NOT NULL COMMENT '1:目录 2:菜单 3:按钮',
  path        VARCHAR(128) DEFAULT '' COMMENT '路由路径(目录/菜单)',
  component   VARCHAR(255) DEFAULT '' COMMENT '前端组件路径(菜单)',
  perm_code   VARCHAR(128) DEFAULT '' COMMENT '权限标识, 如 device:play / ptz:control',
  icon        VARCHAR(64)  DEFAULT '',
  sort_order  INT          DEFAULT 0,
  visible     TINYINT      DEFAULT 1,
  status      TINYINT      DEFAULT 1 COMMENT '1启用 0停用',
  deleted_time DATETIME    DEFAULT NULL COMMENT '软删(NULL=有效)',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_parent (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜单与按钮权限(决策D11: 菜单-按钮一体化)';

CREATE TABLE sys_role_menu (
  role_id BIGINT UNSIGNED NOT NULL,
  menu_id BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (role_id, menu_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色-菜单(含按钮)授权';

-- ---------------------------------------------------------------- sys_dict 公共字典
CREATE TABLE sys_dict_type (
  id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id   BIGINT      NOT NULL DEFAULT 0,
  dict_type   VARCHAR(64)  NOT NULL COMMENT '类型编码: device_type/ptz_type/alarm_type/stop_reason...',
  dict_name   VARCHAR(64)  NOT NULL,
  builtin     TINYINT      DEFAULT 0 COMMENT '1系统内置(不可删除)',
  remark      VARCHAR(255) DEFAULT '',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_type (dict_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='字典类型(决策D16)';

CREATE TABLE sys_dict_data (
  id         BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id  BIGINT      NOT NULL DEFAULT 0,
  dict_type  VARCHAR(64)  NOT NULL,
  dict_value VARCHAR(64)  NOT NULL COMMENT '存储值(与后端枚举code对齐)',
  dict_label VARCHAR(128) NOT NULL COMMENT '显示名称',
  extra      VARCHAR(255) DEFAULT '' COMMENT '扩展JSON(颜色/图标等)',
  sort_order INT          DEFAULT 0,
  status     TINYINT      DEFAULT 1,
  UNIQUE KEY uk_type_value (dict_type, dict_value)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='字典数据';


-- ---------------------------------------------------------------- 日志表
CREATE TABLE sys_login_log (
  id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id   BIGINT      NOT NULL DEFAULT 0,
  user_id     BIGINT      DEFAULT NULL,
  username    VARCHAR(64)  DEFAULT '',
  client_ip   VARCHAR(64)  DEFAULT '',
  user_agent  VARCHAR(255) DEFAULT '',
  result      TINYINT      DEFAULT 1 COMMENT '1成功 0失败',
  msg         VARCHAR(255) DEFAULT '',
  create_time DATETIME     DEFAULT CURRENT_TIMESTAMP,
  KEY idx_user (user_id, create_time),
  KEY idx_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='登录日志(防爆破审计, 留存>=6个月)';

CREATE TABLE sys_oper_log (
  id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id   BIGINT      NOT NULL DEFAULT 0,
  user_id     BIGINT       DEFAULT NULL,
  username    VARCHAR(64)  DEFAULT '',
  module      VARCHAR(32)  DEFAULT '' COMMENT 'device/play/playback/ptz/cascade/sys',
  operation   VARCHAR(64)  DEFAULT '',
  biz_type    VARCHAR(32)  DEFAULT '' COMMENT '业务对象类型: device/channel/group/cascade/user',
  biz_id      VARCHAR(64)  DEFAULT '' COMMENT '业务对象id(按对象查历史)',
  detail      VARCHAR(1024) DEFAULT '',
  client_ip   VARCHAR(64)  DEFAULT '',
  cost_ms     INT          DEFAULT 0,
  result      TINYINT      DEFAULT 1 COMMENT '1成功 0失败',
  create_time DATETIME     DEFAULT CURRENT_TIMESTAMP,
  KEY idx_user (user_id, create_time),
  KEY idx_module (module, create_time),
  KEY idx_biz (biz_type, biz_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作日志(留存>=6个月)';

-- ---------------------------------------------------------------- open_app 第三方开放接口
CREATE TABLE open_app (
  id            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id     BIGINT      NOT NULL DEFAULT 0,
  app_key       VARCHAR(64)   NOT NULL,
  app_secret    VARCHAR(128)  NOT NULL COMMENT 'AES加密存储',
  app_name      VARCHAR(128)  NOT NULL,
  allowed_apis  VARCHAR(1024) DEFAULT '' COMMENT 'API白名单(逗号分隔, 空=仅只读接口)',
  channel_scope VARCHAR(1024) DEFAULT '' COMMENT '可见分组id集合(空=全部)',
  rate_limit    INT           DEFAULT 10 COMMENT 'QPS',
  expire_time   DATETIME      DEFAULT NULL COMMENT '接入有效期',
  status        TINYINT       DEFAULT 1,
  create_time   DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time   DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_app_key (app_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='第三方接入应用(见docs/10, 第一阶段不实现)';

CREATE TABLE open_api_log (
  id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id   BIGINT      NOT NULL DEFAULT 0,
  app_id      BIGINT UNSIGNED DEFAULT NULL,
  app_key     VARCHAR(64)   DEFAULT '',
  method      VARCHAR(8)    DEFAULT '',
  path        VARCHAR(255)  DEFAULT '',
  param       VARCHAR(1024) DEFAULT '',
  result_code INT           DEFAULT 0,
  client_ip   VARCHAR(64)   DEFAULT '',
  cost_ms     INT           DEFAULT 0,
  create_time DATETIME      DEFAULT CURRENT_TIMESTAMP,
  KEY idx_app (app_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='开放接口调用日志';

-- ---------------------------------------------------------------- 字典种子数据(展示/分类类, 决策 D16)
INSERT INTO sys_dict_type(dict_type, dict_name, builtin, remark) VALUES
  ('device_type',  '设备类型',   1, '1设备 2下级平台'),
  ('ptz_type',     '云台类型',   1, '国标通道ptz_type字段'),
  ('alarm_type',   '报警类型',   1, '国标报警依据类型'),
  ('stop_reason',  '会话结束原因', 1, '媒体会话stop_reason');

INSERT INTO sys_dict_data(dict_type, dict_value, dict_label, sort_order) VALUES
  ('device_type', '1', '设备(IPC/NVR)', 1),
  ('device_type', '2', '下级平台', 2),
  ('ptz_type', '0', '无云台', 0),
  ('ptz_type', '1', '球机', 1),
  ('ptz_type', '2', '半球', 2),
  ('ptz_type', '3', '固定枪机', 3),
  ('ptz_type', '4', '遥控摄像机', 4),
  ('stop_reason', 'user_stop', '用户停止', 1),
  ('stop_reason', 'none_reader', '无人观看自动关流', 2),
  ('stop_reason', 'device_offline', '设备离线', 3),
  ('stop_reason', 'stream_lost', '断流', 4),
  ('stop_reason', 'invite_timeout', '点播超时', 5),
  ('stop_reason', 'no_stream', '收流超时', 6),
  ('stop_reason', 'platform_restart', '平台重启清理', 7);
