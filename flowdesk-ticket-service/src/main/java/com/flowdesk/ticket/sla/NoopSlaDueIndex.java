package com.flowdesk.ticket.sla;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Redis 索引关闭时的显式降级实现，由 MySQL 安全扫描继续发现 SLA 候选。 */
public final class NoopSlaDueIndex implements SlaDueIndex {
    /**
     * 索引关闭时忽略写入；到期发现由数据库安全扫描承担。
     */
    @Override public void upsert(UUID tenantId, UUID slaClockId, String stage, Instant dueAt) { }
    /**
     * 索引关闭时无需删除外部候选，此方法保持空操作。
     */
    @Override public void remove(UUID tenantId, UUID slaClockId, String stage) { }
    /**
     * 索引关闭时返回空候选集合，促使调用方依赖数据库扫描路径。
     */
    @Override public List<Candidate> due(UUID tenantId, Instant now, int limit) { return List.of(); }
    /**
     * 索引关闭时不存在需要清理的外部内容，此方法保持空操作。
     */
    @Override public void clear(UUID tenantId) { }
    /**
     * 始终返回不可用，明确通知扫描器使用数据库降级发现候选。
     */
    @Override public boolean available() { return false; }
}
