package com.flowdesk.ticket.incident;

import com.flowdesk.ticket.model.incident.result.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 业务能力契约；实现直接使用 MyBatis Mapper，保留调用方事务边界。 */
public interface IncidentResultDispatchService {
    /**
     * 查询到期或领取租约过期的发件箱候选，限制单批数量。
     */
    List<Candidate> candidates(Instant now, Instant cutoff);

    /**
     * 按当前状态和版本领取处理权，返回本次租约快照。
     */
    Optional<Claimed> claim(Candidate candidate, String token, Instant now,
                                   Instant cutoff);

    /**
     * 领取需转入恢复的发件箱记录，以令牌和版本约束结算者。
     */
    Optional<Claimed> claimRecovery(Candidate candidate, String token, Instant now,
                                           Instant cutoff);

    /**
     * 条件记录发布成功，并收敛关联恢复状态。
     */
    boolean published(Claimed row, Instant now, Instant cutoff);

    /**
     * 在租约仍有效时安排重试时间，并保留本轮错误码。
     */
    boolean pending(Claimed row, Instant now, Instant cutoff, Instant next, String code);

    /**
     * 将耗尽发送机会的记录转入恢复，保留恢复及审计事实。
     */
    boolean recover(Claimed row, Instant now, Instant cutoff, String code);
}
