package com.flowdesk.ticket.notification;

import com.flowdesk.ticket.model.notification.*;
import com.flowdesk.ticket.persistence.Uuids;
import com.flowdesk.common.notification.NotificationEventContract;
import com.flowdesk.common.notification.NotificationWireCodec;
import com.flowdesk.common.tenant.TenantContextHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 不可变业务身份的确定性生成规则。 */
public final class TicketNotificationIdentity {
    /**
     * 私有构造方法，阻止工具类实例化，调用方应使用静态入口。
     */
    private TicketNotificationIdentity() { }

    /**
     * 从来源消息或发件箱身份派生恢复标识，支持恢复项幂等建立。
     */
    public static UUID recoveryId(Claimed row) {
        Objects.requireNonNull(row, "row");
        return NotificationWireCodec.uuidFromKey(NotificationWireCodec.deriveKey(
                "FD-NOTIFICATION-RECOVERY-V1", row.tenantId(), row.outboxId(), "OUTBOX"));
    }
}
