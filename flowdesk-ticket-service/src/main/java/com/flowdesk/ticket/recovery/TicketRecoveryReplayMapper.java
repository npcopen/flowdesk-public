package com.flowdesk.ticket.recovery;

import com.flowdesk.common.recovery.RecoveryReplayMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 在工单服务中注册共享的 Inbox 重放 Mapper，复用到期任务扫描及带版本保护的租约认领 SQL。
 * 服务通过本地子接口绑定自身的数据访问配置，实际扫描和认领实现位于公共接口。
 */
@Mapper
public interface TicketRecoveryReplayMapper extends RecoveryReplayMapper { }
