package com.flowdesk.common.web;

import java.io.IOException;

/**
 * 读取请求体时超过 API 约定大小上限所抛出的异常。
 */
public class RequestBodyTooLargeException extends IOException {

    private final long limitBytes;

    /**
     * 记录触发错误的字节上限，并生成供内部诊断使用的超限消息。
     */
    public RequestBodyTooLargeException(long limitBytes) {
        super("Request body exceeds " + limitBytes + " bytes");
        this.limitBytes = limitBytes;
    }

    /**
     * 返回触发异常的字节上限，供内部诊断请求体限制。
     */
    public long limitBytes() {
        return limitBytes;
    }
}
