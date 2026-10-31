package com.yujian.vision.web.openapi;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * body 可复读包装（docs/15 步骤3）：验签需要先读 rawBody 计算 SM3 摘要,
 * 而 Spring 自带 ContentCachingRequestWrapper 只缓存"已被消费"的字节（未读时为空）,
 * 无法先取 body 再放行 —— 故构造时一次性读入, getInputStream/getReader 每次基于缓存重新提供。
 * GET/无 body 请求 body 为空数组（签名摘要口径 = SM3(空)）。
 */
public class RepeatableBodyRequestWrapper extends HttpServletRequestWrapper {

    private final byte[] body;

    public RepeatableBodyRequestWrapper(HttpServletRequest request) throws IOException {
        super(request);
        this.body = request.getInputStream() == null ? new byte[0] : request.getInputStream().readAllBytes();
    }

    /** 原始请求体（验签摘要输入） */
    public byte[] body() {
        return body;
    }

    @Override
    public int getContentLength() {
        return body.length;
    }

    @Override
    public long getContentLengthLong() {
        return body.length;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream buffer = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public int read() {
                return buffer.read();
            }

            @Override
            public boolean isFinished() {
                return buffer.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
}
