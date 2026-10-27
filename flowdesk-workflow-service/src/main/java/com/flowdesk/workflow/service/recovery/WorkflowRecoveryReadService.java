package com.flowdesk.workflow.service.recovery;

import com.flowdesk.workflow.dto.WorkflowRecoveryView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 恢复查询契约，统一当前租户列表、详情和路由校验。
 */
public interface WorkflowRecoveryReadService {
    /**
     * 按声明的筛选和分页条件读取当前租户可见记录，返回本页数据及下一页边界。
     */
    Page list(String direction, String state, Boolean deadLettered, String error,
              Instant from, Instant to, int pageSize, String pageToken, String sort);
    /**
     * 读取当前租户内指定记录，不存在或不可见时返回统一资源错误。
     */
    WorkflowRecoveryView get(UUID recoveryId);
    /**
     * 工作流处理结果封装，组合业务快照及分页或幂等元信息供调用入口使用。
     */
    record Page(List<WorkflowRecoveryView> items, String nextPageToken, String sort) {}
}
