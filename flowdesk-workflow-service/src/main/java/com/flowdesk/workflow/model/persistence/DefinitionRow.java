package com.flowdesk.workflow.model.persistence;

import java.time.Instant;
import java.util.UUID;

/**
 * 工作流持久化行快照，保存数据库查询得到的业务字段，供服务校验与响应映射使用。
 */
public record DefinitionRow(UUID definitionId, String definitionCode,
                            String definitionName, String status, long lockVersion,
                            Instant createdAt, Instant updatedAt) {
}
