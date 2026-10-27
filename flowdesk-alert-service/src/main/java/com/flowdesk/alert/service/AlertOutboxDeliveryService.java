package com.flowdesk.alert.service;
import com.flowdesk.alert.model.*;
import java.time.Instant;
import java.util.*;
import com.flowdesk.alert.event.AlertEventContract;
/**
 * 告警发件箱的领取和结算用例；发布器通过 token、版本及租约确认当前发送权，失败用尽后登记人工恢复。
 */
public interface AlertOutboxDeliveryService {
    /**
     * 有界扫描到期或租约已过期的发件箱候选；候选快照不授予发送权，实际发送前仍须领取。
     */
    List<AlertOutboxCandidate> candidates(Instant now, Instant cutoff);
    /**
     * 依据候选的状态、次数、旧租约和版本条件领取消息；成功后返回本轮发送数据与凭据，竞争失败返回空结果。
     */
    Optional<AlertOutboxClaim> claim(AlertOutboxCandidate c, String token, Instant now, Instant cutoff);
    /**
     * 为已耗尽投递次数的消息取得恢复结算权，不增加尝试次数，也不再授权向 Broker 发送。
     */
    Optional<AlertOutboxClaim> claimRecovery(AlertOutboxCandidate c, String token, Instant now, Instant cutoff);
    /**
     * 仅在本轮 token、版本和有效租约匹配时确认发布成功，并同步结束对应的发件箱恢复记录。
     */
    boolean published(AlertOutboxClaim r, Instant now, Instant cutoff);
    /**
     * 在有效领取权内保存错误及下次重试时间并释放发布锁；旧持有者失去租约后不能覆盖新一轮状态。
     */
    boolean pending(AlertOutboxClaim r, Instant now, Instant cutoff, Instant next, String code);
    /**
     * 将耗尽次数的消息及相关建单关联转入人工恢复，并在同一本地事务中登记恢复记录和审计。
     */
    boolean recover(AlertOutboxClaim r, Instant now, Instant cutoff, String code);
}
