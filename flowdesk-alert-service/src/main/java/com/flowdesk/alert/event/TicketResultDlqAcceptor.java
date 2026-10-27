package com.flowdesk.alert.event;
import com.flowdesk.alert.service.TicketResultProcessingService;

import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextScope;
import org.springframework.dao.DataAccessException;

/**
 * 接纳工单结果主消费耗尽重试后进入死信队列的消息，保存后续人工恢复需要的原始事实。
 *
 * <p>复用主消费的契约规则，但不在这里建立故障与工单关联。首次死信的 Inbox、OPEN 恢复记录和审计
 * 必须在同一事务中提交后才能 ACK；数据库暂时不可用时继续重试死信接纳，不能先确认再补记录。
 */
final class TicketResultDlqAcceptor {
    private final TicketResultEnvelopeGuard guard;
    private final TicketResultProcessingService repository;

    /**
     * 注入死信信封校验器及处理用例，使重试耗尽消息沿受控路径保存原文和人工恢复事实。
     */
    TicketResultDlqAcceptor(
            TicketResultEnvelopeGuard g, TicketResultProcessingService r) {
        guard = g;
        repository = r;
    }

    /** 契约错误不进入租户数据；已存在的消息由去重裁决直接确认，首次消息才创建恢复记录。 */
    TicketResultConsumer.Result accept(TicketResultTransportMessage delivery) {
        final AcceptedTicketResult m;
        try {
            m = guard.validateDlq(delivery);
        } catch (TicketResultContractViolation poison) {
            return TicketResultConsumer.Result.ACK;
        }
        var context =
                TenantContext.ofValidatedBrokerMessage(
                        m.tenantId(),
                        AlertEventContract.TICKET_ESTABLISHED.producer(),
                        m.correlationId());
        try (TenantContextScope ignored = TenantContextScope.open(context)) {
            try {
                repository.acceptDeadLetter(m);
                return TicketResultConsumer.Result.ACK;
            } catch (DataAccessException | IllegalStateException e) {
                return TicketResultConsumer.Result.RETRY;
            }
        }
    }
}
