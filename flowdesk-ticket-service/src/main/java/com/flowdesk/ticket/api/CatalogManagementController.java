package com.flowdesk.ticket.api;
import com.flowdesk.ticket.service.CatalogManagementService;
import com.flowdesk.ticket.service.TicketSupport;
import com.flowdesk.common.web.*;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/service-catalog-items")
public class CatalogManagementController {
    private final CatalogManagementService service;
    public CatalogManagementController(CatalogManagementService service) { this.service=service; }
    @PostMapping
    public ResponseEntity<SuccessEnvelope<TicketDtos.ServiceCatalogItemResponse>> create(@Valid @RequestBody CatalogWriteRequest request,
            @RequestHeader(value="Idempotency-Key",required=false) String key, @RequestHeader("Authorization") String authorization) {
        var result=service.create(request,key,authorization);
        return ResponseEntity.status(result.replayed()?200:201).header("ETag","\"lv:"+result.item().lockVersion()+"\"")
                .header("Location","/api/v1/service-catalog-items/"+result.item().catalogItemId())
                .body(new SuccessEnvelope<>(result.item(),new ApiMeta(RequestCorrelation.current())));
    }
    @PutMapping("/{id}/configuration")
    public ResponseEntity<SuccessEnvelope<TicketDtos.ServiceCatalogItemResponse>> configure(@PathVariable UUID id,
            @Valid @RequestBody CatalogWriteRequest request, @RequestHeader(value="If-Match",required=false) String match,
            @RequestHeader("Authorization") String authorization) {
        var item=service.configure(id,request,TicketSupport.parseIfMatch(match),authorization);
        return ResponseEntity.ok().header("ETag","\"lv:"+item.lockVersion()+"\"").body(new SuccessEnvelope<>(item,new ApiMeta(RequestCorrelation.current())));
    }
}
