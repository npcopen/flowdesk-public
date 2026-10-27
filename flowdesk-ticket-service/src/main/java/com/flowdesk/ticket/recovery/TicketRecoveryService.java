package com.flowdesk.ticket.recovery;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
/**
 * 消息恢复查询及重放受理契约，统一权限、租户、版本和幂等控制。
 * 实现层负责组织本地事务边界，使重放受理与相关恢复事实保持一致。
 */
public interface TicketRecoveryService {
    /**
     * 按筛选条件与稳定游标分页查询消息恢复数据，返回结果及后续页边界。
     */
    Page list(String direction, String state, Boolean deadLettered, String error,
        Instant from, Instant to, int pageSize, String pageToken, String sort);
    /**
     * 读取指定消息恢复详情，并依据当前租户与权限校验资源可见性。
     */
    TicketRecoveryView get(UUID recoveryId);
    /**
     * 校验权限、状态、幂等键与版本后受理消息重放，返回恢复操作结果。
     */
    ReplayResult replay(UUID recoveryId, String reason, String key, String ifMatch);
    /**
     * 恢复列表当前页及后续游标，保持筛选和排序连续性。
     */
    record Page(List<TicketRecoveryView> items, String nextPageToken, String sort) { }
    /**
     * 恢复重放受理结果，携带恢复详情与幂等重放标记。
     */
    record ReplayResult(TicketRecoveryView recovery, boolean replayed) { }
}
