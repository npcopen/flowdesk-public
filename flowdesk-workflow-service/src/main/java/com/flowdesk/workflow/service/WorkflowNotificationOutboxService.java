package com.flowdesk.workflow.service;


import com.flowdesk.workflow.model.WorkflowNotificationOutboxModels.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 工作流业务用例契约，供入口通过稳定方法调用查询或命令处理，并统一约束可见范围及副作用。
 */
public interface WorkflowNotificationOutboxService {
    /**
     * 按时间、过期租约与数量上限扫描本轮可处理的 Outbox 候选消息。
     */
    List<Candidate> findCandidates(Instant now, Instant leaseCutoff, int limit);
    /**
     * 按状态、版本和租约条件领取发送权，竞争失败返回空值以防重复结算。
     */
    Optional<Claimed> claimForSend(Candidate candidate, String token, Instant now, Instant leaseCutoff);
    /**
     * 为达到发送上限的消息领取恢复权，核验当前版本和租约后继续处理。
     */
    Optional<Claimed> claimForRecovery(Candidate candidate, String token, Instant now, Instant leaseCutoff);
    /**
     * 仅按当前领取身份标记发布完成，避免过期处理者覆盖其他实例的发送结果。
     */
    boolean markPublished(Claimed row, Instant now, Instant leaseCutoff);
    /**
     * 将当前持有消息退回待发送状态，保存下次重试时间与错误码。
     */
    boolean markPending(Claimed row, Instant now, Instant leaseCutoff, Instant nextAttemptAt, String errorCode);
    /**
     * 将耗尽自动发送次数的消息转入恢复状态，保存恢复事实与排障信息。
     */
    boolean moveToRecovery(Claimed row, Instant now, Instant leaseCutoff, String errorCode, String summary, UUID recoveryId);
    /**
     * 将不满足冻结契约的已领取消息转入恢复，阻止非法信封继续投递。
     */
    boolean rejectContract(Claimed row, Instant now, Instant leaseCutoff, String errorCode, String summary, UUID recoveryId);
}
