package com.flowdesk.iam.model;

import java.util.UUID;
import java.time.Instant;

/**
 * 租户用户列表与详情的不可变数据库投影，保留账户状态、乐观锁版本以及创建和更新时间。
 */
public record UserRow(UUID userId, String username, String displayName, String status, long lockVersion, Instant createdAt, Instant updatedAt) {
}
