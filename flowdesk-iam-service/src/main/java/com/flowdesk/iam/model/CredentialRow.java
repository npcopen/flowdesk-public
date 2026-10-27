package com.flowdesk.iam.model;

import java.util.UUID;
import java.time.Instant;

/**
 * 登录验证所需的凭证快照，关联租户、用户状态、加盐密码摘要、失败次数与锁定期限。
 */
public record CredentialRow(UUID tenantId, UUID userId, String username, String userStatus, String tenantCode, String tenantDisplayName, byte[] passwordHash, int failedAttemptCount, Instant lockedUntil) {
}
