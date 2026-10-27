package com.flowdesk.ticket.model.reverse;

/**
 * 消息重复判定结果，携带分类与既有接收事实。
 */
public record Duplicate(DuplicateKind kind, Inbox inbox, String conflictCode) {
    /**
     * 拒绝端点未定义的查询参数，避免客户端误以为无效筛选条件已生效。
     */
    public static Duplicate none() { return new Duplicate(DuplicateKind.NONE, null, null); }
}
