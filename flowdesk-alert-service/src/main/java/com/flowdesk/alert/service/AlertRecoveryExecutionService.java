package com.flowdesk.alert.service;
import com.flowdesk.alert.recovery.AlertRecoveryService;
/**
 * 已接纳 Inbox 人工重放的提交与执行契约；复用原消费者完成业务处理，并按人工尝试和租约结算。
 */
public interface AlertRecoveryExecutionService {
    /**
     * 提交已持久化的 Inbox 人工重放任务；执行队列拒绝时记录失败，租约仍为进程中断提供接管入口。
     */
    void submit(AlertRecoveryService.Work work);
    /**
     * 恢复人工尝试的可信上下文，确认仍持有租约后将原消息交给原消费者处理，并据持久化终态结算审计。
     */
    void execute(AlertRecoveryService.Work work);
}
