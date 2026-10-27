package com.flowdesk.alert.model;
import java.util.*;
import java.time.Instant;

/**
 * 告警恢复记录的完整查询投影，汇集方向、来源、状态、尝试次数、人工重放信息和并发版本。
 */
public record AlertRecoveryRow(
            UUID id,
            String direction,
            UUID source,
            byte[] intent,
            String state,
            int attempts,
            Instant next,
            String error,
            String summary,
            Instant dead,
            Instant required,
            int replays,
            Instant replayedAt,
            String replayedType,
            UUID replayedBy,
            String reason,
            UUID replayCorrelation,
            Instant resolved,
            long version,
            Instant created,
            Instant updated) {}
