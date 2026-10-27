package com.flowdesk.ticket.notification;

import com.flowdesk.ticket.model.notification.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 业务能力契约；实现直接使用 MyBatis Mapper，保留调用方事务边界。 */
public interface TicketNotificationDispatchService {
    /**
     * 读取满足到期与租约条件的发件箱候选，供后台有界发布。
     */
    List<Candidate> findCandidates(Instant now, Instant leaseCutoff, int limit);

    /**
     * 条件领取待发记录并建立租约，仅成功领取者可发起传输。
     */
    Optional<Claimed> claimForSend(Candidate candidate, String token, Instant now, Instant leaseCutoff);

    /**
     * 领取需要恢复的记录，防止并发工作器重复建立恢复事实。
     */
    Optional<Claimed> claimForRecovery(Candidate candidate, String token, Instant now, Instant leaseCutoff);

    /**
     * 在领取身份仍有效时标记发布成功，阻止过期工作器覆盖新结果。
     */
    boolean markPublished(Claimed row, Instant now, Instant leaseCutoff);

    /**
     * 将未确认成功的消息重新排队，记录退避后的下次尝试时间。
     */
    boolean markPending(Claimed row, Instant now, Instant leaseCutoff, Instant next, String errorCode);

    /**
     * 将发件箱失败原子转入恢复，保留来源身份和安全摘要。
     */
    boolean moveToRecovery(Claimed row, Instant now, Instant leaseCutoff, String code, String summary, UUID recoveryId);

    /**
     * 将契约非法的持久化消息拒绝并转入恢复，避免反复无效发送。
     */
    boolean rejectContract(Claimed row, Instant now, Instant leaseCutoff, String code, String summary, UUID recoveryId);
}
