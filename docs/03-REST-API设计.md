# 03 REST API 设计

> **文档导航**：[文档首页](README.md) · [快速部署](../deploy/README.md) · [部署手册](../deploy/部署手册.md) · [配置参考](配置参考.md) · [GB28181 接入](GB28181设备接入指南.md) · [ONVIF 接入](ONVIF设备接入指南.md) · [拉流代理](拉流代理接入指南.md) · [级联对接](级联对接指南.md) · [REST-API](03-REST-API设计.md) · [OpenAPI 对接](16-OpenAPI对接手册.md) · [数据库设计](02-数据库设计.md)

> Base Path：`/api`，JSON over HTTPS。前端契约文档，前后端可并行开发。

## 1. 通用约定

### 1.1 响应包络

```json
{ "code": 0, "msg": "success", "data": { } }
```

### 1.2 鉴权

- 登录后签发 JWT，请求头 `Authorization: Bearer <token>`，**硬上限 24h**；Redis 会话**空闲 2h 滑动续期**（每次请求续期），登出/改密/停用/强制下线即时吊销（决策 D19）。
- WS 握手同 token：`/ws/api?token=<jwt>`（§10）。
- 登录响应/`me` 携带 `user.mustChangePwd`；为 1 时仅放行 `/api/auth/me|password|logout`，其余接口 403，改密后恢复。
- 播放地址签名：ZLM 播放 URL 附带 `?sign=<md5(streamId + expire + secret)>&expire=<epoch>`，由 `on_play` Hook 校验，防盗链。

### 1.3 分页

请求：`?page=1&count=20`；响应：`data.total`、`data.list`。

### 1.4 错误码

| code | 含义 |
|---|---|
| 0 | 成功 |
| 400 / 401 / 403 / 404 / 500 | HTTP 语义 |
| 1001 | 设备离线 |
| 1002 | 通道离线 |
| 1003 | INVITE 超时/失败 |
| 1004 | SSRC 分配失败 |
| 1005 | ZLM 接口调用失败 |
| 1006 | 流未就绪（推流超时） |
| 1007 | 会话不存在或已关闭 |
| 1008 | 无录像/设备不支持回放 |
| 1009 | 请求过于频繁（接口限流：登录 60 次/分/IP、点播 120 次/分/用户，Redis 固定窗口） |
| 1010 | 录像查询超时（设备未完整应答；B1 RecordInfo 检索 15s 收敛窗口 + **设备级 60s 失败冷却**——同设备 60s 内重查直接快速返回冷却提示，文案明示可能原因；00fe806） |
| 1011 | 预置位查询超时（B3：PresetQuery 5s 收敛，设备不支持/未应答；前端提示"设备可能不支持预置位查询"且不阻塞云台） |
| 2001 | 目录同步进行中，勿重复触发 |
| 3001 | 级联配置错误（编码重复/上级不可达） |

### 1.5 版本与幂等约定

- **路径版本**：v1 暂用 `/api`，规划升级为 `/api/v1`，新接口按可版本化设计；第三方开放接口直接使用 `/openapi/v1`（见《10-安全与第三方对接设计》，第一阶段不实现）。
- **幂等**：`/api/play` 同通道同码流并发/重复请求经分布式锁 + 活跃会话唯一约束保证只建一路流；前端按钮防抖。
- **分页排序**：`orderBy`/`order` 仅接受白名单字段，默认 `id desc`。

## 2. 认证、用户与菜单权限 [M1]

