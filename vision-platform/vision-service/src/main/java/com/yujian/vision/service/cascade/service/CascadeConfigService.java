package com.yujian.vision.service.cascade.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.common.security.AesCipher;
import com.yujian.vision.service.cascade.entity.GbCascadeSharedChannelEntity;
import com.yujian.vision.service.cascade.entity.GbCascadeUpperEntity;
import com.yujian.vision.service.cascade.mapper.GbCascadeSharedChannelMapper;
import com.yujian.vision.service.cascade.mapper.GbCascadeUpperMapper;
import com.yujian.vision.service.device.entity.GbDeviceChannelEntity;
import com.yujian.vision.service.device.mapper.GbDeviceChannelMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 级联上级配置管理（docs/03 §9）：CRUD/启停/共享通道绑定。
 * 写操作后联动级联 UA 状态机（{@link CascadeUpperManager#reload}）,
 * auto_push=1 且已启用时共享通道变化即主动推送目录（docs/08 §2.2）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CascadeConfigService {

    private final GbCascadeUpperMapper upperMapper;
    private final GbCascadeSharedChannelMapper sharedMapper;
    private final GbDeviceChannelMapper channelMapper;
    private final CascadeUpperManager upperManager;
    private final CascadeCatalogService catalogService;
    private final AesCipher aes;

    /** 上级配置分页（表小, keyword 匹配名称/上级编码） */
    public IPage<GbCascadeUpperEntity> page(String keyword, int page, int count) {
        return upperMapper.selectPage(new Page<>(page, count),
                new LambdaQueryWrapper<GbCascadeUpperEntity>()
                        .and(StringUtils.hasText(keyword), w -> w
                                .like(GbCascadeUpperEntity::getName, keyword)
                                .or().like(GbCascadeUpperEntity::getUpperDeviceId, keyword))
                        .orderByDesc(GbCascadeUpperEntity::getId));
    }

    public GbCascadeUpperEntity detail(Long id) {
        GbCascadeUpperEntity u = upperMapper.selectById(id);
        if (u == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "上级平台配置不存在: " + id);
        }
        return u;
    }

    /** 上级配置请求体（password 明文入参, 服务内 AES 加密; 实体字段 @JsonIgnore 不可直接反序列化） */
    public record UpperRequest(String name, String upperDeviceId, String localDeviceId, String upperIp,
            Integer upperPort, String password, String transport, Integer expires, Integer keepaliveInterval,
            Integer catalogMode, Integer autoPushCatalog, Integer ptzForward) {
    }

    /** 新增上级配置（默认停用, enable 接口显式启用） */
    @Transactional
    public GbCascadeUpperEntity create(UpperRequest req) {
        GbCascadeUpperEntity e = new GbCascadeUpperEntity();
        e.setName(req.name());
        e.setUpperDeviceId(req.upperDeviceId());
        e.setLocalDeviceId(req.localDeviceId());
        e.setUpperIp(req.upperIp());
        e.setUpperPort(req.upperPort());
        e.setPassword(req.password());
        e.setTransport(req.transport());
        e.setExpires(req.expires());
        e.setKeepaliveInterval(req.keepaliveInterval());
        e.setCatalogMode(req.catalogMode());
        e.setAutoPushCatalog(req.autoPushCatalog());
        e.setPtzForward(req.ptzForward());
        validate(e);
        if (upperMapper.selectCount(new LambdaQueryWrapper<GbCascadeUpperEntity>()
                .eq(GbCascadeUpperEntity::getUpperDeviceId, e.getUpperDeviceId())) > 0) {
            throw new BizException(ErrorCode.CASCADE_CONFIG_ERROR, "上级编码已存在: " + e.getUpperDeviceId());
        }
        applyDefaults(e);
        if (StringUtils.hasText(e.getPassword())) {
            e.setPassword(aes.encrypt(e.getPassword()));
        }
        e.setOnline(0);
        upperMapper.insert(e);
        upperManager.reload(e.getId());
        return e;
    }

    /** 修改配置（password 留空=不修改; 修改后联动级联 UA） */
    public GbCascadeUpperEntity update(Long id, UpperRequest req) {
        GbCascadeUpperEntity u = detail(id);
        if (StringUtils.hasText(req.name())) {
            u.setName(req.name());
        }
        if (StringUtils.hasText(req.localDeviceId())) {
            u.setLocalDeviceId(req.localDeviceId());
        }
        if (StringUtils.hasText(req.upperIp())) {
            u.setUpperIp(req.upperIp());
        }
        if (req.upperPort() != null) {
            u.setUpperPort(req.upperPort());
        }
        if (StringUtils.hasText(req.transport())) {
            u.setTransport(req.transport());
        }
        if (req.expires() != null) {
            u.setExpires(req.expires());
        }
        if (req.keepaliveInterval() != null) {
            u.setKeepaliveInterval(req.keepaliveInterval());
        }
        if (req.catalogMode() != null) {
            u.setCatalogMode(req.catalogMode());
        }
        if (req.autoPushCatalog() != null) {
            u.setAutoPushCatalog(req.autoPushCatalog());
        }
        if (req.ptzForward() != null) {
            u.setPtzForward(req.ptzForward());
        }
        if (StringUtils.hasText(req.password())) {
            u.setPassword(aes.encrypt(req.password()));
        }
        validate(u);
        if (StringUtils.hasText(req.upperDeviceId()) && !req.upperDeviceId().equals(u.getUpperDeviceId())) {
            if (upperMapper.selectCount(new LambdaQueryWrapper<GbCascadeUpperEntity>()
                    .eq(GbCascadeUpperEntity::getUpperDeviceId, req.upperDeviceId())
                    .ne(GbCascadeUpperEntity::getId, id)) > 0) {
                throw new BizException(ErrorCode.CASCADE_CONFIG_ERROR, "上级编码已存在: " + req.upperDeviceId());
            }
            u.setUpperDeviceId(req.upperDeviceId());
        }
        upperMapper.updateById(u);
        upperManager.reload(id);
        return u;
    }
    /** 删除配置（级联共享绑定一并清理, 运行会话随之撤销） */
    @Transactional
    public void delete(Long id) {
        detail(id);
        sharedMapper.delete(new LambdaQueryWrapper<GbCascadeSharedChannelEntity>()
                .eq(GbCascadeSharedChannelEntity::getUpperId, id));
        upperMapper.deleteById(id);
        upperManager.reload(id);
    }

    /** 启停注册（0/1） */
    public void enable(Long id, int enabled) {
        GbCascadeUpperEntity u = detail(id);
        u.setStatus(enabled == 1 ? 1 : 0);
        upperMapper.updateById(u);
        upperManager.reload(id);
    }

    /** 共享通道绑定列表（带通道摘要） */
    public List<Map<String, Object>> listShared(Long upperId) {
        detail(upperId);
        List<GbCascadeSharedChannelEntity> shared = sharedMapper.selectList(
                new LambdaQueryWrapper<GbCascadeSharedChannelEntity>()
                        .eq(GbCascadeSharedChannelEntity::getUpperId, upperId)
                        .orderByAsc(GbCascadeSharedChannelEntity::getId));
        if (shared.isEmpty()) {
            return List.of();
        }
        List<Long> ids = shared.stream().map(GbCascadeSharedChannelEntity::getChannelDbId).toList();
        Map<Long, GbDeviceChannelEntity> channels = channelMapper.selectList(
                        new LambdaQueryWrapper<GbDeviceChannelEntity>()
                                .in(GbDeviceChannelEntity::getId, ids))
                .stream().collect(java.util.stream.Collectors.toMap(
                        GbDeviceChannelEntity::getId, c -> c));
        List<Map<String, Object>> result = new ArrayList<>(shared.size());
        for (GbCascadeSharedChannelEntity s : shared) {
            GbDeviceChannelEntity c = channels.get(s.getChannelDbId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", s.getId());
            row.put("channelDbId", s.getChannelDbId());
            row.put("virtualDeviceId", s.getVirtualDeviceId());
            row.put("enabled", s.getEnabled());
            row.put("channelId", c != null ? c.getChannelId() : null);
            row.put("name", c != null ? c.getName() : null);
            row.put("status", c != null ? c.getStatus() : null);
            result.add(row);
        }
        return result;
    }

    /** 整体替换共享通道绑定（前端树勾选后一次性提交; channel_db_id 去重, 通道须存在未删） */
    @Transactional
    public int replaceShared(Long upperId, List<GbCascadeSharedChannelEntity> bindings) {
        detail(upperId);
        Set<Long> seen = new HashSet<>();
        for (GbCascadeSharedChannelEntity b : bindings == null ? List.<GbCascadeSharedChannelEntity>of() : bindings) {
            if (b.getChannelDbId() == null || !seen.add(b.getChannelDbId())) {
                throw new BizException(ErrorCode.CASCADE_CONFIG_ERROR, "共享通道重复或为空");
            }
        }
        sharedMapper.delete(new LambdaQueryWrapper<GbCascadeSharedChannelEntity>()
                .eq(GbCascadeSharedChannelEntity::getUpperId, upperId));
        for (GbCascadeSharedChannelEntity b : bindings == null ? List.<GbCascadeSharedChannelEntity>of() : bindings) {
            GbDeviceChannelEntity c = channelMapper.selectById(b.getChannelDbId());
            if (c == null || c.getDeletedTime() != null) {
                throw new BizException(ErrorCode.CASCADE_CONFIG_ERROR,
                        "通道不存在或已删除: " + b.getChannelDbId());
            }
            b.setId(null);
            b.setUpperId(upperId);
            b.setEnabled(1);
            sharedMapper.insert(b);
        }
        pushIfAuto(upperId);
        return bindings == null ? 0 : bindings.size();
    }

    /** 删除共享绑定（channelDbId 为空=清空该上级全部共享通道） */
    @Transactional
    public void removeShared(Long upperId, Long channelDbId) {
        detail(upperId);
        sharedMapper.delete(new LambdaQueryWrapper<GbCascadeSharedChannelEntity>()
                .eq(GbCascadeSharedChannelEntity::getUpperId, upperId)
                .eq(channelDbId != null, GbCascadeSharedChannelEntity::getChannelDbId, channelDbId));
        pushIfAuto(upperId);
    }

    /** auto_push=1 且已启用 → 目录变化即主动通知上级（失败不阻断配置写入） */
    private void pushIfAuto(Long upperId) {
        GbCascadeUpperEntity u = upperMapper.selectById(upperId);
        if (u == null || u.getStatus() == null || u.getStatus() != 1
                || u.getAutoPushCatalog() == null || u.getAutoPushCatalog() != 1) {
            return;
        }
        try {
            catalogService.pushCatalog(upperId);
        } catch (Exception e) {
            log.warn("目录主动推送失败(不影响配置保存): upperId={}", upperId, e);
        }
    }

    private void validate(GbCascadeUpperEntity req) {
        if (!StringUtils.hasText(req.getName()) || !StringUtils.hasText(req.getUpperDeviceId())
                || !StringUtils.hasText(req.getLocalDeviceId()) || !StringUtils.hasText(req.getUpperIp())) {
            throw new BizException(ErrorCode.CASCADE_CONFIG_ERROR,
                    "名称/上级编码/本级编码/上级地址均必填");
        }
    }

    private void applyDefaults(GbCascadeUpperEntity req) {
        if (!StringUtils.hasText(req.getTransport())) {
            req.setTransport("UDP");
        }
        if (req.getUpperPort() == null) {
            req.setUpperPort(5060);
        }
        if (req.getExpires() == null) {
            req.setExpires(3600);
        }
        if (req.getKeepaliveInterval() == null) {
            req.setKeepaliveInterval(60);
        }
        if (req.getCatalogMode() == null) {
            req.setCatalogMode(1);
        }
        if (req.getAutoPushCatalog() == null) {
            req.setAutoPushCatalog(0);
        }
        if (req.getPtzForward() == null) {
            req.setPtzForward(1);
        }
        if (req.getStatus() == null) {
            req.setStatus(0);
        }
    }
}
