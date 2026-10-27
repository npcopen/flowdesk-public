package com.flowdesk.ticket.model;

import java.util.UUID;
import java.time.Instant;

/**
 * 服务目录的不可变持久化投影，保留状态、风险、规则绑定与乐观锁版本。
 */
public record CatalogItemRow(UUID catalogItemId, String itemCode, String itemName, String description, String riskLevel, boolean requiresApproval, UUID workflowDefinitionId, UUID workflowVersionId, Integer workflowVersionNo, UUID slaPolicyId, String status, long lockVersion, Instant createdAt, Instant updatedAt) {
}
