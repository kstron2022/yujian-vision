package com.yujian.vision.service.device.service;

import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.common.security.AesCipher;
import com.yujian.vision.service.device.entity.GbAccessConfigEntity;
import com.yujian.vision.service.device.mapper.GbAccessConfigMapper;
import com.yujian.vision.service.sip.config.GbPlatformProperties;
import com.yujian.vision.sip.config.SipStackProperties;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 国标接入配置（M3-B3 阶段1, docs/08 §3.1）：
 * 只读展示接入信息（服务器编码/域/SIP 端口/传输, 来自配置文件, 改身份=换平台不走此口）；
 * 默认接入密码运行时可改（AES 落库 gb_access_config, 未设置回退 vision.gb.default-device-password）。
 * DeviceRegisterService 对未建档设备解析密码时优先取本配置。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccessConfigService {

    private final GbAccessConfigMapper mapper;
    private final GbPlatformProperties platform;
    private final SipStackProperties sipStacks;
    private final AesCipher aes;

    /** 接入配置视图（docs/03 §3 access-config; 密码只出"是否已自定义", 不出明文; 开关出 0/1 与 PUT 对齐;
     *  sipIp=对接展示用服务器 IP, 供前端复制对接信息——2026-09-25 现场反馈此前复制不含 IP） */
    public record AccessConfigView(String serverId, String domain, String sipIp, int sipPort, String transports,
            int allowAutoRegister, boolean defaultPasswordSet, String defaultPasswordSource,
            java.time.LocalDateTime updateTime) {
    }

    public AccessConfigView get() {
        GbAccessConfigEntity row = selectRow();
        boolean custom = row != null && row.getDefaultPassword() != null
                && !row.getDefaultPassword().isBlank();
        return new AccessConfigView(platform.getServerId(), platform.getDomain(), sipIp(),
                mainPort(), transports(), isAutoRegisterAllowed() ? 1 : 0, custom,
                custom ? "db" : "config", row == null ? null : row.getUpdateTime());
    }

    /**
     * 修改接入配置（patch 语义, docs/08 §3.1）：
     * defaultPassword 传 null 不改（≥8 位校验）; allowAutoRegister 传 null 不改（白名单/开放模式开关）。
     */
    public void update(String defaultPassword, Integer allowAutoRegister) {
        if (allowAutoRegister != null && allowAutoRegister != 0 && allowAutoRegister != 1) {
            throw new BizException(ErrorCode.BAD_REQUEST, "allowAutoRegister 仅支持 0(白名单)/1(开放)");
        }
        if (defaultPassword != null && defaultPassword.trim().length() < 8) {
            throw new BizException(ErrorCode.BAD_REQUEST, "默认接入密码至少 8 位");
        }
        GbAccessConfigEntity row = selectRow();
        boolean exists = row != null;
        if (row == null) {
            row = new GbAccessConfigEntity();
            row.setId(1L);
        }
        if (defaultPassword != null) {
            row.setDefaultPassword(aes.encrypt(defaultPassword.trim()));
        }
        if (allowAutoRegister != null) {
            row.setAllowAutoRegister(allowAutoRegister);
        }
        if (exists) {
            mapper.updateById(row);
        } else {
            mapper.insert(row);
        }
        log.info("接入配置已更新: autoRegister={}", isAutoRegisterAllowed());
    }

    /** 白名单模式判断（未配置行=默认白名单, V18 起安全默认） */
    public boolean isAutoRegisterAllowed() {
        GbAccessConfigEntity row = selectRow();
        return row != null && row.getAllowAutoRegister() != null && row.getAllowAutoRegister() == 1;
    }

    /** 未建档设备注册时的默认密码：库内自定义优先, 否则内置默认（docs/10 生产预配强密码） */
    public String resolveDefaultPassword() {
        GbAccessConfigEntity row = selectRow();
        if (row != null && row.getDefaultPassword() != null && !row.getDefaultPassword().isBlank()) {
            return aes.decrypt(row.getDefaultPassword());
        }
        return platform.getDefaultDevicePassword();
    }

    private GbAccessConfigEntity selectRow() {
        return mapper.selectById(1L);
    }

    private int mainPort() {
        return sipStacks.getStacks().isEmpty() ? 5060 : sipStacks.getStacks().get(0).getPort();
    }

    /**
     * 对接展示用服务器 IP: SIP 宣告地址(publicIp, VISION_SIP_PUBLIC_IP)优先——那是设备实际
     * 应注册的地址; 其次取具体栈监听地址。0.0.0.0=监听全部网卡不具体, 回空串由前端回落
     * 当前访问主机名（浏览器访问平台用的就是服务器 IP）。
     */
    private String sipIp() {
        String pub = sipStacks.getPublicIp();
        if (pub != null && !pub.isBlank()) {
            return pub.trim();
        }
        for (SipStackProperties.StackSpec s : sipStacks.getStacks()) {
            String h = s.getHost();
            if (h != null && !h.isBlank() && !"0.0.0.0".equals(h.trim())) {
                return h.trim();
            }
        }
        return "";
    }

    private String transports() {
        Set<String> set = new LinkedHashSet<>();
        for (SipStackProperties.StackSpec s : sipStacks.getStacks()) {
            set.add(s.getTransport());
        }
        return set.isEmpty() ? "UDP" : String.join("/", set);
    }
}
