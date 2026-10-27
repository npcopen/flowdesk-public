package com.flowdesk.ticket.model.incident.result;

import java.util.UUID;

/**
 * 消息恢复状态快照，供重放受理与发布结算。
 */
public record RecoveryState(UUID recoveryId, byte[] intent, String state,
                             int attemptCount, String errorCode, String summary,
                             long lockVersion) {
    /**
     * 保存记录字段，并对可变字节数组作防御性复制，避免调用方修改已构造的业务快照。
     */
    public RecoveryState {
        intent = intent == null ? null : intent.clone();
    }
    /**
     * 返回规范业务意图的防御性副本，避免外部改写已保存的业务事实。
     */
    @Override public byte[] intent() { return intent == null ? null : intent.clone(); }
}
