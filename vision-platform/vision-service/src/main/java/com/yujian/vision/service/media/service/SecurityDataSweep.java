package com.yujian.vision.service.media.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yujian.vision.common.security.AesCipher;
import com.yujian.vision.service.cascade.entity.GbCascadeUpperEntity;
import com.yujian.vision.service.cascade.mapper.GbCascadeUpperMapper;
import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.device.mapper.GbDeviceMapper;
import com.yujian.vision.service.media.entity.GbMediaServerEntity;
import com.yujian.vision.service.media.mapper.GbMediaServerMapper;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.lang.Nullable;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Component;

/**
 * 安全基线扫描（docs/02 §0）：启动后把存量明文敏感字段加密回写, 并在密钥轮换窗口
 * 把旧密钥密文迁移到当前密钥（docs/10 §1, 运维步骤见 deploy/README）。
 * 覆盖 gb_device.password / gb_media_server.sign_secret / gb_cascade_upper.password（M3-B1）。
 * decrypt 对存量明文兼容读取。每行三分支：当前密钥可解=不动（幂等）; 仅旧密钥可解=当前密钥
 * 重加密回写（轮换）; 均不可解=跳过不动数据（未知密钥/损坏, 与换错密钥的历史行为一致）。
 * 失败仅记录日志, 不阻断启动。
 */
@Slf4j
@Component
public class SecurityDataSweep {

    private final GbDeviceMapper deviceMapper;
    private final GbMediaServerMapper serverMapper;
    private final GbCascadeUpperMapper upperMapper;
    private final AesCipher aes;
    /** 旧密钥加密器（可选 Bean, 仅轮换窗口装配; null=不启用轮换） */
    private final AesCipher legacyAes;

    private final AtomicBoolean done = new AtomicBoolean(false);

