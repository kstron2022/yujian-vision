package com.yujian.vision.service.cascade.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.gb28181.codec.ManScdpCodec;
import com.yujian.vision.gb28181.message.ManScdpMessage;
import com.yujian.vision.service.cascade.entity.GbCascadeSharedChannelEntity;
import com.yujian.vision.service.cascade.entity.GbCascadeUpperEntity;
import com.yujian.vision.service.device.entity.GbDeviceChannelEntity;
import com.yujian.vision.service.device.mapper.GbDeviceChannelMapper;
import com.yujian.vision.service.cascade.mapper.GbCascadeSharedChannelMapper;
import com.yujian.vision.service.cascade.mapper.GbCascadeUpperMapper;
import com.yujian.vision.sip.transport.SipEvent;
import com.yujian.vision.sip.transport.SipIds;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 级联目录上报（本平台作为下级, docs/08 §2.2）：应答上级 Query/Catalog（多包 RESPONSE,
 * SN 全包回显, SumNum=总数）；auto_push 或管理端手动触发的目录主动通知（Notify/Catalog）。
 * 空目录也回一包空应答（SumNum=0）, 让上级立即收敛。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CascadeCatalogService {

    /** 单包条数上限（上级对单包条数有约定, docs/08 §2.2, 联调可调） */
    @Value("${vision.cascade.catalog-page-size:20}")
    private int catalogPageSize;

    /** 多包间发送间隔 ms（上级限速保护, docs/09 B2 评审挂账） */
    @Value("${vision.cascade.catalog-send-interval-ms:100}")
    private long catalogSendIntervalMs;

    /**
     * 目录分包发送线程（单线程 + daemon）：多包连发含包间 sleep, 不能占 SIP dispatch 线程
     * （B2 阶段 4, 与 cascade-invite 同原则）; 单线程串行使多上级/多次查询天然排队限速。
     */
    private final java.util.concurrent.ExecutorService catalogExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "cascade-catalog");
                t.setDaemon(true);
                return t;
            });

    private final GbCascadeUpperMapper upperMapper;
    private final GbCascadeSharedChannelMapper sharedMapper;
    private final GbDeviceChannelMapper channelMapper;
    private final CascadeSipSender sender;
    private final ManScdpCodec codec;

    /**
     * 应答上级 Query/Catalog：从共享通道（catalog_mode=2 时取全部通道）组装条目,
     * 按 {@code vision.cascade.catalog-page-size} 分包回 RESPONSE（MESSAGE 新事务）。
     */
    public void onUpperQuery(SipEvent event, ManScdpMessage message) {
        GbCascadeUpperEntity u = findByUpperDeviceId(event.getFromUser());
        if (u == null || u.getStatus() == null || u.getStatus() != 1) {
            log.debug("非上级来源的目录查询, 忽略: fromUser={}", event.getFromUser());
            return;
        }
        List<Map<String, String>> items = sharedItems(u);
        String queryDeviceId = message.getDeviceId() != null ? message.getDeviceId() : u.getLocalDeviceId();
        long sn = message.getSn() != null ? message.getSn() : SipIds.nextSn();
        // 分包发送投递 cascade-catalog 线程（B2 阶段 4）: sip-dispatch 线程只做鉴权与组装
        catalogExecutor.execute(() -> {
            try {
                sendPaged(u, sn, items,
                        (s, packet) -> codec.buildCatalogResponse(queryDeviceId, s, items.size(), packet),
                        "上报");
            } catch (Exception e) {
                // 兜底: 编排线程不允许带异常消亡
                log.error("级联目录上报异常: upper={}", u.getName(), e);
            }
        });
    }

    /**
     * 主动推送目录（管理端手动推送 API / auto_push_catalog=1 时共享通道变化触发）。
     * 2016 版实现：以下级发 Catalog 通知消息（Notify/Catalog）, 上级兼容性联调确认（docs/08 §2.2）。
     */
    public void pushCatalog(Long upperId) {
        GbCascadeUpperEntity u = upperMapper.selectById(upperId);
        if (u == null || u.getStatus() == null || u.getStatus() != 1) {
            throw new BizException(ErrorCode.CASCADE_CONFIG_ERROR, "上级不存在或未启用");
        }
        List<Map<String, String>> items = sharedItems(u);
        long sn = SipIds.nextSn();
        catalogExecutor.execute(() -> {
            try {
                sendPaged(u, sn, items,
                        (s, packet) -> codec.buildCatalogNotify(u.getLocalDeviceId(), s, packet),
                        "主动推送");
            } catch (Exception e) {
                // 兜底: 编排线程不允许带异常消亡
                log.error("级联目录主动推送异常: upper={}", u.getName(), e);
            }
        });
    }

    /**
     * 分包发送核心（cascade-catalog 线程执行）：按 catalog-page-size 分包, 包间
     * catalog-send-interval-ms 限速; 空目录回一包空应答让上级立即收敛。
     * bodyFactory 产出各包 MANSCDP+XML（Response 全包回显 Query SN / Notify 用推送 SN）,
     * SIP 事务 SN 每包独立（SipIds.nextSn()）。scene 仅用于日志区分「目录上报/主动推送」。
     */
    private void sendPaged(GbCascadeUpperEntity u, long sn, List<Map<String, String>> items,
                           BiFunction<Long, List<Map<String, String>>, String> bodyFactory, String scene) {
        int sumNum = items.size();
        int pageSize = catalogPageSize <= 0 ? 20 : catalogPageSize;
        int packets = (sumNum + pageSize - 1) / pageSize;
        if (packets == 0) {
            packets = 1; // 空目录回一包空应答
        }
        for (int p = 1; p <= packets; p++) {
            if (p > 1 && catalogSendIntervalMs > 0) {
                try {
                    Thread.sleep(catalogSendIntervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("级联目录分页发送被中断, 剩余 {} 包未发: upper={} scene={}",
                            packets - p + 1, u.getName(), scene);
                    return;
                }
            }
            List<Map<String, String>> packet = items.subList((p - 1) * pageSize, Math.min(p * pageSize, sumNum));
            sender.sendMessage(u, bodyFactory.apply(sn, packet), SipIds.nextSn());
        }
        log.info("级联目录{}: upper={} 通道 {} 条/ {} 包 (SN={})",
                scene, u.getName(), sumNum, packets, sn);
    }

    /** 共享通道条目组装：虚拟编码替换在此完成（virtual_device_id 非空则替换 DeviceID） */
    private List<Map<String, String>> sharedItems(GbCascadeUpperEntity u) {
        List<GbCascadeSharedChannelEntity> shared = sharedMapper.selectList(
                new LambdaQueryWrapper<GbCascadeSharedChannelEntity>()
                        .eq(GbCascadeSharedChannelEntity::getUpperId, u.getId())
                        .eq(GbCascadeSharedChannelEntity::getEnabled, 1));
        List<GbDeviceChannelEntity> channels;
        if (u.getCatalogMode() != null && u.getCatalogMode() == 2) {
            // 目录模式=全部通道：忽略共享配置, 上报库内全部未删通道
            channels = channelMapper.selectList(new LambdaQueryWrapper<GbDeviceChannelEntity>()
                    .isNull(GbDeviceChannelEntity::getDeletedTime));
        } else {
            if (shared.isEmpty()) {
                return new ArrayList<>();
            }
            List<Long> ids = shared.stream()
                    .map(GbCascadeSharedChannelEntity::getChannelDbId).toList();
            channels = channelMapper.selectList(new LambdaQueryWrapper<GbDeviceChannelEntity>()
                    .in(GbDeviceChannelEntity::getId, ids)
                    .isNull(GbDeviceChannelEntity::getDeletedTime));
        }
        // virtual_device_id 非空替换编码（docs/08 §2.2）
        Map<Long, String> virtual = new HashMap<>();
        for (GbCascadeSharedChannelEntity s : shared) {
            if (s.getVirtualDeviceId() != null && !s.getVirtualDeviceId().isBlank()) {
                virtual.put(s.getChannelDbId(), s.getVirtualDeviceId());
            }
        }
        List<Map<String, String>> items = new ArrayList<>(channels.size());
        for (GbDeviceChannelEntity c : channels) {
            items.add(item(c, virtual.get(c.getId())));
        }
        return items;
    }

    /** 目录 Item 字段组装（字段名按国标目录项, 名称取自定义名优先, Status 反映通道在线态） */
    private Map<String, String> item(GbDeviceChannelEntity c, String virtualId) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("DeviceID", virtualId != null ? virtualId : c.getChannelId());
        m.put("Name", c.getCustomName() != null && !c.getCustomName().isBlank()
                ? c.getCustomName() : c.getName());
        if (c.getManufacturer() != null) {
            m.put("Manufacturer", c.getManufacturer());
        }
        if (c.getModel() != null) {
            m.put("Model", c.getModel());
        }
        if (c.getCivilCode() != null) {
            m.put("CivilCode", c.getCivilCode());
        }
        if (c.getAddress() != null) {
            m.put("Address", c.getAddress());
        }
        m.put("Parental", c.getParental() != null ? String.valueOf(c.getParental()) : "0");
        if (c.getParentId() != null) {
            m.put("ParentID", c.getParentId());
        }
        if (c.getBusinessGroupId() != null) {
            m.put("BusinessGroupID", c.getBusinessGroupId());
        }
        m.put("RegisterWay", c.getRegisterWay() != null ? String.valueOf(c.getRegisterWay()) : "1");
        m.put("Secrecy", c.getSecrecy() != null ? String.valueOf(c.getSecrecy()) : "0");
        // PTZType 国标目录字段（0 未知/1 球机/2 半球/3 枪机/4 遥控枪机）: 2026-09-25 LiveGBS
        // 联调补齐——库内 GB(目录同步)/ONVIF(PTZConfiguration 推导)均已有值, 缺此字段对端显示"未知"
        if (c.getPtzType() != null) {
            // 5=平台内部扩展值"不支持", 超出国标 0~4 值域, 级联面归一为 0 防对端判非法
            m.put("PTZType", String.valueOf(c.getPtzType() == 5 ? 0 : c.getPtzType()));
        }
        m.put("Status", c.getStatus() != null && c.getStatus() == 1 ? "ON" : "OFF");
        return m;
    }

    private GbCascadeUpperEntity findByUpperDeviceId(String upperDeviceId) {
        if (upperDeviceId == null || upperDeviceId.isBlank()) {
            return null;
        }
        List<GbCascadeUpperEntity> list = upperMapper.selectList(
                new LambdaQueryWrapper<GbCascadeUpperEntity>()
                        .eq(GbCascadeUpperEntity::getUpperDeviceId, upperDeviceId)
                        .eq(GbCascadeUpperEntity::getStatus, 1));
        return list.isEmpty() ? null : list.get(0);
    }
}
