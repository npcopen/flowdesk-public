package com.flowdesk.alert.model;
import java.util.*;
import java.time.Instant;

/**
 * 发件箱扫描所得的领取候选，保存租户、旧状态、次数、版本及锁信息；实际发送前必须再次条件领取。
 */
public record AlertOutboxCandidate(
            UUID tenantId,
            UUID outboxId,
            String status,
            int attemptCount,
            String lockedBy,
            UUID correlationId,
            long lockVersion) {}
