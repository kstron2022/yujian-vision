package com.yujian.vision.service.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yujian.vision.service.system.entity.SysLoginLogEntity;
import com.yujian.vision.service.system.mapper.SysLoginLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 登录日志（sys_login_log, 留存 ≥6 个月, docs/10 §1）
 */
@Service
@RequiredArgsConstructor
public class LoginLogService {

    private final SysLoginLogMapper loginLogMapper;

    public void record(Long userId, String username, String clientIp, String userAgent,
            boolean success, String msg) {
        SysLoginLogEntity e = new SysLoginLogEntity();
        e.setUserId(userId);
        e.setUsername(username);
        e.setClientIp(clientIp);
        e.setUserAgent(userAgent);
        e.setResult(success ? 1 : 0);
        e.setMsg(msg);
        loginLogMapper.insert(e);
    }

    /** 分页查询（登录日志页, docs/03 §2 login-logs） */
    public IPage<SysLoginLogEntity> page(int page, int count, String username, Integer result) {
        LambdaQueryWrapper<SysLoginLogEntity> w = new LambdaQueryWrapper<SysLoginLogEntity>()
                .like(StringUtils.hasText(username), SysLoginLogEntity::getUsername, username)
                .eq(result != null, SysLoginLogEntity::getResult, result)
                .orderByDesc(SysLoginLogEntity::getCreateTime);
        return loginLogMapper.selectPage(new Page<>(page, count), w);
    }
}
