package com.flowdesk.ticket.service;

import com.flowdesk.common.web.AsyncProgress;
import com.flowdesk.ticket.dto.TicketDtos;
import java.util.List;
import java.util.UUID;

/**
 * 工单生命周期写操作的业务契约，统一创建、分派、受理、转派、解决、关闭和重开入口。
 * 实现层负责权限、状态和乐观锁校验，并在同一业务事务中记录审计、时间线及异步投递意图。
 */
public interface TicketService {
    /** 根据创建请求和幂等键创建工单；重复的相同意图返回原结果，并附带异步副作用进度。 */
    CreateResult create(TicketDtos.TicketCreateRequest request,
                               String idempotencyKey, String ifMatch);

    /** 校验目标处理人资格及 If-Match 版本后分派工单；重复命令使用幂等记录返回既有结果。 */
    TransitionResult assign(UUID ticketId, TicketDtos.AssignmentCommand request,
                                   String idempotencyKey, String ifMatch);

    /** 由当前处理人受理已分派工单，使用预期锁版本防止覆盖并发的状态变更。 */
    TransitionResult accept(UUID ticketId, String idempotencyKey, String ifMatch);

    /** 校验操作权限、目标处理人资格和版本后转派工单，保留转派原因及审计轨迹。 */
    TransitionResult transfer(UUID ticketId, TicketDtos.TransferCommand request,
                                     String idempotencyKey, String ifMatch);

    /** 为可见工单追加评论，以幂等键避免重试产生重复评论和时间线事件。 */
    CommentResult addComment(UUID ticketId, TicketDtos.CommentCreateRequest request,
                                    String idempotencyKey);

    /** 记录解决说明并将工单推进到已解决状态，同时按业务规则处理关联的 SLA 时钟。 */
    TransitionResult resolve(UUID ticketId, TicketDtos.ResolveCommand request,
                                    String idempotencyKey, String ifMatch);

    /** 在权限、当前状态及版本均满足约束时关闭工单，记录关闭原因和终态时间。 */
    TransitionResult close(UUID ticketId, TicketDtos.CloseOrReopenCommand request,
                                  String idempotencyKey, String ifMatch);

    /** 将满足重开条件的工单恢复到处理中，并记录重开原因、次数和相关 SLA 周期。 */
    TransitionResult reopen(UUID ticketId, TicketDtos.CloseOrReopenCommand request,
                                   String idempotencyKey, String ifMatch);

    /** 创建结果快照：包含工单详情、是否幂等重放以及工作流或通知等异步副作用进度。 */
    public record CreateResult(TicketDtos.TicketResponse ticket, boolean replayed,
                               List<AsyncProgress> asyncEffects) {
    }

    /** 生命周期流转结果；replayed 表示本次请求复用了先前成功执行的同一幂等命令。 */
    public record TransitionResult(TicketDtos.TicketResponse ticket, boolean replayed) {
    }

    /** 评论写入结果，携带已落库评论及幂等重放标记，供控制器选择响应状态码。 */
    public record CommentResult(TicketDtos.TicketCommentResponse comment, boolean replayed) {
    }
}
