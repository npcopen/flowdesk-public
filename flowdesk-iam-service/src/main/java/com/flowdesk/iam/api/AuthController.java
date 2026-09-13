package com.flowdesk.iam.api;

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
 * API-001 login and API-002 current user.
 */
@RestController
@RequestMapping("/api/v1")
public class AuthController {

    private final IamAuthService authService;

    public AuthController(IamAuthService authService) {
        this.authService = authService;
    }

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
