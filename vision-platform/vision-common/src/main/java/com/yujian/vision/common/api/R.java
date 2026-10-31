package com.yujian.vision.common.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一响应包络（docs/03 §1.1）
 *
 * <pre>{ "code": 0, "msg": "success", "data": { } }</pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class R<T> {

    private int code;
    private String msg;
    private T data;

    public static <T> R<T> ok() {
        return new R<>(ErrorCode.OK.getCode(), ErrorCode.OK.getMsg(), null);
    }

    public static <T> R<T> ok(T data) {
        return new R<>(ErrorCode.OK.getCode(), ErrorCode.OK.getMsg(), data);
    }

    public static <T> R<T> fail(ErrorCode errorCode) {
        return new R<>(errorCode.getCode(), errorCode.getMsg(), null);
    }

    public static <T> R<T> fail(ErrorCode errorCode, String msg) {
        return new R<>(errorCode.getCode(), msg, null);
    }
}
