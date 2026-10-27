package com.flowdesk.notification.service;

import com.flowdesk.common.tenant.TenantContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.UUID;

/** 通知仓储共用的确定性标识转换及租户、事务断言，约束所有持久化操作。 */
public final class NotificationPersistenceSupport {
    /**
     * 禁止直接实例化工具容器，当前类型通过静态方法提供共享规则和转换。
     */
    private NotificationPersistenceSupport() { }

    /**
     * 核验目标租户与当前上下文一致，防止持久化操作跨出租户边界。
     */
    public static void requireTenant(UUID tenantId) {
        TenantContextHolder.current().requireTenant(tenantId);
    }

    /**
     * 确认当前线程有活动数据库事务，防止需要原子性的写入在事务外执行。
     */
    public static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Notification mutation requires a transaction");
        }
    }

    /**
     * 在 UUID 与持久化表示之间转换，保持标识高低位的编码顺序一致。
     */
    public static UUID uuid(byte[] value) {
        if (value == null) return null;
        ByteBuffer b = ByteBuffer.wrap(value); return new UUID(b.getLong(), b.getLong());
    }

    /**
     * 从稳定键字节生成 UUID，使同一业务身份在重复处理中得到相同标识。
     */
    public static UUID uuidFromKey(byte[] value) {
        if (value == null || value.length < 16) {
            throw new IllegalArgumentException("Derived identity key is too short");
        }
        return uuid(Arrays.copyOf(value, 16));
    }

}
