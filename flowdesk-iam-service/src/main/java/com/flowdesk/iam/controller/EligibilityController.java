package com.flowdesk.iam.controller;

import com.flowdesk.iam.dto.IamDtos;
import com.flowdesk.iam.dto.QueryParams;

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
 * 供工单和工作流服务调用的 IAM 资格预检接口（API-016）。
 */
@RestController
@RequestMapping("/api/v1/internal/iam")
public class EligibilityController {

    private final EligibilityService eligibilityService;

    /**
     * 注入当前 HTTP 入口所需的业务用例，使请求校验及响应包装调用同一服务能力。
     */
    public EligibilityController(EligibilityService eligibilityService) {
        this.eligibilityService = eligibilityService;
    }

    /**
     * 处理服务身份发起的资格预检，委托业务服务校验调用用途并返回统一资格结果。
     */
    @PostMapping("/eligibility-checks")
    public SuccessEnvelope<IamDtos.EligibilityCheckResult> check(
            @Valid @RequestBody IamDtos.EligibilityCheckRequest request,
            @RequestParam MultiValueMap<String, String> params) {
        QueryParams.none(params);
        return new SuccessEnvelope<>(
                eligibilityService.check(request), new ApiMeta(RequestCorrelation.current()));
    }
}
