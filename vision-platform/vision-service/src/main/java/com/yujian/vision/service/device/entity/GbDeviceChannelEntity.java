package com.yujian.vision.service.device.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yujian.vision.service.media.entity.SnapshotVo;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * gb_device_channel 通道实体（docs/02 §2.2）
 */
@Data
@TableName("gb_device_channel")
public class GbDeviceChannelEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long deviceDbId;

    private String channelId;

    private String name;

    private String customName;

    private String manufacturer;

    private String model;

    private String owner;

    private String civilCode;

    private String address;

    /** 1:有子节点(虚拟分组) */
    private Integer parental;

    private String parentId;

    private String businessGroupId;

    /** 通道状态 1:ON 0:OFF */
    private Integer status;

    private Integer registerWay;

    private Integer secrecy;

    private String ip;

    private Integer port;

    private BigDecimal longitude;

    private BigDecimal latitude;

    /** 0:无PTZ 1:球机 2:半球 3:固定枪机 4:遥控 5:不支持 */
    private Integer ptzType;

    /** 云台开关: NULL=按 ptzType 自动 1=强制开启 0=禁用（V12, docs/09 M1-8 挂账） */
    private Integer ptzEnabled;

    /** 1:移动设备 */
    private Integer mobile;

    /** ONVIF VideoSource token（通道身份键, 目录收敛按它匹配; M4-B1） */
    private String onvifSourceToken;

    /** ONVIF 主码流 Profile token（M4-B1）; GB 通道空串 */
    private String onvifProfileToken;

    /** ONVIF Profiles 缓存 JSON（token/分辨率/编码, 主/子档位按分辨率降序; docs/13 §4） */
    private String onvifProfiles;

    private Integer subCount;

    private LocalDateTime catalogTime;

    private LocalDateTime deletedTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 最新截图（M3-B7 阶段2）: ChannelQueryService.page 列表页批量填充, 不落库列; 无图=null */
    @TableField(exist = false)
    private SnapshotVo snapshot;

    /**
     * 目录节点（虚拟分组, 如下级平台的行政区划/平台根）: 不落库列, 查询侧填充。
     * 判定不信对端上报的 parental——部分平台(如 LiveGBS)对目录节点报 parental=0 却挂
     * children（M5-B2 联调缺陷）, 以「是其它存活通道的 parentId」这一结构事实为准。
     * 目录节点不可点播/不可抓图（doPlay 有同口径守卫）, 前端按此隐藏播放入口。
     */
    @TableField(exist = false)
    private Boolean directory;
}
