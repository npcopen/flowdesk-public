package com.flowdesk.iam.model;

import java.util.UUID;
import java.time.Instant;

/**
 * 用户角色或角色权限绑定的数据库快照；同时保留授予、撤销时间和锁版本，供条件写入判定。
 */
public record BindingRow(UUID subjectId, UUID targetId, String state, Instant grantedAt, Instant revokedAt, long lockVersion) {
}
