package com.flowdesk.alert.api;

import com.flowdesk.alert.service.WebhookAcceptanceService;
import com.flowdesk.alert.webhook.WebhookAlertPayload;
import com.flowdesk.alert.webhook.WebhookAuthenticationFilter;
import com.flowdesk.common.web.ApiException;
import com.flowdesk.common.web.ApiMeta;
import com.flowdesk.common.web.RequestCorrelation;
import com.flowdesk.common.web.SuccessEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(prefix="flowdesk.alert.webhook",name="enabled",havingValue="true")
public class WebhookController {
    private final WebhookAcceptanceService service;
    public WebhookController(WebhookAcceptanceService service){this.service=service;}

    @PostMapping(path="/api/v1/webhooks/monitoring/alerts",
            consumes="application/json",produces="application/json")
    public ResponseEntity<SuccessEnvelope<AlertDtos.WebhookReceipt>> accept(
            HttpServletRequest request) {
        var verified=WebhookAuthenticationFilter.verified(request);
        final WebhookAlertPayload payload;
        try{payload=WebhookAlertPayload.parse(verified.rawBody());}
        catch(WebhookAlertPayload.InvalidPayload invalid){
            throw ApiException.unprocessable("WEBHOOK_BODY_INVALID","Webhook body 无效。",
                    java.util.List.of());
        }
        var receipt=service.accept(verified,payload);
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                .body(new SuccessEnvelope<>(receipt,new ApiMeta(RequestCorrelation.current())));
    }
}
