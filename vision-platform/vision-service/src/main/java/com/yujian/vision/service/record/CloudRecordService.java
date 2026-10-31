package com.yujian.vision.service.record;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.media.zlm.ZlmApiClient;
import com.yujian.vision.service.media.entity.GbMediaSessionEntity;
import com.yujian.vision.service.media.mapper.GbMediaSessionMapper;
import com.yujian.vision.service.record.entity.GbCloudRecordEntity;
import com.yujian.vision.service.record.mapper.GbCloudRecordMapper;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 云端录像（docs/09 M2 #3）：MP4 分片入库归因、分页查询（不外泄服务器路径）、
 * 删除（连文件）、一次性下载 token 与文件流、按计划触发录制。
 */
@Slf4j
@Service
public class CloudRecordService {

    /** 下载 token Redis 键前缀 */
    public static final String DL_TOKEN_PREFIX = "vision:dl:";
    /** 下载 token 有效期（秒） */
    public static final long DL_TOKEN_TTL_SECONDS = 60;
    /** 活跃态集合（与 gb_media_session 活跃口径一致: INIT/INVITING/STREAMING/CLOSING） */
    private static final Set<String> ACTIVE = Set.of("INIT", "INVITING", "STREAMING", "CLOSING");
    /** 同一流 startRecord 去重窗: ZLM 会按 schema(rtsp/rtmp/ts/fmp4) 各报一次 regist, 防重复开录 */
    private static final long START_RECORD_DEDUPE_MS = 5_000L;
    /** streamId → 最近一次 startRecord 请求时刻(epoch ms) */
    private final java.util.concurrent.ConcurrentHashMap<String, Long> recordAskAt =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final GbCloudRecordMapper recordMapper;
    private final GbMediaSessionMapper sessionMapper;
    private final RecordPlanService plans;
    private final ZlmApiClient zlm;
    private final RedissonClient redisson;
    private final ObjectMapper json;

    public CloudRecordService(GbCloudRecordMapper recordMapper, GbMediaSessionMapper sessionMapper,
            RecordPlanService plans, ZlmApiClient zlm, RedissonClient redisson, ObjectMapper json) {
        this.recordMapper = recordMapper;
        this.sessionMapper = sessionMapper;
        this.plans = plans;
        this.zlm = zlm;
        this.redisson = redisson;
        this.json = json;
    }

    /** 下载凭证（token 消费后得到, 用于数据权限复验） */
    public record DownloadTicket(long recordId, long userId) {
    }

    /** 下载载荷（已定位到文件; record.filePath 不出参, 由服务端直读; userId 用于数据权限复验） */
    public record DownloadPayload(GbCloudRecordEntity record, FileSystemResource resource, long userId) {
    }

    /**
     * ZLM on_record_mp4 报文归一化（字段名按 ZLM 官方文档:
     * mediaServerId/app/stream/file_name/file_path/file_size/start_time/time_len）。
     */
    public record HookMp4Chunk(String streamId, String app, String mediaServerId, String filePath,
            String fileName, Long fileSize, Double timeLen, Long startEpoch) {

        /** 以节点表解析结果回填 server_id（HookService 解析 gb_media_server 后调用） */
        public HookMp4Chunk withServerId(String serverId) {
            return new HookMp4Chunk(streamId, app, serverId, filePath, fileName, fileSize,
                    timeLen, startEpoch);
        }
    }

    // ---------------------------------------------------------------- 分页

    /**
     * 契约主入口: 分页查询, 出参不含 file_path（select 显式排除列, 服务器路径不外泄）。
     * 时间窗按"录像与窗口有交集"过滤（end_time > start 且 start_time < end）,
     * 起止时间为空的行视作持续存在, 不被窗口条件误杀。
     */
    public IPage<GbCloudRecordEntity> page(Long channelDbId, LocalDateTime start, LocalDateTime end,
            int page, int count) {
        return page(channelDbId, null, start, end, page, count);
    }

    /** 数据权限感知重载: scopeChannelIds=null 不限; 非 null 空集 = 无任何授权通道 → 空页 */
    public IPage<GbCloudRecordEntity> page(Long channelDbId, java.util.Set<Long> scopeChannelIds,
            LocalDateTime start, LocalDateTime end, int page, int count) {
        LambdaQueryWrapper<GbCloudRecordEntity> qw = new LambdaQueryWrapper<GbCloudRecordEntity>()
                .select(GbCloudRecordEntity::getId, GbCloudRecordEntity::getServerId,
                        GbCloudRecordEntity::getApp, GbCloudRecordEntity::getStreamId,
                        GbCloudRecordEntity::getDeviceId, GbCloudRecordEntity::getChannelDbId,
                        GbCloudRecordEntity::getChannelId, GbCloudRecordEntity::getFileName,
                        GbCloudRecordEntity::getFileSize, GbCloudRecordEntity::getDuration,
                        GbCloudRecordEntity::getStartTime, GbCloudRecordEntity::getEndTime,
                        GbCloudRecordEntity::getUploadState, GbCloudRecordEntity::getCreateTime)
                .eq(channelDbId != null, GbCloudRecordEntity::getChannelDbId, channelDbId)
                .orderByDesc(GbCloudRecordEntity::getStartTime);
        if (scopeChannelIds != null) {
            if (scopeChannelIds.isEmpty()) {
                return new Page<>(page, count, 0);
            }
            qw.in(GbCloudRecordEntity::getChannelDbId, scopeChannelIds);
        }
        if (start != null) {
            qw.and(w -> w.gt(GbCloudRecordEntity::getEndTime, start)
                    .or().isNull(GbCloudRecordEntity::getEndTime));
        }
        if (end != null) {
            qw.and(w -> w.lt(GbCloudRecordEntity::getStartTime, end)
                    .or().isNull(GbCloudRecordEntity::getStartTime));
        }
        return recordMapper.selectPage(new Page<>(page, count), qw);
    }

