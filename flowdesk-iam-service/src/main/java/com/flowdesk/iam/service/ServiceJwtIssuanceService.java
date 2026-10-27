package com.flowdesk.iam.service;

import com.flowdesk.common.security.FlowDeskJwtProperties;
import com.flowdesk.common.security.IssuedJwt;
import com.flowdesk.common.security.JwtIssuer;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 签发绑定租户的服务身份 JWT；仅允许配置白名单内的部署单元，签名私钥不离开 IAM。
 * 签发白名单与内部资格接口的验证白名单保持一致。
 */
@Service
public class ServiceJwtIssuanceService {

    private final JwtIssuer jwtIssuer;
    private final List<String> allowedServiceIds;

    /**
     * 保存 JWT 签发器及允许的服务标识配置，只有通过白名单校验的租户服务身份才能签发令牌。
     */
    public ServiceJwtIssuanceService(JwtIssuer jwtIssuer, FlowDeskJwtProperties jwtProperties) {
        this.jwtIssuer = Objects.requireNonNull(jwtIssuer, "jwtIssuer");
        this.allowedServiceIds = List.copyOf(
                Objects.requireNonNull(jwtProperties, "jwtProperties").getAllowedServiceIds());
    }

    /**
     * 签发短时效的 RS256 服务 JWT（{@code aud=flowdesk-internal}）。
     * @param tenantId 写入令牌的租户范围
     * @param serviceId 使用此令牌的部署单元标识
     * @return 序列化 JWT 及其权威到期时间
     * @throws IllegalArgumentException 服务标识不在 IAM 签发白名单时抛出
     */
    public IssuedJwt issue(UUID tenantId, String serviceId) {
        Objects.requireNonNull(tenantId, "tenantId");
        if (serviceId == null || serviceId.isBlank()) {
            throw new IllegalArgumentException("serviceId must not be blank");
        }
        if (!allowedServiceIds.contains(serviceId)) {
            throw new IllegalArgumentException(
                    "serviceId is not in flowdesk.security.jwt.allowed-service-ids: " + serviceId);
        }
        return jwtIssuer.issueService(tenantId, serviceId);
    }
}
