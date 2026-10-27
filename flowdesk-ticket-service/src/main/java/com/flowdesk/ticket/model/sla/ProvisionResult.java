package com.flowdesk.ticket.model.sla;

import java.util.UUID;

/**
 * 升级目标配置结果，反映目标与资格快照写入情况。
 */
public record ProvisionResult(UUID tenantId, UUID targetId, boolean changed,
                              boolean replayed, String code) { }
