package com.flowdesk.ticket.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;

/** 人工重放的 HTTP 请求，仅接受原因字段。 */
public record TicketRecoveryReplayCommand(String reason) {
    /**
     * 把不可处理消息收敛为拒绝结果，保存契约允许的拒绝原因。
     */
    @JsonAnySetter public void reject(String field, Object ignored) {
        throw new IllegalArgumentException("Ticket replay request contains unknown field: " + field);
    }
}
