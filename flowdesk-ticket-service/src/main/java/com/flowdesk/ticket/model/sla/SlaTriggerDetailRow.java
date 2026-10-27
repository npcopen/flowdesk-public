package com.flowdesk.ticket.model.sla;

import java.time.Instant;
import java.util.UUID;

/**
 * 阶段触发详情，保存稳定效果键、发布键及时间。
 */
public record SlaTriggerDetailRow(UUID slaClockId, String stage, byte[] effectKey,
                                  Instant dueAt, Instant triggeredAt, byte[] publicationKey) {
    /**
     * 保存记录字段，并对可变字节数组作防御性复制，避免调用方修改已构造的业务快照。
     */
    public SlaTriggerDetailRow {
        effectKey = effectKey == null ? null : effectKey.clone();
        publicationKey = publicationKey == null ? null : publicationKey.clone();
    }
    /**
     * 返回业务副作用幂等键的防御性副本，避免外部改写已保存的业务事实。
     */
    @Override public byte[] effectKey() { return effectKey == null ? null : effectKey.clone(); }
    /**
     * 返回消息发布幂等键的防御性副本，避免外部改写已保存的业务事实。
     */
    @Override public byte[] publicationKey() { return publicationKey == null ? null : publicationKey.clone(); }
}
