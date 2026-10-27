package com.flowdesk.workflow.start;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * IAM API-016 的声明式传输接口，默认按注册服务名寻址，也支持显式 base-url 直连。
 *
 * <p>认证及关联编号由业务适配层逐请求传入，不使用跨客户端的身份拦截器。
 * 响应保留为原始 JSON，让 WorkflowIamEligibilityClient 沿用严格的资格判定和异常转换。
 */
@FeignClient(
        name = "flowdesk-iam-service",
        contextId = "workflowIamEligibility",
        url = "${flowdesk.workflow.iam.base-url:}",
        configuration = WorkflowIamFeignConfiguration.class)
public interface WorkflowIamEligibilityFeignClient {

    /**
     * 向 IAM 内部接口发送审批资格检查请求，返回目标用户的当前租户资格。
     */
    @PostMapping(
            value = "/api/v1/internal/iam/eligibility-checks",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    String check(
            @RequestHeader("Authorization") String authorization,
            @RequestHeader("X-Correlation-Id") String correlationId,
            @RequestBody EligibilityRequest request);

    /** 仅描述远端契约字段，保持原请求的字符串形状，不依赖 IAM 的生产 DTO 或实体。 */
    record EligibilityRequest(String subjectUserId, String requiredRoleCode, String purpose) {}
}