| Method | Path | 说明 |
|---|---|---|
| POST | /api/auth/login | 登录，返回 `{ token, user, roles[], perms[], menus[] }`（perms=权限码集合，menus=可见菜单树，前端据此动态建路由；user.mustChangePwd=1 需先改密） |
| POST | /api/auth/logout | 注销（吊销当前 token） |
| PUT | /api/auth/password | 修改自己密码（复杂度≥8位含字母+数字；成功后吊销该用户全部会话） |
| GET | /api/auth/me | 当前用户信息（含 roles/perms/menus，页面刷新时拉取） |
| GET/POST/PUT/DELETE | /api/users | 用户管理（admin） |
| GET/POST/PUT/DELETE | /api/roles | 角色管理（admin），保存角色时提交授权的 menuId 集合 |
| GET/PUT | /api/roles/{id}/menus | 角色已授权 menuId 查询 / 全量覆盖保存（角色授权页用；PUT body=`{menuIds[]}`） |
| GET | /api/menus/tree | 全量菜单树（含按钮节点，菜单管理用，admin） |
| GET/POST/PUT/DELETE | /api/menus | 菜单与按钮维护（admin；**D22 定稿式**：运行期增删改授权已由 V4 收回，接口保留兜底，前端只读展示） |
| GET | /api/dicts/{dictType} | 字典数据查询（决策 D16，展示/分类类枚举统一来源；M1 前端未消费，Pinia 启动缓存随 M2） |
| GET | /api/dicts/all | 全量字典（同上） |
| GET | /api/auth/online | 在线用户/登录会话列表（admin） |
| DELETE | /api/auth/online/{tokenId} | 强制下线（admin，登出/封禁即时吊销 token） |
| GET | /api/auth/login-logs | 登录日志查询（admin，留存≥6个月） |
| GET | /api/oper-logs?module=&username=&bizType=&bizId=&result= | 操作日志分页查询（M1-9 契约缺口补齐；admin+operator 查看，`log:oper:list`） |

### 2.1 权限模型（菜单-按钮一体化）

- 权限点即 `sys_menu` 中 `menu_type=3` 记录上的 `perm_code`（如 `device:play`、`ptz:control`、`device:delete`），角色通过 `sys_role_menu` 授权（决策 D11）。
- 后端接口点：`@PreAuthorize("hasAuthority('device:play')")`。
- 前端联动：`menus[]` → 动态 `addRoute`；`perms[]` → `v-permission` 指令控制按钮显隐；路由守卫兜底（无权限跳 403）。
- **数据级权限（B3 已落地，通道级授权）**：`sys_role.data_scope` 1=全部通道 2=按分组授权（`sys_role_group` 子树生效 ∪ `sys_role_channel` 直选预留）；admin 恒全部（解析短路）。拦截面：通道列表/树、实时点播、回放、云台（含预置位）、录像检索、云端录像检索/下载/删除/计划、分组通道树、点播会话列表。scope 每请求实时解析（缓存优化）。级联点播权限为 `cascade:play`。
- 分组管理/数据授权为 admin 专属；通道改名（channel:update，含强制云台开关）admin 专属，与 §2.1 矩阵"国标设备 operator=编辑/刷新"的落差在此注记（operator 不可改通道名）。

内置角色默认矩阵：

| 菜单/权限 | admin | operator | viewer |
|---|---|---|---|
| 实时预览/播放 | ✅ | ✅ | ✅ |
| 云台控制 | ✅ | ✅ | ❌ |
| 录像回放/云端录像 | ✅ | ✅ | 查看 |
| 国标设备(目录刷新/编辑/删除) | ✅ | 编辑/刷新 | ❌ |
| 流媒体节点管理 | ✅ | ❌ | ❌ |
| 级联配置 [M3] | ✅ | ❌ | ❌ |
| 用户/角色/菜单管理 | ✅ | ❌ | ❌ |
| 操作日志 | ✅ | 查看 | ❌ |

侧边栏信息架构（V8 起定稿, 菜单 id 随种子演进【只挪挂载点不改 id】, 授权行按 id 引用因此无需重刷）：

```
实时预览 /live          ← 一级叶子页(menu_type=2 直挂根, 前端侧边栏/路由/面包屑均已支持)
设备接入 /access        ← 国标设备(3) + 流媒体节点(20)（views/access/*）
系统管理 /system        ← 用户/角色/菜单/在线用户(14, 原名"会话监控")/操作/登录日志
录像回放=50(已建 B1: 51 录像检索页 record:query, admin/operator/viewer 三角色)  报警中心=60(已建 M3-B4: 61 报警列表页 alarm:view + 6101 报警处理按钮 alarm:handle, admin/operator)  运维监控=70(已建 B4: 71 监控概览页 monitor:view, admin/operator)   ← 余下 id 段随 M2/M3 各迁移建
```

### 2.1a 报警中心 [M3-B4]

| Method | Path | 说明 |
|---|---|---|
| GET | /api/alarms?deviceId=&keyword=&handled=&start=&end=&page=&count= | 报警分页（alarm_time 倒序；keyword 匹配描述/通道/设备编码；返回 {total,list}；alarm:view） |
| PUT | /api/alarms/{id}/handle | 标记已处理（记录 handle_user=当前登录名+handle_time；重复处理 400；alarm:handle + @AuditLog） |

