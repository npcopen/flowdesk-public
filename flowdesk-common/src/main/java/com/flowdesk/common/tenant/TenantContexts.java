package com.flowdesk.common.tenant;

import java.util.Objects;
import java.util.UUID;

/**
 * 可信租户一致性校验工具（ARCHITECTURE §7.1.5）。
 *
 * <p>跨对象引用必须与当前 TenantContext 一致：知道资源标识或能猜出其他租户 UUID
 * 都不能替代资源授权。所有资源查询/更新必须以当前可信租户为首个数据库条件。</p>
 */
public final class TenantContexts {

    private TenantContexts() {
    }

    /**
     * 校验给定上下文属于期望租户。
     *
     * @param context  非空可信上下文
     * @param tenantId 期望租户
     * @throws TenantContextMismatchException 不一致时抛出
     */
    public static void requireSameTenant(TenantContext context, UUID tenantId) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(tenantId, "tenantId");
        if (!tenantId.equals(context.tenantId())) {
            throw new TenantContextMismatchException(tenantId, context.tenantId());
        }
    }

    /**
     * 校验当前作用域的可信上下文属于期望租户。
     *
     * @param tenantId 期望租户
     * @throws NoTenantContextException        当前作用域没有可信上下文
     * @throws TenantContextMismatchException 不一致时抛出
     */
    public static void requireCurrentTenant(UUID tenantId) {
        requireSameTenant(TenantContextHolder.current(), tenantId);
    }
}
