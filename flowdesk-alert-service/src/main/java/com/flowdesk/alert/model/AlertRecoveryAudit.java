package com.flowdesk.alert.model;
import java.util.*;
import java.time.Instant;

/**
 * 人工恢复审计的数据库投影，保留目标、操作者、意图摘要、结果及因果关系，供重复请求核对。
 */
public record AlertRecoveryAudit(
            String action,
            UUID target,
            String outcome,
            String actorType,
            UUID actor,
            String reason,
            String error,
            UUID correlation,
            UUID causation) {}
