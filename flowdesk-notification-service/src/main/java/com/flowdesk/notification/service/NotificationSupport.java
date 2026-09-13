package com.flowdesk.notification.service;

import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.authz.PermissionPolicy;
import com.flowdesk.common.security.JwtPrincipal;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.common.web.ApiException;
import com.flowdesk.common.web.JwtPrincipalHolder;

import java.util.UUID;
import java.util.regex.Pattern;

final class NotificationSupport {
    private static final Pattern IDEMPOTENCY = Pattern.compile("^[A-Za-z0-9._:-]{16,128}$");
    private static final Pattern ETAG = Pattern.compile("^\"lv:([0-9]+)\"$");

    private NotificationSupport() {
    }

    static TenantContext context() { return TenantContextHolder.current(); }
    static JwtPrincipal principal() { return JwtPrincipalHolder.current(); }
    static UUID actorId() { return principal().humanUserId(); }
    static void require(PermissionCode permission) { PermissionPolicy.require(context(), principal(), permission); }
    static String idempotency(String key) {
        if (key == null || key.isBlank()) throw ApiException.badRequest("IDEMPOTENCY_KEY_REQUIRED", "命令必须携带 Idempotency-Key。");
        if (!IDEMPOTENCY.matcher(key).matches()) throw ApiException.badRequest("IDEMPOTENCY_KEY_INVALID", "Idempotency-Key 格式无效。");
        return key;
    }
    static long ifMatch(String value) {
        if (value == null || value.isBlank()) throw ApiException.preconditionRequired("修改资源必须携带 If-Match。");
        var m = ETAG.matcher(value.trim());
        if (!m.matches()) throw ApiException.badRequest("PRECONDITION_INVALID", "If-Match 必须是单个强 ETag。");
        try { return Long.parseLong(m.group(1)); }
        catch (NumberFormatException e) { throw ApiException.badRequest("PRECONDITION_INVALID", "If-Match 版本格式无效。"); }
    }
}
