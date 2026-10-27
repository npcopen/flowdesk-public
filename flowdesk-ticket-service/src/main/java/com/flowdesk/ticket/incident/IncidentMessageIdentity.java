package com.flowdesk.ticket.incident;

import com.flowdesk.ticket.model.incident.*;
import com.flowdesk.ticket.persistence.Uuids;
import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.ticket.service.IdempotencyCodec;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 不可变业务身份的确定性生成规则。 */
public final class IncidentMessageIdentity {
    /**
     * 私有构造方法，阻止工具类实例化，调用方应使用静态入口。
     */
    private IncidentMessageIdentity() { }

    /**
     * 按业务来源、时钟类型与周期派生稳定 SLA 时钟标识。
     */
    public static UUID slaClockId(UUID tenantId, UUID ticketId, String clockType) {
        if (tenantId == null || ticketId == null
                || !("FIRST_RESPONSE".equals(clockType) || "RESOLUTION".equals(clockType))) {
            throw new IllegalArgumentException("Incident SLA clock identity is invalid");
        }
        return IdempotencyCodec.uuidFromDigest(IdempotencyCodec.derivedKey("FD-INCIDENT-SLA-CLOCK-V1", tenantId, ticketId, clockType));
    }
}
