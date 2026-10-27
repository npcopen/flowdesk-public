package com.flowdesk.ticket.model.incident;

import java.time.Instant;
import java.util.UUID;

/**
 * 告警到工单的权威映射，供恢复和重复建单收敛。
 */
public record Mapping(UUID incidentId, UUID ticketId, byte[] creationIntent,
                      Instant establishedAt, String originType, String ticketStatus,
                      byte[] ticketIntent) {
    /**
     * 保存记录字段，并对可变字节数组作防御性复制，避免调用方修改已构造的业务快照。
     */
    public Mapping { creationIntent = creationIntent == null ? null : creationIntent.clone(); ticketIntent = ticketIntent == null ? null : ticketIntent.clone(); }
    /**
     * 返回工单创建意图的防御性副本，避免外部改写已保存的业务事实。
     */
    @Override public byte[] creationIntent() { return creationIntent == null ? null : creationIntent.clone(); }
    /**
     * 返回工单业务意图的防御性副本，避免外部改写已保存的业务事实。
     */
    @Override public byte[] ticketIntent() { return ticketIntent == null ? null : ticketIntent.clone(); }
}