    /**
     * on_record_mp4 入库: 按 stream_id 反查活跃会话做权限归因（channel/device/编码）。
     * 未命中活跃会话 → 孤儿分片, 丢弃不入库并 log.warn（权限归因必须精确, 宁缺勿错）。
     *
     * @return true=已入库
     */
    public boolean saveFromHook(HookMp4Chunk chunk) {
        if (chunk == null || !StringUtils.hasText(chunk.streamId())) {
            return false;
        }
        GbMediaSessionEntity s = activeByStream(chunk.streamId());
        if (s == null) {
            log.warn("on_record_mp4 孤儿分片丢弃(无活跃会话): stream={} file={}",
                    chunk.streamId(), chunk.filePath());
            return false;
        }
        GbCloudRecordEntity row = new GbCloudRecordEntity();
        row.setServerId(StringUtils.hasText(chunk.mediaServerId()) ? chunk.mediaServerId() : s.getServerId());
        row.setApp(StringUtils.hasText(chunk.app()) ? chunk.app() : s.getApp());
        row.setStreamId(chunk.streamId());
        row.setDeviceId(s.getDeviceId());
        row.setChannelDbId(s.getChannelDbId());
        row.setChannelId(s.getChannelId());
        // file_path 存 ZLM 报文原值（服务端内部使用, 下载走专用端点）
        row.setFilePath(chunk.filePath());
        row.setFileName(chunk.fileName());
        row.setFileSize(chunk.fileSize());
        int lenSec = chunk.timeLen() == null ? 0 : (int) Math.max(0, Math.round(chunk.timeLen()));
        row.setDuration(lenSec);
        LocalDateTime start;
        if (chunk.startEpoch() != null && chunk.startEpoch() > 0) {
            start = LocalDateTime.ofInstant(Instant.ofEpochSecond(chunk.startEpoch()), ZoneId.systemDefault());
        } else {
            start = LocalDateTime.now().minusSeconds(lenSec);
        }
        row.setStartTime(start);
        row.setEndTime(start.plusSeconds(lenSec));
        row.setUploadState(0);
        recordMapper.insert(row);
        log.info("云端录像分片入库: channel={} stream={} file={} size={} len={}s",
                s.getChannelId(), s.getStreamId(), chunk.fileName(), chunk.fileSize(), lenSec);
        return true;
    }

    /** stream_id → 活跃会话（无则 null） */
    private GbMediaSessionEntity activeByStream(String streamId) {
        return sessionMapper.selectOne(new LambdaQueryWrapper<GbMediaSessionEntity>()
                .eq(GbMediaSessionEntity::getStreamId, streamId)
                .in(GbMediaSessionEntity::getStatus, ACTIVE)
                .last("LIMIT 1"));
    }

    // ---------------------------------------------------------------- 按计划触发录制

    /**
     * 流就绪（on_stream_changed regist=true）触发: 该通道当前时刻按计划应录 →
     * 调 ZLM startRecord(type=1)。仅实况会话参与计划录制（回放流不落云端）;
     * 会话关闭时 ZLM 随关流自动停录, 无需显式 stop。失败仅告警不影响会话本身。
     */
    public void onStreamReady(String streamId) {
        GbMediaSessionEntity s = activeByStream(streamId);
        if (s == null) {
            recordAskAt.remove(streamId);
            return;
        }
        if (s.getSessionType() == null || s.getSessionType() != GbMediaSessionEntity.TYPE_LIVE) {
            return;
        }
        if (!plans.isRecordingDue(s.getChannelDbId(), LocalDateTime.now())) {
            return;
        }
        // 去重: put-before-check, 并发/多 schema 下至多重复一次, 可接受
        long nowMs = System.currentTimeMillis();
        Long last = recordAskAt.put(streamId, nowMs);
        if (last != null && nowMs - last < START_RECORD_DEDUPE_MS) {
            return;
        }
        String app = StringUtils.hasText(s.getApp()) ? s.getApp() : "rtp";
        try {
            zlm.startRecord(s.getServerId(), app, s.getStreamId(), 1);
            log.info("按计划开启云端录像: stream={} channel={} server={}",
                    s.getStreamId(), s.getChannelId(), s.getServerId());
        } catch (Exception e) {
            log.warn("按计划开启云端录像失败(不影响会话, 待下个分片/下次流就绪再试): stream={} err={}",
                    streamId, e.getMessage());
        }
    }

