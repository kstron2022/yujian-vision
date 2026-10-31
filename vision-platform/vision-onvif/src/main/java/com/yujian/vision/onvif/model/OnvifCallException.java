package com.yujian.vision.onvif.model;

/** ONVIF 调用失败（网络/HTTP/Fault），message 面向运维可直接展示 */
public class OnvifCallException extends RuntimeException {

    public OnvifCallException(String message) {
        super(message);
    }

    public OnvifCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
