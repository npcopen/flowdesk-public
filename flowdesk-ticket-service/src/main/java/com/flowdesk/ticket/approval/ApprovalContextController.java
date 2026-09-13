package com.flowdesk.ticket.approval;

import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.web.*;
import com.flowdesk.ticket.repository.TicketRepository;
import com.flowdesk.ticket.service.TicketSupport;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.UUID;

@RestController
public class ApprovalContextController {
    private final TicketRepository repository;
    private final ApprovalAccessChecker checker;
    public ApprovalContextController(TicketRepository repository, ApprovalAccessChecker checker) {
        this.repository = repository;
        this.checker = checker;
    }

    @GetMapping("/api/v1/tickets/{ticketId}/approval-context/{taskId}")
    @RequiresPermission(PermissionCode.APPROVAL_READ_ASSIGNED)
    public SuccessEnvelope<ApplicationSummary> get(@PathVariable UUID ticketId, @PathVariable UUID taskId,
            @RequestHeader("Authorization") String authorization) {
        var context = TicketSupport.context();
        if (!checker.canRead(context.tenantId(), TicketSupport.actorId(), taskId, ticketId, authorization)) {
            throw ApiException.notFound("RESOURCE_NOT_FOUND", "申请信息不可见。");
        }
        var ticket = repository.findTicket(context.tenantId(), ticketId)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "申请信息不可见。"));
        String catalog = ticket.catalogItemId() == null ? null
                : repository.findCatalogItem(context.tenantId(), ticket.catalogItemId())
                        .map(TicketRepository.CatalogItemRow::itemName).orElse(null);
        return new SuccessEnvelope<>(new ApplicationSummary(ticket.ticketId(), ticket.ticketNo(),
                ticket.requesterUserId(), ticket.subject(), ticket.description(), ticket.status(),
                catalog, ticket.createdAt()), new ApiMeta(RequestCorrelation.current()));
    }

    public record ApplicationSummary(UUID ticketId, String ticketNo, UUID requesterUserId,
                                     String subject, String description, String status,
                                     String catalogName, Instant createdAt) { }
}
