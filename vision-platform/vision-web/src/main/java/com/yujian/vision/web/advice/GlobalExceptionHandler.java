package com.yujian.vision.web.advice;

import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.api.R;
import com.yujian.vision.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：业务异常/参数校验异常/未知异常统一转为响应包络（docs/03 §1.1）。
 * AccessDeniedException 单列（@PreAuthorize 拒绝），避免落入兜底 500。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public R<Void> handleBizException(BizException e) {
        log.warn("业务异常: code={}, msg={}", e.getErrorCode().getCode(), e.getMessage());
        return R.fail(e.getErrorCode(), e.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public R<Void> handleAccessDenied(AccessDeniedException e) {
        log.warn("接口权限拒绝: {}", e.getMessage());
        return R.fail(ErrorCode.FORBIDDEN);
    }

    @ExceptionHandler(AuthenticationException.class)
    public R<Void> handleAuthentication(AuthenticationException e) {
        log.warn("接口认证失败: {}", e.getMessage());
        return R.fail(ErrorCode.UNAUTHORIZED);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<Void> handleValidation(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String msg = fieldError == null ? ErrorCode.BAD_REQUEST.getMsg()
                : fieldError.getField() + " " + fieldError.getDefaultMessage();
        return R.fail(ErrorCode.BAD_REQUEST, msg);
    }

    /** 客户端请求体损坏/编码错误(如非 UTF-8 JSON)属于 4xx, 不能落兜底 500 */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public R<Void> handleMessageNotReadable(
            org.springframework.http.converter.HttpMessageNotReadableException e) {
        log.warn("请求体不可读: {}", e.getMessage());
        return R.fail(ErrorCode.BAD_REQUEST, "请求体格式错误");
    }

    /** 查询参数类型不匹配(如 status=true 传给 Integer)属于 4xx, 不能落兜底 500 */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public R<Void> handleTypeMismatch(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException e) {
        log.warn("查询参数类型错误: name={}, value={}", e.getName(), e.getValue());
        return R.fail(ErrorCode.BAD_REQUEST, "参数类型错误: " + e.getName());
    }

    /** 必填查询参数缺失(如截图图片端点缺 sign/expire)属于 4xx, 不能落兜底 500 */
    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public R<Void> handleMissingParam(org.springframework.web.bind.MissingServletRequestParameterException e) {
        log.warn("必填参数缺失: name={}", e.getParameterName());
        return R.fail(ErrorCode.BAD_REQUEST, "缺少必填参数: " + e.getParameterName());
    }

    /** 静态资源或路径不存在：内嵌前端下缺失的 js/css、未知接口统一 404，不落兜底 500 */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NOT_FOUND)
    public R<Void> handleNoResource(
            org.springframework.web.servlet.resource.NoResourceFoundException e) {
        return R.fail(ErrorCode.NOT_FOUND);
    }

    @ExceptionHandler(Exception.class)
    public R<Void> handleUnknown(Exception e) {
        log.error("系统异常", e);
        return R.fail(ErrorCode.SYSTEM_ERROR);
    }
}
