package com.yujian.vision.service.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yujian.vision.service.system.entity.SysNoticeEntity;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SysNoticeMapper extends BaseMapper<SysNoticeEntity> {

    /** 用户消息分页（键下划线, NoticeService 转 camelCase; unread=null 全部, true 仅未读, false 仅已读） */
    String PAGE_SQL = "<script>"
            + "SELECT n.id, n.type, n.title, n.content, n.level, n.biz_id, n.create_time, m.read_time "
            + "FROM sys_notice n "
            + "JOIN sys_user_message m ON m.notice_id = n.id AND m.user_id = #{userId} "
            + "WHERE n.deleted_time IS NULL "
            + "<if test='unread != null'>AND m.read_time IS "
            + "<choose><when test='unread'>NULL</when><otherwise>NOT NULL</otherwise></choose></if> "
            + "ORDER BY n.id DESC LIMIT #{offset}, #{limit}"
            + "</script>";

    /** 用户消息计数（条件与 PAGE_SQL 对齐） */
    String COUNT_SQL = "<script>"
            + "SELECT COUNT(*) FROM sys_notice n "
            + "JOIN sys_user_message m ON m.notice_id = n.id AND m.user_id = #{userId} "
            + "WHERE n.deleted_time IS NULL "
            + "<if test='unread != null'>AND m.read_time IS "
            + "<choose><when test='unread'>NULL</when><otherwise>NOT NULL</otherwise></choose></if>"
            + "</script>";

    @Select(PAGE_SQL)
    List<Map<String, Object>> pageByUser(@Param("userId") Long userId, @Param("unread") Boolean unread,
            @Param("offset") int offset, @Param("limit") int limit);

    @Select(COUNT_SQL)
    long countByUser(@Param("userId") Long userId, @Param("unread") Boolean unread);

    /** 未读数（未删通知口径, 轮询/红点用） */
    @Select("SELECT COUNT(*) FROM sys_notice n "
            + "JOIN sys_user_message m ON m.notice_id = n.id AND m.user_id = #{userId} "
            + "WHERE n.deleted_time IS NULL AND m.read_time IS NULL")
    long countUnreadByUser(@Param("userId") Long userId);
}
