package com.yujian.vision.service.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * sys_dict_data 字典数据实体（决策 D16）
 */
@Data
@TableName("sys_dict_data")
public class SysDictDataEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String dictType;
    private String dictValue;
    private String dictLabel;
    private String extra;
    private Integer sortOrder;
    private Integer status;
}
