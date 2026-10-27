package com.flowdesk.ticket.recovery;

import com.flowdesk.common.recovery.RecoveryReplayResumer;

/** 受理与持久续跑共用的后台重放能力；消息投递发生在数据库事务之外。 */
public interface TicketRecoveryReplayService {
    /**
     * 提交已持久化受理的恢复任务，后续沿原消息路径继续处理。
     */
    void submit(TicketReplayWork work);
    /**
     * 根据持久化任务恢复执行，覆盖受理提交后、调度前进程中断的情况。
     */
    void resume(RecoveryReplayResumer.Task task);
}
