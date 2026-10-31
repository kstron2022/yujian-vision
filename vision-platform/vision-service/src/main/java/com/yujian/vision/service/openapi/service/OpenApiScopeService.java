package com.yujian.vision.service.openapi.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.service.device.entity.GbGroupChannelRefEntity;
import com.yujian.vision.service.device.mapper.GbGroupChannelRefMapper;
import com.yujian.vision.service.device.service.ChannelDataScopeService;
import com.yujian.vision.service.device.service.GroupManageService;
import com.yujian.vision.service.openapi.domain.OpenAppAuth;
import java.util.HashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 开放接口数据范围（docs/15 步骤4 越权防线）：把 open_app.channel_scope（分组 id 集）
 * 解析为通道级 ScopeFilter——分组子树经 expandWithDescendants 展开,
 * 经 gb_group_channel_ref 得通道 id 集, 形制对齐 ChannelDataScopeService 的角色-分组路径。
 * scope 空 = 全部（管理页建卡口径）。全部 8 个开放接口凡涉通道必须经此过滤/校验。
 * 仅 vision.openapi.enabled=true 时装配。
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "vision.openapi.enabled", havingValue = "true")
public class OpenApiScopeService {

    private final GroupManageService groupService;
    private final GbGroupChannelRefMapper refMapper;

    /** 解析应用的可见通道集（scope 空 = ALL; scope 内分组无任何通道 = 全禁空集） */
    public ChannelDataScopeService.ScopeFilter resolve(OpenAppAuth app) {
        if (app.getChannelScope().isEmpty()) {
            return ChannelDataScopeService.ScopeFilter.ALL();
        }
        Set<Long> expanded = groupService.expandWithDescendants(app.getChannelScope());
        if (expanded.isEmpty()) {
            return new ChannelDataScopeService.ScopeFilter(false, Set.of());
        }
        Set<Long> channelIds = new HashSet<>();
        for (GbGroupChannelRefEntity ref : refMapper.selectList(
                new LambdaQueryWrapper<GbGroupChannelRefEntity>()
                        .in(GbGroupChannelRefEntity::getGroupId, expanded))) {
            channelIds.add(ref.getChannelDbId());
        }
        return new ChannelDataScopeService.ScopeFilter(false, channelIds);
    }

    /** 单通道校验（点播/云台/录像等写路径; 越权 403） */
    public void checkAccess(OpenAppAuth app, Long channelDbId) {
        ChannelDataScopeService.ScopeFilter f = resolve(app);
        if (!f.all() && !f.ids().contains(channelDbId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无该通道的数据权限");
        }
    }
}
