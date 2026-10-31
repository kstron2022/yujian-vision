package com.yujian.vision.service.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * sys_menu 菜单与按钮权限实体（决策 D11: 菜单-按钮一体化）
 */
@Data
@TableName("sys_menu")
public class SysMenuEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 父菜单id, 0=根 */
    private Long parentId;
    private String menuName;
    /** 1:目录 2:菜单 3:按钮 */
    private Integer menuType;
    private String path;
    private String component;
    /** 权限标识, 如 device:play / ptz:control, 空串=非权限点 */
    private String permCode;
    private String icon;
    private Integer sortOrder;
    /** 1显示 0隐藏 */
    private Integer visible;
    /** 1启用 0停用 */
    private Integer status;
    private LocalDateTime deletedTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
