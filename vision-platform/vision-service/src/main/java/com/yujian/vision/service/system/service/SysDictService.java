package com.yujian.vision.service.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.service.system.entity.SysDictDataEntity;
import com.yujian.vision.service.system.entity.SysDictTypeEntity;
import com.yujian.vision.service.system.mapper.SysDictDataMapper;
import com.yujian.vision.service.system.mapper.SysDictTypeMapper;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 公共字典查询（决策 D16: 展示/分类类枚举统一来源）
 */
@Service
@RequiredArgsConstructor
public class SysDictService {

    private final SysDictTypeMapper typeMapper;
    private final SysDictDataMapper dataMapper;

    public List<SysDictDataEntity> data(String dictType) {
        List<SysDictTypeEntity> types = typeMapper.selectList(
                new LambdaQueryWrapper<SysDictTypeEntity>().eq(SysDictTypeEntity::getDictType, dictType));
        if (types.isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "字典类型不存在: " + dictType);
        }
        return dataMapper.selectList(new LambdaQueryWrapper<SysDictDataEntity>()
                .eq(SysDictDataEntity::getDictType, dictType)
                .eq(SysDictDataEntity::getStatus, 1)
                .orderByAsc(SysDictDataEntity::getSortOrder));
    }

    public Map<String, List<SysDictDataEntity>> all() {
        return dataMapper.selectList(new LambdaQueryWrapper<SysDictDataEntity>()
                        .eq(SysDictDataEntity::getStatus, 1)
                        .orderByAsc(SysDictDataEntity::getSortOrder))
                .stream().collect(java.util.stream.Collectors.groupingBy(
                        SysDictDataEntity::getDictType,
                        java.util.LinkedHashMap::new,
                        java.util.stream.Collectors.toList()));
    }
}
