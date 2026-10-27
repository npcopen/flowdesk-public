package com.flowdesk.common.recovery;

import java.util.UUID;

/**
 * 满足租约接管条件的持久化人工重放候选，保存租户、源记录、操作者、原因及乐观锁版本。
 */
public record RecoveryReplayCandidate(UUID tenantId, UUID recoveryId, UUID sourceRecordId,
        UUID actorId, String reason, UUID attemptCorrelationId, UUID sourceCorrelationId,
        long lockVersion) { }
