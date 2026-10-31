package com.yujian.vision.service.media.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 回放 control 入参与窗口校验（docs/03 §6 / docs/07 §3.1）：纯静态逻辑, 不起 Spring */
class PlaybackControlValidationTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 9, 9, 10, 0, 0);

    @Test
    @DisplayName("窗口: start/end 倒置 → 400")
    void windowReversed() {
        BizException e = assertThrows(BizException.class,
                () -> MediaSessionService.validateWindow(BASE, BASE.minusHours(1)));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
    }

    @Test
    @DisplayName("窗口: 空值 → 400")
    void windowNull() {
        assertEquals(ErrorCode.BAD_REQUEST,
                assertThrows(BizException.class, () -> MediaSessionService.validateWindow(null, BASE)).getErrorCode());
        assertEquals(ErrorCode.BAD_REQUEST,
                assertThrows(BizException.class, () -> MediaSessionService.validateWindow(BASE, null)).getErrorCode());
    }

    @Test
    @DisplayName("窗口: 恰好 31 天合法, 超过 31 天 400")
    void windowSpanLimit() {
        assertDoesNotThrow(() -> MediaSessionService.validateWindow(BASE, BASE.plusDays(31)));
        // toDays() 向下取整, 与 RecordQueryService 同口径: 31 天整可过, 32 天起拒绝
        BizException e = assertThrows(BizException.class,
                () -> MediaSessionService.validateWindow(BASE, BASE.plusDays(32)));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
    }

    @Test
    @DisplayName("cmd: PAUSE/RESUME/SCALE/SEEK 合法, 未知/空 → 400")
    void cmdWhitelist() {
        assertDoesNotThrow(() -> MediaSessionService.validateControl("PAUSE", null, null));
        assertDoesNotThrow(() -> MediaSessionService.validateControl("RESUME", null, null));
        assertDoesNotThrow(() -> MediaSessionService.validateControl("SCALE", new BigDecimal("2"), null));
        assertDoesNotThrow(() -> MediaSessionService.validateControl("SEEK", null, BASE));
        assertEquals(ErrorCode.BAD_REQUEST,
                assertThrows(BizException.class, () -> MediaSessionService.validateControl("STOP", null, null)).getErrorCode());
        assertEquals(ErrorCode.BAD_REQUEST,
                assertThrows(BizException.class, () -> MediaSessionService.validateControl(null, null, null)).getErrorCode());
    }

    @Test
    @DisplayName("SCALE: 档位 0.25/0.5/1/2/4 合法(含 1.0 等价写法), 非法档位/缺参 → 400")
    void scaleLevels() {
        for (String v : new String[]{"0.25", "0.5", "1", "2", "4", "1.0", "2.00"}) {
            assertDoesNotThrow(() -> MediaSessionService.validateControl("SCALE", new BigDecimal(v), null));
        }
        for (String v : new String[]{"0.1", "3", "8"}) {
            assertEquals(ErrorCode.BAD_REQUEST, assertThrows(BizException.class,
                    () -> MediaSessionService.validateControl("SCALE", new BigDecimal(v), null)).getErrorCode());
        }
        assertEquals(ErrorCode.BAD_REQUEST, assertThrows(BizException.class,
                () -> MediaSessionService.validateControl("SCALE", null, null)).getErrorCode());
    }

    @Test
    @DisplayName("SEEK: 缺 seekTime → 400")
    void seekRequiresTime() {
        assertEquals(ErrorCode.BAD_REQUEST, assertThrows(BizException.class,
                () -> MediaSessionService.validateControl("SEEK", null, null)).getErrorCode());
    }
}
