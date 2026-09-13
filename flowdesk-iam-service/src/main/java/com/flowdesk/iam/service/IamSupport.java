package com.flowdesk.iam.service;

import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.authz.PermissionPolicy;
import com.flowdesk.common.security.JwtPrincipal;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.common.web.ApiException;
import com.flowdesk.common.web.JwtPrincipalHolder;
import org.springframework.http.HttpHeaders;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Shared authorization/HTTP precondition helpers for IAM use cases. */
public final class IamSupport {

    static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._:-]{16,128}$");
    private static final Pattern ETAG = Pattern.compile("^\"lv:([0-9]+)\"$");

    private IamSupport() {
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
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("PRECONDITION_INVALID", "If-Match 版本格式无效。");
        }
    }

    public static void requireCreatePrecondition(String ifMatch, String ifNoneMatch) {
        if (ifMatch != null && !ifMatch.isBlank()) {
            throw ApiException.badRequest("PRECONDITION_INVALID", "新关系不能同时使用 If-Match。");
        }
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            throw ApiException.preconditionRequired("新关系必须携带 If-None-Match: *。");
        }
        if (!"*".equals(ifNoneMatch.trim())) {
            throw ApiException.badRequest("PRECONDITION_INVALID", "If-None-Match 只接受 *。");
        }
    }

    public static void requireUpdatePrecondition(String ifMatch, String ifNoneMatch) {
        boolean hasIfMatch = ifMatch != null && !ifMatch.isBlank();
        boolean hasIfNoneMatch = ifNoneMatch != null && !ifNoneMatch.isBlank();
        if (hasIfMatch && hasIfNoneMatch) {
            throw ApiException.badRequest("PRECONDITION_INVALID",
                    "If-Match 与 If-None-Match 不能同时提交。");
        }
        if (hasIfNoneMatch) {
            if (!"*".equals(ifNoneMatch.trim())) {
                throw ApiException.badRequest("PRECONDITION_INVALID",
                        "If-None-Match 只接受 *。");
            }
            throw ApiException.preconditionFailed("RESOURCE_PRECONDITION_FAILED",
                    "If-None-Match:* 前提失败，目标关系已存在。", Map.of());
        }
        parseIfMatch(ifMatch);
    }

    public static String requireIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "IDEMPOTENCY_KEY_REQUIRED", "命令必须携带 Idempotency-Key。", false);
        }
        if (!IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "IDEMPOTENCY_KEY_INVALID", "Idempotency-Key 格式无效。", false);
        }
        return key;
    }

    public static ApiException versionConflict(Map<String, String> headers) {
        return new ApiException(org.springframework.http.HttpStatusCode.valueOf(412),
                "RESOURCE_VERSION_CONFLICT", "资源已更新，请刷新后重试。", false,
                java.util.List.of(), headers);
    }

    public static Map<String, String> etagHeaders(long lockVersion) {
        return Map.of(HttpHeaders.ETAG, "\"lv:" + lockVersion + "\"");
    }
}
