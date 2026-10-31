package com.yujian.vision.common.exception;

import com.yujian.vision.common.api.ErrorCode;
import lombok.Getter;

/**
 * 业务异常：携带全局错误码，由 vision-web 全局异常处理器统一转为响应包络
 */
@Getter
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMsg());
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }
}
