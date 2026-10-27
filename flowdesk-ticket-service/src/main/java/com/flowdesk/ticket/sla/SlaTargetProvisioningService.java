package com.flowdesk.ticket.sla;

import com.flowdesk.ticket.model.sla.*;
import com.flowdesk.ticket.model.*;
import java.time.Instant;
import java.util.UUID;

/** 业务用例契约，调用者依赖接口。 */
public interface SlaTargetProvisioningService {
    /**
     * 校验SLA创建请求并保存事实，按接口约定处理幂等及版本条件。
     */
    ProvisionResult create(UUID tenantId, UUID targetId, UUID recipientUserId,
                                  Instant effectiveAt);

    /**
     * 校验SLA创建请求并保存事实，按接口约定处理幂等及版本条件。
     */
    ProvisionResult create(UUID tenantId, UUID targetId, UUID recipientUserId,
                                  Instant effectiveAt, UUID actorId);

    /**
     * 核验目标资格后，在事务中替换升级接收目标并保存审计。
     */
    ProvisionResult replace(UUID tenantId, UUID newTargetId, UUID recipientUserId,
                                   Instant effectiveAt);

    /**
     * 核验目标资格后，在事务中替换升级接收目标并保存审计。
     */
    ProvisionResult replace(UUID tenantId, UUID oldTargetId, UUID newTargetId,
                                   UUID recipientUserId, Instant effectiveAt, UUID actorId);

    /**
     * 核验目标资格后，在事务中替换升级接收目标并保存审计。
     */
    ProvisionResult replace(UUID tenantId, UUID oldTargetId, UUID newTargetId,
                                   UUID recipientUserId, Instant effectiveAt);

    /**
     * 停用当前升级目标，并记录原因与操作审计。
     */
    ProvisionResult deactivate(UUID tenantId, UUID targetId);

    /**
     * 停用当前升级目标，并记录原因与操作审计。
     */
    ProvisionResult deactivate(UUID tenantId, UUID targetId, UUID actorId);
}
