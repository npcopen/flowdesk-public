package com.flowdesk.alert.model;
import java.util.*;
import java.time.Instant;

/**
 * 人工恢复所需的发件箱及故障关联快照；保存源消息状态和版本，供同事务条件重置。
 */
public record AlertRecoveryOutbox(
            UUID outboxId,
            String contractRef,
            int contractVersion,
            UUID incidentId,
            UUID correlation,
            String status,
            long version,
            String linkStatus,
            long linkVersion) {}
