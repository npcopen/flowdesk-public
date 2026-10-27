package com.flowdesk.ticket.persistence;

import com.flowdesk.common.tenant.TenantContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** SLA 业务实现与后台任务共享的时间参数转换及租户、事务断言。 */
public final class SlaPersistenceSupport {
    /**
     * 私有构造方法，阻止工具类实例化，调用方应使用静态入口。
     */
    private SlaPersistenceSupport() { }

    /** 所有租户 SLA 语句都必须在匹配的上下文内执行。 */
    public static void requireTenant(UUID tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        TenantContextHolder.current().requireTenant(tenantId);
    }

    /**
     * 要求调用处于数据库事务中，保证关联事实可以原子提交或回滚。
     */
    public static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Ticket mutation requires an active transaction");
        }
    }

    /**
     * 解析时间文本或转换数据库时间值，遵循空值约定并保留业务精度。
     */
    public static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

}
