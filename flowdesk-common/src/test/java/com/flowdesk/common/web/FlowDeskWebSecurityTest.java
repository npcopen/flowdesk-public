package com.flowdesk.common.web;

import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.authz.RoleCode;
import com.flowdesk.common.security.IssuedJwt;
import com.flowdesk.common.security.JwtIssuer;
import com.flowdesk.common.security.JwtVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 通过 Servlet 安全链验证 JWT、权限、租户上下文清理及请求体限制，检查无效输入在业务前被拒绝。
 */
class FlowDeskWebSecurityTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static JwtIssuer issuer;
    private static MockMvc mockMvc;

    /**
     * 初始化当前测试所需签名密钥、认证配置或 Mock MVC 环境，使后续行为断言基于隔离依赖。
     */
    @BeforeAll
    static void setUp() throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        issuer = new JwtIssuer(keyPair.getPrivate(), "flowdesk-iam-service",
                "flowdesk-api", "flowdesk-internal", 900, 300);
        JwtVerifier verifier = new JwtVerifier(keyPair.getPublic(), "flowdesk-iam-service",
                "flowdesk-api", "flowdesk-internal", 30);
        mockMvc = MockMvcBuilders.standaloneSetup(new ProtectedController())
                .setControllerAdvice(new ApiExceptionHandler())
                .addInterceptors(new PermissionInterceptor())
                .addFilters(new CorrelationIdFilter(List.of()),
                        new FlowDeskRequestBodyPolicyFilter(new tools.jackson.databind.ObjectMapper()),
                        new HumanJwtAuthenticationFilter(verifier, List.of(), List.of()),
                        new ServiceJwtAuthenticationFilter(verifier, List.of(), List.of()))
                .build();
    }

    /**
     * 清除测试线程中的租户及 JWT 主体持有器，避免认证状态影响后续用例。
     */
    @AfterEach
    void clearHolders() {
        JwtPrincipalHolder.clear();
    }

    /**
     * 验证有效且有权限的令牌可访问保护接口，响应完成后线程上下文被清理。
     */
    @Test
    void validTokenWithPermissionPassesAndCleansContext() throws Exception {
        IssuedJwt jwt = issuer.issueHuman(TENANT, USER,
                Set.of(RoleCode.TENANT_ADMIN), Set.of(PermissionCode.IAM_USER_READ));
        mockMvc.perform(get("/api/v1/protected").header("Authorization", "Bearer " + jwt.token()))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(jsonPath("$.ok").value(true));
        org.assertj.core.api.Assertions.assertThat(JwtPrincipalHolder.currentOrNull()).isNull();
    }

    /**
     * 验证有效令牌缺少所需权限时返回拒绝，而非仅凭验签成功放行。
     */
    @Test
    void validTokenWithoutPermissionIsDenied() throws Exception {
        IssuedJwt jwt = issuer.issueHuman(TENANT, USER,
                Set.of(RoleCode.REQUESTER), Set.of());
        mockMvc.perform(get("/api/v1/protected").header("Authorization", "Bearer " + jwt.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
    }

    /**
     * 验证保护接口缺少令牌时在业务调用前返回认证错误。
     */
    @Test
    void missingTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/protected"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
    }

    /**
     * 验证伪造内部租户请求头不能覆盖 JWT 中的可信租户身份。
     */
    @Test
    void forgedTenantHeadersAreIgnoredAndNotTrusted() throws Exception {
        IssuedJwt jwt = issuer.issueHuman(TENANT, USER,
                Set.of(RoleCode.TENANT_ADMIN), Set.of(PermissionCode.IAM_USER_READ));
        // The JWT tenant is the only trust root; a forged header must not change anything.
        mockMvc.perform(get("/api/v1/protected")
                        .header("Authorization", "Bearer " + jwt.token())
                        .header("X-Tenant-Id", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    /**
     * 验证超大请求体在业务执行前被拒绝，返回统一体积限制错误。
     */
    @Test
    void oversizedBodyIsRejectedWithRequestTooLarge() throws Exception {
        mockMvc.perform(post("/api/v1/protected")
                        .contentType("application/json")
                        .content("x".repeat(257 * 1024)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("REQUEST_TOO_LARGE"));
    }

    /**
     * 验证登录请求采用专属的更小报文上限，避免通用上限弱化登录入口保护。
     */
    @Test
    void loginBodyHasItsOwnSmallerLimit() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("x".repeat(9 * 1024)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("REQUEST_TOO_LARGE"));
    }

    /**
     * 验证非 identity 内容编码被拒绝，防止压缩报文绕过原始请求大小与解析约束。
     */
    @Test
    void nonIdentityContentEncodingIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/protected")
                        .header("Content-Encoding", "gzip")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("CONTENT_ENCODING_UNSUPPORTED"));
    }

    /**
     * 验证签名无效的 JWT 在建立可信业务上下文前被拒绝。
     */
    @Test
    void invalidSignatureIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/protected").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_FAILED"));
    }

    /**
     * 仅供安全链测试使用的受权限保护端点，返回固定内容以确认请求是否真正到达业务处理。
     */
    @RestController
    static class ProtectedController {

        /**
         * 返回固定成功内容，供测试断言带有效 JWT 和权限的请求已通过整个安全链。
         */
        @GetMapping("/api/v1/protected")
        @RequiresPermission(PermissionCode.IAM_USER_READ)
        public java.util.Map<String, Boolean> get() {
            return java.util.Map.of("ok", true);
        }
    }
}
