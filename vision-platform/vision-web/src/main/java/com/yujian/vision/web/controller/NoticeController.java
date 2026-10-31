package com.yujian.vision.web.controller;

import com.yujian.vision.common.api.R;
import com.yujian.vision.service.system.service.NoticeService;
import com.yujian.vision.web.security.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站内消息中心（docs/03 §2.2, B1-c）：个人消息查询/已读, 与 WS NOTICE_NEW 双通道。
 * 不设 @PreAuthorize —— 登录即可用（个人数据, 无权限点）。
 */
@Tag(name = "消息中心")
@RestController
@RequestMapping("/api/notices")
@RequiredArgsConstructor
public class NoticeController {

    private final NoticeService noticeService;

    @Operation(summary = "消息列表（unread=true 仅未读/false 仅已读/不传全部, count 默认 10 上限 100）")
    @GetMapping
    public R<Map<String, Object>> list(@RequestParam(required = false) String unread,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int count,
            @AuthenticationPrincipal LoginUser user) {
        return R.ok(noticeService.page(userId(user), parseUnread(unread), page, count));
    }

    @Operation(summary = "未读数")
    @GetMapping("/unread-count")
    public R<Long> unreadCount(@AuthenticationPrincipal LoginUser user) {
        return R.ok(noticeService.unreadCount(userId(user)));
    }

    @Operation(summary = "标记已读（幂等）")
    @PostMapping("/{id}/read")
    public R<Void> markRead(@PathVariable Long id, @AuthenticationPrincipal LoginUser user) {
        noticeService.markRead(userId(user), id);
        return R.ok();
    }

    @Operation(summary = "全部已读（返回更新条数）")
    @PostMapping("/read-all")
    public R<Long> readAll(@AuthenticationPrincipal LoginUser user) {
        return R.ok(noticeService.markAllRead(userId(user)));
    }

    /** principal 缺失（异常链路直连）时按匿名处理 */
    private static Long userId(LoginUser user) {
        return user == null ? null : user.userId();
    }

    /** unread 入参: true/false 之外一律按全部处理 */
    private static Boolean parseUnread(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return "true".equalsIgnoreCase(raw.trim()) ? Boolean.TRUE : Boolean.FALSE;
    }
}