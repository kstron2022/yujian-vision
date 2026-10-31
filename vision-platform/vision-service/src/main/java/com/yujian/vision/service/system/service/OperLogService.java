package com.yujian.vision.service.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yujian.vision.service.system.entity.SysOperLogEntity;
import com.yujian.vision.service.system.mapper.SysOperLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 操作日志落库/查询（M1-9 A3）
 */
@Service
@RequiredArgsConstructor
public class OperLogService {

    private final SysOperLogMapper operLogMapper;

    public void record(Long userId, String username, String module, String operation,
            String bizType, String bizId, String detail, String clientIp, int costMs, boolean success) {
        SysOperLogEntity e = new SysOperLogEntity();
        e.setUserId(userId);
        e.setUsername(username);
        e.setModule(module);
        e.setOperation(operation);
        e.setBizType(bizType);
        e.setBizId(bizId);
        e.setDetail(detail);
        e.setClientIp(clientIp);
        e.setCostMs(costMs);
        e.setResult(success ? 1 : 0);
        operLogMapper.insert(e);
    }

    /** 分页查询（操作日志页; orderBy 白名单: 默认 create_time, 可按 cost_ms） */
    public IPage<SysOperLogEntity> page(int page, int count, String module, String username,
            String bizType, String bizId, Integer result) {
        return operLogMapper.selectPage(new Page<>(page, count),
                new LambdaQueryWrapper<SysOperLogEntity>()
                        .eq(StringUtils.hasText(module), SysOperLogEntity::getModule, module)
                        .like(StringUtils.hasText(username), SysOperLogEntity::getUsername, username)
                        .eq(StringUtils.hasText(bizType), SysOperLogEntity::getBizType, bizType)
                        .eq(StringUtils.hasText(bizId), SysOperLogEntity::getBizId, bizId)
                        .eq(result != null, SysOperLogEntity::getResult, result)
                        .orderByDesc(SysOperLogEntity::getCreateTime));
    }
}
