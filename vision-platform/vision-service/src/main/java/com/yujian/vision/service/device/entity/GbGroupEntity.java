package com.yujian.vision.service.device.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * gb_group 自定义分组（决策 D13: 前端主树, 国标 Catalog 为原始视图；docs/09 M2 #5）
 * tenant_id 由租户插件 SQL 层统一注入, 实体不落字段（决策 D12）
 */
@Data
@TableName("gb_group")
public class GbGroupEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 父节点, 0=根 */
    private Long parentId;

    /** 祖级路径(如 0,1,5), 便于子树查询 */
    private String ancestors;

    private String groupName;

    private Integer sortOrder;

    /** 软删(NULL=有效) */
    private LocalDateTime deletedTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
