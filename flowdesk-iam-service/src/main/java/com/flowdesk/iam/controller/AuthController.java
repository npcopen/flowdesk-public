package com.flowdesk.iam.controller;

import com.flowdesk.iam.dto.IamDtos;
import com.flowdesk.iam.dto.QueryParams;

import com.flowdesk.common.web.ApiMeta;
import com.flowdesk.common.web.RequestCorrelation;
import com.flowdesk.common.web.SuccessEnvelope;
import com.flowdesk.iam.service.IamAuthService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import org.springframework.util.MultiValueMap;

/**
 * 登录和当前用户的 HTTP 入口（API-001/002），将认证结果封装为统一响应。
 */
@RestController
@RequestMapping("/api/v1")
public class AuthController {

    private final IamAuthService authService;

    /**
     * 注入当前 HTTP 入口所需的业务用例，使请求校验及响应包装调用同一服务能力。
     */
    public AuthController(IamAuthService authService) {
        this.authService = authService;
    }

    /**
     * 接收已通过字段校验的登录请求并传递请求关联标识，包装认证服务返回的令牌和用户会话。
     */
    @PostMapping("/auth/login")
    public ResponseEntity<SuccessEnvelope<IamDtos.AuthSessionResponse>> login(
            @Valid @RequestBody IamDtos.LoginRequest request,
            @RequestParam MultiValueMap<String, String> params) {
        QueryParams.none(params);
        UUID correlationId = RequestCorrelation.current();
        IamDtos.AuthSessionResponse session = authService.login(request, correlationId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new SuccessEnvelope<>(session, new ApiMeta(correlationId)));
    }

    /**
     * 读取认证主体当前的用户资料与有效授权，并返回统一成功响应。
     */
    @GetMapping("/users/me")
    public ResponseEntity<SuccessEnvelope<IamDtos.CurrentUserResponse>> me(
            @RequestParam MultiValueMap<String, String> params) {
        QueryParams.none(params);
        UUID correlationId = RequestCorrelation.current();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new SuccessEnvelope<>(authService.currentUser(), new ApiMeta(correlationId)));
    }
}
