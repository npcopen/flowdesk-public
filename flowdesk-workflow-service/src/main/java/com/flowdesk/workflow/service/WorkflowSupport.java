package com.flowdesk.workflow.service;

import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.authz.PermissionPolicy;
import com.flowdesk.common.security.JwtPrincipal;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.common.web.ApiException;
import com.flowdesk.common.web.JwtPrincipalHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Authorization, idempotency and optimistic-concurrency helpers for Workflow HTTP APIs. */
public final class WorkflowSupport {

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._:-]{16,128}$");
    private static final Pattern ETAG = Pattern.compile("^\"lv:([0-9]+)\"$");

    private WorkflowSupport() {
    }

    public static TenantContext context() {
        return TenantContextHolder.current();
    }

    public static JwtPrincipal principal() {
        return JwtPrincipalHolder.current();
    }

    public static UUID actorId() {
        return principal().humanUserId();
    }

    public static void require(PermissionCode permission) {
        PermissionPolicy.require(context(), principal(), permission);
    }

    public static void requireAny(PermissionCode... permissions) {
        PermissionPolicy.requireAny(context(), principal(), permissions);
    }

    public static boolean hasAny(PermissionCode... permissions) {
        return PermissionPolicy.hasAny(principal(), permissions);
    }

    public static String requireIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
                    "命令必须携带 Idempotency-Key。", false);
        }
        if (!IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw ApiException.badRequest("IDEMPOTENCY_KEY_INVALID", "Idempotency-Key 格式无效。");
        }
        return key;
    }

    public static long parseIfMatch(String header) {
        if (header == null || header.isBlank()) {
            throw ApiException.preconditionRequired("修改资源必须携带 If-Match。");
        }
        var matcher = ETAG.matcher(header.trim());
        if (!matcher.matches()) {
            throw ApiException.badRequest("PRECONDITION_INVALID", "If-Match 必须是单个强 ETag。");
        }
        try {
            return Long.parseLong(matcher.group(1));
        }
        catch (NumberFormatException invalid) {
            throw ApiException.badRequest("PRECONDITION_INVALID", "If-Match 版本格式无效。");
        }
    }

    public static void rejectIfMatchOnCreate(String ifMatch) {
        if (ifMatch != null && !ifMatch.isBlank()) {
            throw ApiException.badRequest("PRECONDITION_INVALID", "创建命令不接受 If-Match。");
        }
    }

    public static ApiException versionConflict(long currentVersion) {
        return new ApiException(HttpStatusCode.valueOf(412), "RESOURCE_VERSION_CONFLICT",
                "资源已更新，请刷新后重试。", false, java.util.List.of(),
                Map.of(HttpHeaders.ETAG, etag(currentVersion)));
    }

    public static String etag(long lockVersion) {
        return "\"lv:" + lockVersion + "\"";
    }
}
