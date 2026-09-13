package com.flowdesk.iam.api;

import com.flowdesk.common.web.ApiMeta;
import com.flowdesk.common.web.RequestCorrelation;
import com.flowdesk.common.web.SuccessEnvelope;
import com.flowdesk.iam.service.EligibilityService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.MultiValueMap;

/**
 * API-016 internal IAM eligibility pre-check for Ticket/Workflow services.
 */
@RestController
@RequestMapping("/api/v1/internal/iam")
public class EligibilityController {

    private final EligibilityService eligibilityService;

    public EligibilityController(EligibilityService eligibilityService) {
        this.eligibilityService = eligibilityService;
    }

    @PostMapping("/eligibility-checks")
    public SuccessEnvelope<IamDtos.EligibilityCheckResult> check(
            @Valid @RequestBody IamDtos.EligibilityCheckRequest request,
            @RequestParam MultiValueMap<String, String> params) {
        QueryParams.none(params);
        return new SuccessEnvelope<>(
                eligibilityService.check(request), new ApiMeta(RequestCorrelation.current()));
    }
}
