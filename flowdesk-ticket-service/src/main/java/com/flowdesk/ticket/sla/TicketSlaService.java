package com.flowdesk.ticket.sla;

import com.flowdesk.ticket.model.sla.*;
import com.flowdesk.ticket.model.*;
import com.flowdesk.common.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;

/** 业务用例契约，调用者依赖接口。 */
public interface TicketSlaService {
    /**
     * 事务内重新核验 SLA 候选，记录触发事实并处理后续通知。
     */
    SlaProcessResult processSlaCandidate(UUID tenantId, UUID ticketId, UUID slaClockId,
                                                String stage, Instant suppliedNow);

    /**
     * 沿既有触发事实补发通知，避免重复创建提醒或超时效果。
     */
    void retrySlaNotification(UUID tenantId, UUID ticketId, UUID clockId, String stage, Instant now);

    /**
     * 业务达成响应或解决目标时结算 SLA 时钟，记录按时或逾期达成。
     */
    void satisfyClock(TenantContext context, UUID ticketId, String clockType, Instant now);

    /**
     * 按策略快照建立工单 SLA 时钟，后续配置不追溯改变已建目标。
     */
    void insertClocks(TenantContext context, UUID ticketId, UUID slaPolicyId, Instant now);

    /**
     * 为重开工单建立新解决周期时钟，保留既有周期事实。
     */
    void reopenResolutionClock(TenantContext context, UUID ticketId, int cycleNo, Instant now);
}
