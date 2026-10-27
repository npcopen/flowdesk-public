package com.flowdesk.iam.service;

import java.time.Instant;
import java.util.UUID;

/**
 * 记录登录失败与临时锁定的独立事务能力，使登录主事务抛出认证异常时仍能保留失败计数。
 */
public interface CredentialFailureService {
    /**
     * 在独立事务中累加错误密码次数；达到阈值时设置锁定期限，使认证异常不会回滚失败记录。
     */
    void recordFailedAttempt(UUID tenantId, UUID userId, Instant now, int maxFailedAttempts, Instant lockUntil);
}
