package com.flowdesk.ticket.iam;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import java.util.UUID;

/**
 * IAM API-016 的声明式 HTTP 接口，只负责请求和响应的传输。
 *
 * <p>默认以 IAM 的注册服务名寻址；显式配置 base-url 时改为直连，便于本地调试。
 * 认证与关联编号由业务适配层逐请求传入，不使用全局拦截器读取或缓存租户身份。
 * 响应保留为原始 JSON，由 IamEligibilityClient 沿用原有字段核验和错误转换规则。
 */
@FeignClient(
        name = "flowdesk-iam-service",
        contextId = "ticketIamEligibility",
        url = "${flowdesk.ticket.iam.base-url:}",
        configuration = TicketIamFeignConfiguration.class)
public interface IamEligibilityFeignClient {

    /**
     * 调用资格校验接口并解析结果，供写入前确认目标用户资格。
     */
    @PostMapping(
            value = "/api/v1/internal/iam/eligibility-checks",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    String check(
            @RequestHeader("Authorization") String authorization,
            @RequestHeader("X-Correlation-Id") String correlationId,
            @RequestBody EligibilityRequest request);

    /** 仅声明 IAM 契约需要的字段，不依赖 IAM 服务的生产类或数据库实体。 */
    record EligibilityRequest(UUID subjectUserId, String requiredRoleCode, String purpose) {}
}
