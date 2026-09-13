package com.flowdesk.common.tenant;

import java.util.UUID;

/**
 * 当前可信租户与要求不一致时抛出。
 *
 * <p>知道其他租户 UUID 不等于有权访问；跨对象引用必须与当前 TenantContext 一致。</p>
 */
public class TenantContextMismatchException extends IllegalStateException {

    public TenantContextMismatchException(UUID expected, UUID actual) {
        super("TenantContext tenant mismatch: expected " + expected + " but current context is " + actual);
    }
}