入库来源（信令层, 无 REST 触发端点）：设备报警经 MESSAGE（Notify/Alarm 直发或 Response/Alarm）或订阅 NOTIFY（平台自动 SUBSCRIBE Event: Alarm，V21 前身=CatalogSubscriptionService 同款状态机，`vision.alarm.subscribe-expires` 默认 3600s）→ 来源校验（From=在册设备，报警源须为设备自身或在册通道）→ gb_alarm_event + WS ALARM 事件 + 消息中心 NOTICE（type=ALARM，级别按报警优先级）。WS ALARM payload: {deviceId, channelId, alarmType, alarmPriority, alarmTime}。

### 2.2 消息中心 [M2]

| Method | Path | 说明 |
|---|---|---|
| GET | /api/notices?unread=&page= | 消息列表（设备离线/报警/目录同步失败，与 WS 推送双通道） |
| GET | /api/notices/unread-count | 未读数（前端轮询或 WS 附带） |
| POST | /api/notices/{id}/read | 标记已读 |
| POST | /api/notices/read-all | 全部已读（✅B1 四端点全部实现；事件源=设备离线[注销/心跳超时]/目录同步失败, 报警随 M3；WS 事件 NOTICE_NEW {id,type,title,level,createTime}；扇出=每未删用户一行 sys_user_message，已读态按用户隔离；保留 90 天随 DataRetentionTask 清理） |

> **通知 type（B4 新增）**：`PLATFORM_ALERT`（Prometheus 告警经 §2.3 webhook）/ `NODE_CAPACITY`（端口占用≥80%，SessionReconcileTask）/ `NODE_OFFLINE`（节点判离线，NodeKeepaliveTask）；后两者平台直发，均 30 分钟 Redisson setIfAbsent 去重（fail-open）。

### 2.3 告警 webhook 入口 [M2-B4]

Alertmanager 专用回调，鉴权不走 JWT：`permitAll` + 请求头 `X-Vision-Alert-Token` 恒定时间比对（配置 `vision.security.alert-webhook-token`，ProdSecretGuard 拒绝生产弱默认）。

| Method | Path | 说明 |
|---|---|---|
| POST | /api/alerts/webhook | 接收 Alertmanager 告警（仅 `firing` 落站内消息中心+WS，type=PLATFORM_ALERT；level 按 severity critical=2/warning=1；按 fingerprint 30 分钟去重；解析异常自吞不影响 2xx，避免 AM 重试风暴） |

## 3. 设备与通道 [M1]

