package com.flowdesk.ticket.sla;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 只提供到期候选的 SLA 索引端口；状态、截止时间、触发事实及收件人的最终判断均以 MySQL 为准。 */
public interface SlaDueIndex {
    /**
     * 写入或刷新到期候选，使扫描器按时间定位 SLA 阶段。
     */
    void upsert(UUID tenantId, UUID slaClockId, String stage, Instant dueAt);

    /**
     * 移除已失效候选，避免已结算或已触发阶段继续被扫描。
     */
    void remove(UUID tenantId, UUID slaClockId, String stage);

    /**
     * 按租户、截止时间与数量上限读取到期候选，供后台有界处理。
     */
    List<Candidate> due(UUID tenantId, Instant now, int limit);

    /**
     * 清理指定租户索引，供后续从数据库事实重新建立。
     */
    void clear(UUID tenantId);

    /** 当前适配器不可用时返回 false，调用方据此降级。 */
    boolean available();

    /**
     * SLA 到期索引候选，固定租户、时钟、阶段与到期时间，供扫描器回查数据库事实。
     */
    record Candidate(UUID tenantId, UUID slaClockId, String stage, double score) {
    }
}