    public SecurityDataSweep(GbDeviceMapper deviceMapper, GbMediaServerMapper serverMapper,
            GbCascadeUpperMapper upperMapper, @Qualifier("aesCipher") AesCipher aes,
            @Nullable @Qualifier("legacyAesCipher") AesCipher legacyAes) {
        this.deviceMapper = deviceMapper;
        this.serverMapper = serverMapper;
        this.upperMapper = upperMapper;
        this.aes = aes;
        this.legacyAes = legacyAes;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!done.compareAndSet(false, true)) {
            return;
        }
        try {
            sweep();
        } catch (Exception e) {
            log.error("安全基线扫描执行异常(不阻断启动)", e);
        }
    }

    /** 单表扫描结果: ok=加密回写条数, rotated=旧密钥轮换条数, failed=失败跳过条数 */
    private record Stats(int ok, int rotated, int failed) {
    }

    /** 包私有便于单测 */
    void sweep() {
        Stats devices = sweepDevices();
        Stats servers = sweepServers();
        Stats uppers = sweepUppers();
        int total = devices.ok() + devices.rotated() + devices.failed()
                + servers.ok() + servers.rotated() + servers.failed()
                + uppers.ok() + uppers.rotated() + uppers.failed();
        if (total == 0) {
            log.info("安全基线: 无明文/待轮换敏感数据");
            return;
        }
        log.info("安全基线: 设备密码 加密 {} 轮换 {} (失败跳过 {}), 节点密钥 加密 {} 轮换 {} (失败跳过 {}), 级联密码 加密 {} 轮换 {} (失败跳过 {})",
                devices.ok(), devices.rotated(), devices.failed(),
                servers.ok(), servers.rotated(), servers.failed(),
                uppers.ok(), uppers.rotated(), uppers.failed());
    }

    /**
     * 密钥轮换判定: 入参必为本工具密文。当前密钥可解 → null(无需迁移, 幂等);
     * 仅旧密钥可解 → 当前密钥重加密的新密文(调用方回写);
     * 当前密钥解不开且旧密钥未装配或也解不开 → 抛异常(调用方跳过该行不动数据)。
     */
    private String rotateIfLegacy(String stored) {
        try {
            aes.decrypt(stored);
            return null;
        } catch (Exception notCurrentKey) {
            if (legacyAes == null) {
                throw new IllegalStateException("当前密钥无法解密且未配置旧密钥(疑似换钥未迁移或数据损坏)");
            }
            String plain = legacyAes.decrypt(stored);
            return aes.encrypt(plain);
        }
    }

    /**
     * 设备表存量明文 password 加密回写 + 旧密文轮换（只 set id+password, 逐条 updateById）。
     * 单行失败只跳过该行(如列长不够/Data truncation), 不中止整表; 整表失败不影响另一张表。
    /**
     * 设备表存量明文 password 加密回写 + 旧密文轮换（只 set id+password, 逐条 updateById）。
     * 单行失败只跳过该行(如列长不够/Data truncation), 不中止整表; 整表失败不影响另一张表。
     */
    private Stats sweepDevices() {
        int ok = 0;
        int rotated = 0;
        int failed = 0;
        try {
            List<GbDeviceEntity> rows = deviceMapper.selectList(new LambdaQueryWrapper<GbDeviceEntity>()
                    .isNull(GbDeviceEntity::getDeletedTime)
                    .isNotNull(GbDeviceEntity::getPassword)
                    .ne(GbDeviceEntity::getPassword, ""));
            for (GbDeviceEntity d : rows) {
                String p = d.getPassword();
                if (p == null || p.isBlank()) {
                    continue;
                }
                if (!AesCipher.isEncrypted(p)) {
                    try {
                        GbDeviceEntity up = new GbDeviceEntity();
                        up.setId(d.getId());
                        up.setPassword(aes.encrypt(p));
                        deviceMapper.updateById(up);
                        ok++;
                    } catch (Exception e) {
                        failed++;
                        log.warn("安全基线: 加密失败(跳过): 表={} id={} ({})", "gb_device", d.getId(), e.getMessage());
                    }
                } else {
                    String migrated;
                    try {
                        migrated = rotateIfLegacy(p);
                    } catch (Exception e) {
                        failed++;
                        log.warn("安全基线: 密钥轮换跳过(不动数据): 表={} id={} ({})", "gb_device", d.getId(), e.getMessage());
                        continue;
                    }
                    if (migrated != null) {
                        try {
                            GbDeviceEntity up = new GbDeviceEntity();
                            up.setId(d.getId());
                            up.setPassword(migrated);
                            deviceMapper.updateById(up);
                            rotated++;
                        } catch (Exception e) {
                            failed++;
                            log.warn("安全基线: 轮换回写失败(跳过): 表={} id={} ({})", "gb_device", d.getId(), e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("安全基线: 设备表扫描中止(已加密 {} 条, 轮换 {} 条, 跳过 {} 条)", ok, rotated, failed, e);
        }
        return new Stats(ok, rotated, failed);
    }

    /** 节点表存量明文 sign_secret 加密回写 + 旧密文轮换, 容错口径同设备表 */
    private Stats sweepServers() {
        int ok = 0;
        int rotated = 0;
        int failed = 0;
        try {
            List<GbMediaServerEntity> rows = serverMapper.selectList(new LambdaQueryWrapper<GbMediaServerEntity>()
                    .isNotNull(GbMediaServerEntity::getSignSecret)
                    .ne(GbMediaServerEntity::getSignSecret, ""));
            for (GbMediaServerEntity s : rows) {
                String v = s.getSignSecret();
                if (v == null || v.isBlank()) {
                    continue;
                }
                if (!AesCipher.isEncrypted(v)) {
                    try {
                        GbMediaServerEntity up = new GbMediaServerEntity();
                        up.setId(s.getId());
                        up.setSignSecret(aes.encrypt(v));
                        serverMapper.updateById(up);
                        ok++;
                    } catch (Exception e) {
                        failed++;
                        log.warn("安全基线: 加密失败(跳过): 表={} id={} ({})", "gb_media_server", s.getId(), e.getMessage());
                    }
                } else {
                    String migrated;
                    try {
                        migrated = rotateIfLegacy(v);
                    } catch (Exception e) {
                        failed++;
                        log.warn("安全基线: 密钥轮换跳过(不动数据): 表={} id={} ({})", "gb_media_server", s.getId(), e.getMessage());
                        continue;
                    }
                    if (migrated != null) {
                        try {
                            GbMediaServerEntity up = new GbMediaServerEntity();
                            up.setId(s.getId());
                            up.setSignSecret(migrated);
                            serverMapper.updateById(up);
                            rotated++;
                        } catch (Exception e) {
                            failed++;
                            log.warn("安全基线: 轮换回写失败(跳过): 表={} id={} ({})", "gb_media_server", s.getId(), e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("安全基线: 节点表扫描中止(已加密 {} 条, 轮换 {} 条, 跳过 {} 条)", ok, rotated, failed, e);
        }
        return new Stats(ok, rotated, failed);
    }

    /** 上级平台表存量明文 password 加密回写 + 旧密文轮换, 容错口径同设备表（docs/02 §0: cascade 随 M3 接入） */
    private Stats sweepUppers() {
        int ok = 0;
        int rotated = 0;
        int failed = 0;
        try {
            List<GbCascadeUpperEntity> rows = upperMapper.selectList(new LambdaQueryWrapper<GbCascadeUpperEntity>()
                    .isNotNull(GbCascadeUpperEntity::getPassword)
                    .ne(GbCascadeUpperEntity::getPassword, ""));
            for (GbCascadeUpperEntity u : rows) {
                String v = u.getPassword();
                if (v == null || v.isBlank()) {
                    continue;
                }
                if (!AesCipher.isEncrypted(v)) {
                    try {
                        GbCascadeUpperEntity up = new GbCascadeUpperEntity();
                        up.setId(u.getId());
                        up.setPassword(aes.encrypt(v));
                        upperMapper.updateById(up);
                        ok++;
                    } catch (Exception e) {
                        failed++;
                        log.warn("安全基线: 加密失败(跳过): 表={} id={} ({})", "gb_cascade_upper", u.getId(), e.getMessage());
                    }
                } else {
                    String migrated;
                    try {
                        migrated = rotateIfLegacy(v);
                    } catch (Exception e) {
                        failed++;
                        log.warn("安全基线: 密钥轮换跳过(不动数据): 表={} id={} ({})", "gb_cascade_upper", u.getId(), e.getMessage());
                        continue;
                    }
                    if (migrated != null) {
                        try {
                            GbCascadeUpperEntity up = new GbCascadeUpperEntity();
                            up.setId(u.getId());
                            up.setPassword(migrated);
                            upperMapper.updateById(up);
                            rotated++;
                        } catch (Exception e) {
                            failed++;
                            log.warn("安全基线: 轮换回写失败(跳过): 表={} id={} ({})", "gb_cascade_upper", u.getId(), e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("安全基线: 上级平台表扫描中止(已加密 {} 条, 轮换 {} 条, 跳过 {} 条)", ok, rotated, failed, e);
        }
        return new Stats(ok, rotated, failed);
    }
}