| Method | Path | 说明 |
|---|---|---|
| GET | /api/devices | 设备列表（`?online=&keyword=&page=&count=`） |
| GET | /api/devices/{deviceId} | 设备详情 |
| POST | /api/devices | 设备预建档（M3-B3）：`{deviceId(20位数字), name?, password, deviceType(1设备/2下级平台), protocolVersion?, mediaTcpMode?}`，权限 `device:add`；默认值与注册自动建档对齐（2016/UDP/TCP 被动/心跳 60s×3） |
| GET | /api/devices/access-config | 接入配置页「设备侧对接信息」+ 接入模式 + 默认密码状态：接入信息只读（serverId/domain/sipPort/transports 来自配置文件）+ 自动注册开关 + 默认接入密码状态（defaultPasswordSet/source，**不明文出参**）；权限 `device:update` |
| PUT | /api/devices/access-config | 修改接入配置（patch：`{defaultPassword? ≥8 位, allowAutoRegister? 0白名单/1开放}`，V18 起默认白名单=未知编码注册 403 拒绝；AES 落库 gb_access_config）；权限 `device:update` |
| GET | /api/devices/{deviceId}/access-info | 设备对接信息（M3-B3）：服务器四件套+设备编码/名称/**明文密码**，供一键复制发给接入方；权限 `device:update` |
| DELETE | /api/devices/{deviceId} | 删除设备（清除通道与会话） |
| PUT | /api/devices/{deviceId} | 修改设备参数（名称/类型/密码/心跳/收流方式/协议版本；name/deviceType 为 M3-B3 追加，patch 语义 null 不改） |
| POST | /api/devices/{deviceId}/catalog/refresh | 触发目录同步（进行中返回 2001） |
| GET | /api/devices/{deviceId}/catalog/progress | 目录同步进度（total/synced） |
| GET | /api/channels/tree?deviceId= | 通道树（按 parentId 组织；受限角色按数据权限过滤，被滤父节点的子节点提升为根；businessGroup 虚拟分组见 §3.1 分组视图） |
| GET | /api/channels | 通道分页列表（`?deviceId=&query=&status=&ptz=`；受限角色按数据权限过滤） |
| PUT | /api/channels/{channelDbId} | 修改自定义名称/云台开关（body `{customName, ptzEnabled}`，ptzEnabled: null=自动 1=强制开启 0=禁用；**改名须回传当前 ptzEnabled 原值**，B3） |
| GET | /api/records/positions?channelDbId=&start=&end= | 移动设备位置查询 [M3] |

### 3.1 分组管理 [M2-B3]

自定义分组为平台主树（决策 D13），并作为数据授权载体（§2.1 数据级权限）。
**单分组约束（M2-B3 修订）**：一条通道仅可绑定到一个分组——绑定接口对已被其他分组绑定的通道拒绝并列明所在分组；绑定抽屉仅展示"未绑定 + 本分组已绑"的通道（`bind-view` 供数）。

| Method | Path | 说明 |
|---|---|---|
| GET | /api/groups/tree | 分组树（登录可读；管理页/角色数据授权树共用） |
| GET | /api/groups/channel-tree | 分组+通道树（登录可读；预览页"分组视图"数据源，通道按数据权限过滤，整树无可见通道的分组被裁剪） |
| GET | /api/groups/{id}/channels | 分组内通道（group:list；通道按数据权限过滤） |
| GET | /api/groups/{id}/bind-view | 通道绑定视图（group:list；返回全部可见通道 + 各通道所属分组，绑定抽屉数据源） |
| POST | /api/groups | 新建分组 `{parentId, groupName, sortOrder}`（group:add） |
| PUT | /api/groups/{id} | 编辑分组/挪挂载点 `{parentId, groupName, sortOrder}`（group:update；环/自身校验，后代 ancestors 重算） |
| DELETE | /api/groups/{id} | 删除分组（group:delete；有子分组/绑定通道/被角色数据授权引用时拒绝并列明原因） |
| PUT | /api/groups/{id}/channels | 绑定通道（group:bind-channel；body `{channelDbIds[]}` 全量覆盖；单分组约束：通道已被其他分组绑定 → 400 列明分组） | |

## 4. 实时点播 [M1]

### 4.1 开始点播

`POST /api/play`

> **M3-B3**：点播下级平台（deviceType=2）通道需 `cascade:play` 独立授权（V20 菜单 204「级联点播」，admin/operator），且收流模式恒取设备档案 `media_tcp_mode`（请求 tcpMode 不生效）；本域设备不变（请求值优先，缺省 TCP 被动）。详见《级联对接指南》§3.2。

```json
// 请求
{ "channelDbId": 1001, "tcpMode": 1, "streamProfile": 1 }
// 响应 data（流未就绪时前端等待 WS 的 STREAM_READY 事件或轮询 4.3）
{
  "streamId": "gb_play_34020000001320000001",
  "sessionId": 88,
  "status": "INVITING",
  "videoCodec": "",
  "urls": {
    "flv":  "http://zlm.host:80/rtp/gb_play_34020000001320000001.live.flv?sign=xxx&expire=1725600000",
    "wsFlv":"ws://zlm.host:80/rtp/gb_play_34020000001320000001.live.flv?sign=xxx&expire=1725600000",
    "hls":  "http://zlm.host:80/rtp/gb_play_34020000001320000001/hls.m3u8?sign=xxx",
    "ts":   "http://zlm.host:80/rtp/gb_play_34020000001320000001.live.ts?sign=xxx&expire=1725600000",
    "rtc":  "http://zlm.host:80/index/api/webrtc?app=rtp&stream=gb_play_34020000001320000001&type=play&sign=xxx&expire=1725600000"
  }
}
```

> **复用与幂等**：按"通道+码流"两维度复用，同通道同码流已有 STREAMING 会话直接返回已有流并 `viewer_count+1`，不重复 INVITE；并发请求由分布式锁 + 活跃会话唯一约束保证只建一路流。
> **码流**：`streamProfile` 1=主码流 2=子码流（多宫格预览建议子码流省带宽），默认主码流。
> **videoCodec [B5]**：`H264`/`H265`（规范化名，空串=未知）。INVITING 时为空串，流就绪后随 WS `STREAM_READY` 下发；复用点播（status=STREAMING）时由 ZLM getMediaList tracks 现查。前端据此选播放路径：H.265 且浏览器无 HEVC-MSE 硬解（`MediaSource.isTypeSupported('video/mp4; codecs="hvc1…"')`=false）→ 主动提示不可播；WebRTC 路径不受 MSE 判据约束。
> **rtc [B5]**：ZLM 内置 WebRTC SDP 交换 API 地址（POST offer body → JSON `{code,sdp}`），sign/expire 随 on_play 走同一防盗链校验；**仅安全上下文可用（HTTPS 或 localhost）**，未启用 HTTPS 的部署中仅 localhost 可验证。
> **地址 base [B5]**：默认按节点 `public_ip:http_port` 拼 http/ws；配置 `vision.play.public-base`（如 `https://media.example.com`）后全量覆盖、ws/wss 按 scheme 推导（HTTPS 化伴生项，平台代码零改动）。
> **地址范围定稿 [B5]**：`flv`/`wsFlv`/`hls`/`ts`/`rtc` 五种全量下发（`hls` 起播延迟高于 FLV，作移动端/兜底路径；`rtc` 首帧最快，安全上下文就绪后启用）。

### 4.2 停止点播

`POST /api/play/{streamId}/stop` — viewer_count-1；归零时平台向设备发 BYE 并释放资源。**停止权限：登录用户可停止任意会话**（决策 D14），调用者记入审计日志。前端在 `pagehide` 时以 `fetch keepalive` + token 快照调用（登录态已清空时仍可发出；预览页签切走即销毁并批量停流），最终兜底为 ZLM `on_stream_none_reader` + 会话对账任务——无需前端续期机制（见 06 §9）。

### 4.2b 跨节点观看地址 [M3-B6 阶段1]

`GET /api/play/{streamId}/urls?node=`（device:play + 通道数据权限）

- 按 streamId 取进行中（STREAMING）会话的播放地址；`node` 可选：
  - 缺省 / 与源节点相同：返回源节点地址（不建代理）
  - 其他在线节点：目标节点 `addStreamProxy` 从源节点拉签名 TS（幂等），返回目标节点地址
- 应答 data：`streamId` / `sourceNode`（源节点）/ `node`（本次地址所属节点）/ `proxied`（本次是否新建代理）/ `urls`（五格式地址）

### 4.2c HA 内部管理口 [M3-B6 阶段2]

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/ha/internal/sip-stack/start | 本实例升为活跃身份（起全部配置 SIP 栈 + 写领导租约；任一栈起失败全量回滚返回错误）。keepalived notify_fault 调用（B 接管） |
| POST | /api/ha/internal/sip-stack/stop | 本实例降为备用身份（停栈 + 删自持租约）。keepalived notify_master 调用（回切让口） |
| GET | /api/ha/internal/status | 运行态：enabled/role/selfId/activeStacks/leader/peerBaseUrl |
| POST | /api/ha/internal/on-demand | **备台委托建流**（M3-B7）：body `{streamId, serverId, streamProfile}` → 活跃实例 `playOnDemand` 建流（`serverId`=发起 hook 的节点，流 pin 到该节点）。失败回业务码非 0（HTTP 仍 200），调用方只记日志、由播放器重试自愈 |

鉴权：请求头 `X-Vision-Ha-Token` 与 `vision.ha.token` 恒定时间比对，不符 401；网关 permitAll（同 /api/alerts/webhook 形制），prod 启用 HA 时弱令牌拒启（ProdSecretGuard）。调用方=keepalived notify 脚本与演练脚本（deploy/ha/），以及备台的 `HaPeerClient`（M3-B7 按需点播委托）。

### 4.2d 通道截图 [M3-B7 阶段2]

服务端抓图：ZLM `getSnap` 抽帧 → 落盘 `{vision.snapshot.dir}/{channelDbId}_{epochMs}.jpg`（+`_t.jpg` 缩略图宽160）→ `gb_channel_snapshot` 每通道只留最新一张（写新删旧，容量有界 ≈千通道×160MB 内，`SnapshotCleanupTask` 每日 02:50 收扫孤儿文件与失效行）。

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | /api/channels/{channelDbId}/snapshot | `device:play` + 通道数据权限 + 用户限流 30/min + 审计 | 手动抓图。同步执行，最坏 ~23s（空闲建流 8s + 抓帧预算 15s）。应答 data = `SnapshotVo {url, thumbUrl, time, source}`，url/thumbUrl 为**带签名的同源相对地址**（TTL=`url-ttl-seconds` 300s） |
| GET | /api/channels/{channelDbId}/snapshot?size=full\|thumb&sign=&expire= | 免登录（签名即凭证，形制同 /api/cloud-records/file；IP 限流 300/min） | 图片字节流 `image/jpeg`。sign=md5(channelDbId:size:expire:aesKey)，端点内复验，缺参/过期/篡改 → 401 包络 |
| GET | /api/channels/snapshot-config | 登录即可 | 能力开关查询 `{enabled, idleEnabled}`，前端抽屉据此决定"截图"列是否渲染 |

> **来源 source**：`AUTO`=点播流就绪自动抓（on_stream_changed regist=true +3s，10s Redis 去重 + `auto-refresh-seconds` 1800 节流）/ `IDLE`=本次临时建流的空闲抓图（手动入口触发空闲建流也记 IDLE）/ `MANUAL`=复用已有流的手动抓图。
> **无流拒绝**：通道无进行中实况且 `vision.snapshot.idle-enabled=false`（默认）→ `1014 SNAPSHOT_NO_STREAM`；开启则临时建流抓完即停（viewer 1→0 触发关流，期间真人点播不误停）。
> **总开关**：`vision.snapshot.enabled=false` → 手动接口 1013 拒绝、自动抓图关闭、清理任务停扫；前端不渲染截图列。
> **HA**：POST 在备台态经 `/api/channels` 转发规则自动转活跃实例（零新增 HA 面）；自动抓图经 VIP hook 落备台时在备台本地执行（getSnap+读共享库，无 SIP 依赖）。

### 4.3 查询会话

| Method | Path | 说明 |
|---|---|---|
| GET | /api/play/sessions?status= | 会话列表（运维用；B3 起受限角色仅见数据权限范围内通道的会话，admin 全量；观测上限 `vision.media.sessions-limit` 默认 100, M3-B4 阶段3 参数化） |

> ~~GET /api/play/streams~~ **未实现**：会话对账为平台内部任务（06 §8 C2），外部查流走 §7 `/api/servers/{serverId}/streams`。

## 5. 云台控制 [M1]

`POST /api/ptz/control`

```json
// 请求
{ "channelDbId": 1001, "command": "LEFT", "speed": 100 }
```

| command | 含义 | command | 含义 |
|---|---|---|---|
| UP / DOWN / LEFT / RIGHT | 方向 | ZOOM_IN / ZOOM_OUT | 变倍近/远 |
| FOCUS_NEAR / FOCUS_FAR | 聚焦 | IRIS_OPEN / IRIS_CLOSE | 光圈 |
| STOP | 停止（前端松开时发送） | | |

预置位：

| Method | Path | 说明 |
|---|---|---|
| GET | /api/ptz/preset/list?channelDbId= | ✅B3 预置位查询（PresetQuery 信令，5s 收敛，超时 1011；返回 `[{presetId, presetName}]`，上限 255） |
| POST | /api/ptz/preset/set | `{channelDbId, presetNum}` |
| POST | /api/ptz/preset/goto | `{channelDbId, presetNum}` |
| POST | /api/ptz/preset/delete | `{channelDbId, presetNum}` |
| POST | /api/ptz/homeposition/set / delete | 变倍巡航/复位 [M2] |

> **PTZ 限流（B3）**：/api/ptz/* 全端点 Redis 固定窗口 600 次/分/用户（`@RateLimit(name="ptz")`，yaml `vision.rate-limit.limits.ptz` 可覆盖）；触发 1009，前端对摇杆长按场景 3s 节流提示。

## 5.x 语音对讲 [M3-B5]

**2022 Talk 流程 + 平台直推音频**（M3-B5）：

```text
POST /api/talk/start {channelDbId}                    发起对讲: 建会话(session_type=4) + INVITE s=Talk
                                                      → {talkId, streamId, status, channelId, pushWsUrl}
POST /api/talk/begin {talkId}                         开始对讲: 等设备 200 OK 应答后置 STREAMING（平台开始转发音频帧）
POST /api/talk/stop  {talkId}                         结束对讲: BYE + 终态
GET  /api/talk/status?talkId=                         会话状态查询
WS   /ws/talk?talkId={id}&token={jwt}                 音频推送（二进制）: G.711A 裸字节 8kHz, 20ms/160B 帧
```

- **权限/限流**：`talk:talk`（V22 menu 205, admin/operator）；`@RateLimit(name="talk")` 30 次/分/用户；start/stop 审计。
- **平台直推设计**：音频不经 ZLM——客户端 WS 二进制(G.711A) → 平台 RTP 打包(pt=8, seq/ts 8kHz)直发设备 200 OK 应答 SDP 的 c=/m=audio 地址。设备应答 SDP 必须含 PCMA(8)，仅 UDP；不支持转码（应答无 pt=8 → 1012 TALK_UNAVAILABLE 收尾）。
- **错误码**：1012 TALK_UNAVAILABLE（设备不支持 G.711A/级联通道）；1006/1003 复用（音频源未就绪/设备未应答）。
- **超时清理**：INVITE 10s 无最终响应 CANCEL（复用点播扫描）；已应答未 begin 60s 兜底关停；WS 断开 → stream_lost 收尾。
- **设备 BYE**：对讲会话按 callId 关闭并发终态事件 TALK_STOPPED（WS 事件 `{talkId, channelId, status, reason}`）。

## 6. 历史录像与回放 [M2]

| Method | Path | 说明 |
|---|---|---|
| GET | /api/record/query?channelDbId=&start=&end= | ✅B1 录像检索：RecordInfo 信令（发往通道编码）→ 按 SN 多包聚合 → 15s 超时收敛（1010）→ 窗口裁剪+间隙≤3s 合并去重；60s 结果缓存（+ 设备级失败 60s 冷却，00fe806；前端该请求单独 20s 超时）。start/end 格式 `yyyy-MM-dd HH:mm:ss`，跨度≤31天；返回 `{channelDbId,start,end,count,segments:[{deviceId,recordId,name,type,startTime,endTime}]}`，空结果 count=0 正常返回（设备离线=1001） |
| POST | /api/playback/start | `{channelDbId, startTime, endTime}` → 立即返回 `{streamId, sessionId, status:"INVITING", urls:{}, videoCodec:""}`——**两段式应答**：流就绪后 WS `STREAM_READY` 携带最终 urls（与实况 play 同款，前端不得轮询猜 urls）。**级联通道**（deviceType=2）回放需 `cascade:play`（复用实况哨兵，与入口 `record:play` 双权限），收流模式恒取设备档案 `media_tcp_mode`（请求不传/不生效）——详见《级联对接指南》§3.3 |
| POST | /api/playback/control | `{streamId, cmd, scale?, seekTime?}`；cmd 白名单 + 会话状态前置校验（PlaybackControlValidation，8235df59），非法 cmd/状态不符 → 报错且不动会话 |
| POST | /api/playback/stop | `{streamId}` |

`control.cmd` 取值：`PAUSE`、`RESUME`、`SCALE`（附 `scale`: 0.25/0.5/1/2/4）、`SEEK`（附 `seekTime`，平台内部走重新 INVITE）。SEEK 重 INVITE 被 4xx 拒绝 → **会话保留继续播**仅返回错误（07 §3.1 实现注记）；PAUSE 成功会话豁免 15s 无 RTP 判死（06 §6/§6.1）。

### 回放响应示例

```json
{
  "streamId": "gb_playback_34020000001320000001_093025_17",
  "sessionId": 91,
  "status": "INVITING",
  "urls": { "flv": "...", "wsFlv": "...", "hls": "..." }
}
```

## 7. 流媒体节点管理 [M1]

| Method | Path | 说明 |
|---|---|---|
| GET | /api/servers | ZLM 节点列表（状态/流数/端口段） |
| POST | /api/servers | 新增节点 |
| PUT/DELETE | /api/servers/{serverId} | 修改/删除 |
| GET | /api/servers/{serverId}/streams | 节点流列表 |

> M1-10 注：`signSecret` 出参为 AES 密文（`enc:v1:` 前缀，敏感字段加密见 02 §0）；入参 patch 语义=留空不改、传明文将加密落库（重复提交密文幂等，加密操作对已加密值原样返回）。

### 7.1 运维监控指标 [M2-B4]

| Method | Path | 说明 |
|---|---|---|
| GET | /api/metrics/overview | 监控概览快照（monitor:view；NodeMetricsTask 30s 采集的**内存快照直出，平台不落库**——会话/设备/节点状态、ZLM 可达性、流数/观看数/带宽、端口占用、SSRC 占用；历史曲线走 Grafana，前端 views/ops/overview 10s 轮询） |

## 8. 云端录像 [M2-B3]

录制语义：**跟随实况流**——通道处于计划时段且实况流已建立（有人观看）时平台才向 ZLM startRecord；无人观看时段有计划也不录（7x24 自动 INVITE 录制暂未支持）。保留清理每日 02:40，`vision.retention.cloud-record-days`（默认 30 天）。

| Method | Path | 说明 |
|---|---|---|
| GET | /api/cloud-records?channelDbId=&start=&end=&page=&count= | 录像文件检索（record:query + 数据权限；start/end `yyyy-MM-dd HH:mm:ss`，窗口按"有交集"匹配；**出参不含服务器文件路径**） |
| POST | /api/cloud-records/{id}/download | 生成下载地址（record:query + cloud-record:download + 数据权限）→ `{url, expire:60}`；url 指向 GET /api/cloud-records/file?token=（免登录，60s 一次性 token，签发者数据权限复验） |
| DELETE | /api/cloud-records/{id} | 删除文件与记录（cloud-record:delete + 数据权限） |
| GET/PUT | /api/record-plan | 录像计划：GET 列表（record:query；通道行按数据权限过滤）/ PUT 单条 upsert（record-plan:manage；`{planType:1全局默认\|2通道, channelDbId?, enable, weekPlan: boolean[7][48]}` 半小时格，slot=hour*2+minute/30；通道计划须在操作者数据权限内） |

## 9. 级联 [M3]

| Method | Path | 说明 |
|---|---|---|
| GET/POST/PUT/DELETE | /api/cascade/uppers | 上级平台配置管理 **✅B1**（page 运行态含 sessions 快照；POST/PUT 收 UpperRequest，password 明文入参服务内 AES，留空=不修改；cascade:list / cascade:config） |
| POST | /api/cascade/uppers/{id}/enable | 启停注册 **✅B1**（enabled=1 触发级联 UA 注册） |
| POST | /api/cascade/uppers/{id}/catalog/push | 手动向级联上级推送目录 **✅B1**（Notify/Catalog） |
| GET/POST/DELETE | /api/cascade/uppers/{id}/channels | 共享通道选择 **✅B1**（GET 带通道摘要；POST 整体替换 `{bindings:[{channelDbId,virtualDeviceId}]}`；DELETE channelDbId 可选） |
| GET | /api/cascade/lowers | 下级平台列表（即 device_type=2 的设备）（B3） |

## 10. WebSocket 事件推送 [M1]

连接：`ws://{host}/ws/api?token=<jwt>`，消息包络：

```json
{ "type": "STREAM_READY", "timestamp": 1725600000000, "data": { "streamId": "..." } }
```

| type | 触发 | data |
|---|---|---|
| DEVICE_ONLINE / DEVICE_OFFLINE | 注册/心跳超时 | `{deviceId, name}` |
| CATALOG_SYNC_PROGRESS | 目录同步进度 | `{deviceId, total, synced}` |
| CHANNEL_CHANGE | 目录变更 | `{deviceId}` |
| STREAM_READY | on_stream_changed(regist=true) | `{streamId, channelId, urls, videoCodec}`（videoCodec: H264/H265, B5） |
| STREAM_CLOSED | 会话关闭/断流 | `{streamId, reason}` |
| STREAM_FALLBACK | UDP 收流超时自动降级 TCP re-INVITE（UDP 收流超时自动降级 TCP re-INVITE） | `{streamId, channelId}` |
| PLAYBACK_SPEED_ACK | 回放控制应答 | `{streamId, cmd}` |
| ALARM | 报警通知 [M3] ✅M3-B4 | `{deviceId, channelId, alarmType, alarmPriority, alarmTime}` |
