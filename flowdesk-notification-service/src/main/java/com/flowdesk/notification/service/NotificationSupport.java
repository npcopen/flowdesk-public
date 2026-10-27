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

/** 统一取得通知请求身份、检查权限，并校验写命令的幂等键和强 ETag。 */
public final class NotificationSupport {
    private static final Pattern IDEMPOTENCY = Pattern.compile("^[A-Za-z0-9._:-]{16,128}$");
    private static final Pattern ETAG = Pattern.compile("^\"lv:([0-9]+)\"$");

    /**
     * 禁止直接实例化工具容器，相关静态规则与转换由调用方按业务需要复用。
     */
    private NotificationSupport() {
    }

    /**
     * 取得当前线程的租户上下文，供查询、授权和审计关联使用。
     */
    public static TenantContext context() { return TenantContextHolder.current(); }
    /**
     * 读取当前 JWT 主体，供业务操作确认调用身份。
     */
    public static JwtPrincipal principal() { return JwtPrincipalHolder.current(); }
    /**
     * 取得当前操作者标识，供权限、幂等及审计记录使用。
     */
    public static UUID actorId() { return principal().humanUserId(); }
    /**
     * 检查调用方是否具有指定权限，缺少权限时立即终止业务处理。
     */
    public static void require(PermissionCode permission) { PermissionPolicy.require(context(), principal(), permission); }
    /**
     * 校验幂等键的必填和格式约束，使重试请求拥有稳定操作身份。
     */
    public static String idempotency(String key) {
        if (key == null || key.isBlank()) throw ApiException.badRequest("IDEMPOTENCY_KEY_REQUIRED", "命令必须携带 Idempotency-Key。");
        if (!IDEMPOTENCY.matcher(key).matches()) throw ApiException.badRequest("IDEMPOTENCY_KEY_INVALID", "Idempotency-Key 格式无效。");
        return key;
    }
    /**
     * 解析客户端资源版本，供条件更新识别基于旧快照的操作。
     */
    public static long ifMatch(String value) {
        if (value == null || value.isBlank()) throw ApiException.preconditionRequired("修改资源必须携带 If-Match。");
        var m = ETAG.matcher(value.trim());
        if (!m.matches()) throw ApiException.badRequest("PRECONDITION_INVALID", "If-Match 必须是单个强 ETag。");
        try { return Long.parseLong(m.group(1)); }
        catch (NumberFormatException e) { throw ApiException.badRequest("PRECONDITION_INVALID", "If-Match 版本格式无效。"); }
    }
}
