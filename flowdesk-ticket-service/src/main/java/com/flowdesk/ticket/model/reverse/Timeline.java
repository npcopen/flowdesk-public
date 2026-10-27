package com.flowdesk.ticket.model.reverse;

import java.time.Instant;
import java.util.UUID;

/**
 * 反向结果对应时间线事实，供终态消费幂等核验。
 */
public record Timeline(UUID timelineId, String eventType, String summary,
                       String relatedRefType, UUID relatedRefId, String actorType,
                       UUID actorId, Instant occurredAt, UUID correlationId,
                       UUID causationId, byte[] effectKey) {
    /**
     * 保存记录字段，并对可变字节数组作防御性复制，避免调用方修改已构造的业务快照。
     */
    public Timeline {
        effectKey = effectKey == null ? null : effectKey.clone();
    }
    /**
     * 返回业务副作用幂等键的防御性副本，避免外部改写已保存的业务事实。
     */
    @Override public byte[] effectKey() { return effectKey == null ? null : effectKey.clone(); }
}
