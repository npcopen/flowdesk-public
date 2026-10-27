package com.flowdesk.iam.service.impl;

import com.flowdesk.iam.mapper.CredentialMapper;
import com.flowdesk.iam.service.CredentialFailureService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;

/**
 * 以独立事务累计错误密码次数并设置账户锁定期限；过期锁定后的失败计数从新一轮尝试开始。
 */
@Service
public class CredentialFailureServiceImpl implements CredentialFailureService {
    private final CredentialMapper mapper;
    /**
     * 注入凭证 Mapper，以独立事务持久化失败次数及锁定期限，避免登录拒绝时回滚计数。
     */
    public CredentialFailureServiceImpl(CredentialMapper mapper) { this.mapper = mapper; }

    /**
     * 在独立事务中累加错误密码次数；达到阈值时设置锁定期限，使认证异常不会回滚失败记录。
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailedAttempt(UUID tenantId, UUID userId, Instant now, int maxFailedAttempts, Instant lockUntil) {
        mapper.incrementFailedAttempt(tenantId, userId, now);
        mapper.lockAfterFailedAttempt(tenantId, userId, maxFailedAttempts, lockUntil);
    }
}