    /**
     * 流注销（on_stream_changed regist=false, B3 P1）: 移除该流的 startRecord 去重条目,
     * 防止"关流→复用同 streamId 再点播"时旧去重时间戳压制新会话的按计划开录。
     */
    public void onStreamClosed(String streamId) {
        recordAskAt.remove(streamId);
    }

    // ---------------------------------------------------------------- 删除

    /**
     * 删除录像记录并连文件: 行先删, 文件容错（文件不存在/删除失败仅删行, log 记录）。
     */
    public void delete(Long id) {
        GbCloudRecordEntity row = recordMapper.selectById(id);
        if (row == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "录像不存在: " + id);
        }
        recordMapper.deleteById(id);
        boolean removed = deleteFileIfExists(row.getFilePath());
        if (removed) {
            log.info("云端录像已删除: id={} file={}", id, row.getFilePath());
        } else if (StringUtils.hasText(row.getFilePath())) {
            log.warn("云端录像文件缺失或删除失败(仅删行): id={} file={}", id, row.getFilePath());
        }
    }

    /** 文件容错删除（清理任务复用）: 路径为空/文件不存在返回 false, 不抛异常 */
    public static boolean deleteFileIfExists(String path) {
        if (!StringUtils.hasText(path)) {
            return false;
        }
        try {
            return Files.deleteIfExists(Paths.get(path));
        } catch (Exception e) {
            log.warn("录像文件删除失败: path={} err={}", path, e.getMessage());
            return false;
        }
    }

    // ---------------------------------------------------------------- 下载

    /**
     * 生成一次性下载 token: Redisson RBucket vision:dl:{token} = JSON{recordId,userId},
     * TTL 60s, 读后即删（getAndDelete 一次性消费）。
     */
    public String createDownloadToken(Long id, Long userId) {
        GbCloudRecordEntity row = recordMapper.selectById(id);
        if (row == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "录像不存在: " + id);
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        String payload = payload(id, userId);
        redisson.<String>getBucket(DL_TOKEN_PREFIX + token).set(payload, Duration.ofSeconds(DL_TOKEN_TTL_SECONDS));
        log.info("生成录像下载 token: record={} user={} ttl={}s", id, userId, DL_TOKEN_TTL_SECONDS);
        return token;
    }

    /**
     * 消费一次性下载 token: 无效/过期/已使用返回 null（getAndDelete 原子读删, 重放无效）。
     */
    public DownloadTicket consumeDownloadToken(String token) {
        if (!StringUtils.hasText(token)) {
            return null;
        }
        String payload = redisson.<String>getBucket(DL_TOKEN_PREFIX + token).getAndDelete();
        if (!StringUtils.hasText(payload)) {
            return null;
        }
        try {
            JsonNode n = json.readTree(payload);
            long recordId = n.path("recordId").asLong(0);
            if (recordId <= 0 || !n.hasNonNull("userId")) {
                return null;
            }
            return new DownloadTicket(recordId, n.get("userId").asLong(0));
        } catch (Exception e) {
            log.warn("下载 token 载荷解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 下载解析: token → 凭证 → 定位记录与文件（数据权限复验由 Controller 持凭证执行）。
     * 文件缺失/路径非法按 404/403 语义抛出, token 已消费不回滚（防重放优先）。
     */
    public DownloadPayload resolveDownload(String token) {
        DownloadTicket ticket = consumeDownloadToken(token);
        if (ticket == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "下载令牌无效、过期或已使用");
        }
        GbCloudRecordEntity row = recordMapper.selectById(ticket.recordId());
        if (row == null || !StringUtils.hasText(row.getFilePath())) {
            throw new BizException(ErrorCode.NOT_FOUND, "录像不存在或文件已清理");
        }
        String path = row.getFilePath();
        if (path.contains("..")) {
            throw new BizException(ErrorCode.FORBIDDEN, "非法文件路径");
        }
        java.io.File file = new java.io.File(path);
        if (!file.isFile()) {
            throw new BizException(ErrorCode.NOT_FOUND, "录像文件已清理");
        }
        return new DownloadPayload(row, new FileSystemResource(file), ticket.userId());
    }

    /** 录像 → 通道 dbId（下载 token 签发前的数据权限校验用; 无记录返回 null） */
    public Long channelDbIdOf(Long id) {
        GbCloudRecordEntity row = recordMapper.selectOne(new LambdaQueryWrapper<GbCloudRecordEntity>()
                .select(GbCloudRecordEntity::getId, GbCloudRecordEntity::getChannelDbId)
                .eq(GbCloudRecordEntity::getId, id)
                .last("LIMIT 1"));
        return row == null ? null : row.getChannelDbId();
    }

    private String payload(Long recordId, Long userId) {
        try {
            return json.writeValueAsString(java.util.Map.of("recordId", recordId, "userId", userId));
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "下载令牌生成失败");
        }
    }
}
