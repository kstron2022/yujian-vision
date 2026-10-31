package com.yujian.vision.service.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * sys_dict_type 字典类型实体（决策 D16）
 */
@Data
@TableName("sys_dict_type")
public class SysDictTypeEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String dictType;
    private String dictName;
    /** 1系统内置(不可删除) */
    private Integer builtin;
    private String remark;
    private LocalDateTime createTime;
}
