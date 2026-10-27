package com.flowdesk.workflow.service;


import com.flowdesk.workflow.model.WorkflowOutboxModels.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 管理工作流事务消息的领取、发送结果结算和恢复转移。
 * 各操作只持有短数据库事务；实际向消息代理投递由调用方在事务返回后执行，避免网络等待占用数据库锁。
 */
public interface WorkflowOutboxService {
    /** 查询到达发送时间或租约已失效的候选消息，按给定数量限制本轮扫描范围。 */
    List<Candidate> findCandidates(Instant now, Instant leaseCutoff, int limit);
    /** 尝试用指定令牌领取候选消息的发送租约；竞争失败或状态已变化时返回空值。 */
    Optional<Claimed> claimForSend(Candidate candidate, String token, Instant now, Instant leaseCutoff);
    /** 为恢复处理领取候选消息，使用令牌和租约截止时间防止并发恢复重复操作同一记录。 */
    Optional<Claimed> claimForRecovery(Candidate candidate, String token, Instant now, Instant leaseCutoff);
    /** 仅在领取令牌和租约仍匹配时标记投递完成，返回本次条件更新是否成功。 */
    boolean markPublished(Claimed row, Instant now, Instant leaseCutoff);
    /** 将仍由当前处理者持有的消息退回待发送状态，同时记录下次重试时间和失败原因。 */
    boolean markPending(Claimed row, Instant now, Instant leaseCutoff, Instant next, String errorCode);
    /** 将无法继续自动投递的消息转入恢复流程，并关联恢复记录及供排障使用的错误摘要。 */
    boolean moveToRecovery(Claimed row, Instant now, Instant leaseCutoff, String errorCode, String summary, UUID recoveryId);
}
