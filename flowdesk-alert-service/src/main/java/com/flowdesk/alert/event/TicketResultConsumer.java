package com.flowdesk.alert.event;
import com.flowdesk.alert.service.TicketResultProcessingService;

import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextScope;
import org.springframework.dao.DataAccessException;


/**
 * 接收 Ticket 服务返回的“故障已关联工单”结果，完成 Alert 侧关联状态的最终确认。
 *
 * <p>主队列消息先经过信封校验，再进入租户上下文。故障关联、Inbox 消费结果、恢复记录和审计
 * 共用一个本地事务；只有事务成功提交后，传输适配器才能据返回值确认消息。人工恢复也复用这套业务裁决，
 * 因此不会绕过去重或覆盖 Ticket 已经确认的工单编号。
 */
public final class TicketResultConsumer {
    /** ACK 表示无需再次投递，也包括已记录的业务拒绝；RETRY 表示本次未能可靠完成处理。 */
    public enum Result {
        ACK,
        RETRY
    }

    private final TicketResultEnvelopeGuard guard;
    private final TicketResultProcessingService inbox;
    /**
     * 注入工单结果信封校验器及事务处理用例，确保消息先被验证再进入租户业务上下文。
     */
    TicketResultConsumer(TicketResultEnvelopeGuard guard, TicketResultProcessingService processing) {
        this.guard = guard;
        this.inbox = processing;
    }

    /**
     * 主队列入口：契约错误不会因重复投递自行修复，且此时还不能信任消息声称的租户，因而直接 ACK。
     * 校验通过后才允许访问租户数据；数据库错误的重试判断由后续事务入口负责。
     */
    public Result consume(TicketResultTransportMessage delivery) {
        final AcceptedTicketResult m;
        try {
            m = guard.validateMain(delivery);
        } catch (TicketResultContractViolation poison) {
            return Result.ACK;
        }
        return execute(m);
    }

    /** 接收已校验消息的内部重放入口，沿用主消费的事务、幂等和关联检查。 */
    Result replay(AcceptedTicketResult m) {
        return execute(m);
    }

    /** 上下文随作用域关闭；事务提交或回滚完成后才把 ACK / RETRY 交回调用方。 */
    private Result execute(AcceptedTicketResult m) {
        TenantContext context =
                TenantContext.ofValidatedBrokerMessage(
                        m.tenantId(),
                        AlertEventContract.TICKET_ESTABLISHED.producer(),
                        m.correlationId());
        try (TenantContextScope ignored = TenantContextScope.open(context)) {
            try {
                inbox.process(m);
                return Result.ACK;
            } catch (DataAccessException | IllegalStateException failure) {
                return Result.RETRY;
            }
        }
    }

}
