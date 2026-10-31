package com.yujian.vision.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.api.R;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.service.device.service.ChannelDataScopeService;
import com.yujian.vision.service.media.config.SnapshotProperties;
import com.yujian.vision.service.media.entity.SnapshotVo;
import com.yujian.vision.service.media.service.SnapshotService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * ChannelSnapshotController 单测（M3-B7 阶段2）: 直接实例化, 不起 Spring 上下文
 * （形制同 HaInternalControllerTest——BizException 原样上抛, 用 catchThrowableOfType 断言错误码）。
 */
class ChannelSnapshotControllerTest {

    private static final long CH_ID = 5L;
    private static final Long UID = 42L;

    private SnapshotService snapshots;
    private ChannelDataScopeService dataScope;
    private SnapshotProperties props;
    private ChannelSnapshotController controller;

    @BeforeEach
    void setUp() {
        snapshots = mock(SnapshotService.class);
        dataScope = mock(ChannelDataScopeService.class);
        props = new SnapshotProperties();
        controller = new ChannelSnapshotController(snapshots, dataScope, props);
    }

    private static SnapshotVo vo() {
        return new SnapshotVo("/api/channels/" + CH_ID + "/snapshot?size=full&sign=s&expire=9999999999",
                "/api/channels/" + CH_ID + "/snapshot?size=thumb&sign=s&expire=9999999999",
                LocalDateTime.of(2026, 9, 16, 10, 0), "MANUAL");
    }

    @Test
    @DisplayName("开关查询: 出参 enabled/idleEnabled 来自配置")
    void snapshotConfigReturnsFlags() {
        props.setEnabled(true);
        props.setIdleEnabled(false);

        R<java.util.Map<String, Object>> r = controller.snapshotConfig();

        assertThat(r.getCode()).isEqualTo(0);
        assertThat(r.getData().get("enabled")).isEqualTo(true);
        assertThat(r.getData().get("idleEnabled")).isEqualTo(false);
    }

    @Test
    @DisplayName("手动抓图: 先过通道数据权限, 再抓图并 R.ok 包络")
    void captureChecksScopeThenCaptures() {
        when(snapshots.captureManual(CH_ID)).thenReturn(vo());

        R<SnapshotVo> r = controller.capture(CH_ID,
                new com.yujian.vision.web.security.LoginUser(UID, "admin", "Admin",
                        java.util.Set.of(), java.util.Set.of(), false, "tok"));

        assertThat(r.getCode()).isEqualTo(0);
        assertThat(r.getData().url()).contains("size=full");
        verify(dataScope).checkAccess(CH_ID, UID);
        verify(snapshots).captureManual(CH_ID);
    }

    @Test
    @DisplayName("图片读取: 验签不过 → 401 且不读文件")
    void imageRejectsBadSign() {
        when(snapshots.verifyUrl(CH_ID, "full", "9999999999", "bad")).thenReturn(false);

        BizException e = catchThrowableOfType(BizException.class,
                () -> controller.image(CH_ID, "full", "bad", "9999999999"));

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
        verify(snapshots, never()).readForServe(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("图片读取: 验签通过 → 200 image/jpeg 原始字节")
    void imageServesJpegOnValidSign() {
        when(snapshots.verifyUrl(CH_ID, "thumb", "9999999999", "good")).thenReturn(true);
        when(snapshots.readForServe(CH_ID, true))
                .thenReturn(new SnapshotService.ServedImage(new byte[]{1, 2, 3}, LocalDateTime.now()));

        ResponseEntity<byte[]> resp = controller.image(CH_ID, "thumb", "good", "9999999999");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getHeaders().getContentType())
                .isEqualTo(org.springframework.http.MediaType.IMAGE_JPEG);
        assertThat(resp.getBody()).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("图片读取: 行缺失(NOT_FOUND 404)原样上抛交全局异常处理器出包络")
    void imagePropagatesNotFound() {
        when(snapshots.verifyUrl(CH_ID, "full", "9999999999", "good")).thenReturn(true);
        when(snapshots.readForServe(CH_ID, false))
                .thenThrow(new BizException(ErrorCode.NOT_FOUND, "截图不存在"));

        BizException e = catchThrowableOfType(BizException.class,
                () -> controller.image(CH_ID, "full", "good", "9999999999"));

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(e.getErrorCode().getCode()).isEqualTo(404);
    }
}
