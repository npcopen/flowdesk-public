package com.flowdesk.ticket.service;

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

/** 工单用例共享的权限、上下文、幂等键及 ETag 前置条件校验工具。 */
public final class TicketSupport {

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._:-]{16,128}$");
    private static final Pattern ETAG = Pattern.compile("^\"lv:([0-9]+)\"$");

    /**
     * 私有构造方法，阻止工具类实例化，调用方应使用静态入口。
     */
    private TicketSupport() {
    }

    /**
     * 获取当前租户上下文，后续读写必须保持该租户边界。
     */
    public static TenantContext context() {
        return TenantContextHolder.current();
    }

    /**
     * 取得已认证用户主体，供权限与用户范围判断。
     */
    public static JwtPrincipal principal() {
        return JwtPrincipalHolder.current();
    }

    /**
     * 返回当前操作主体标识，用于业务归属、审计和幂等键派生。
     */
    public static UUID actorId() {
        return principal().humanUserId();
    }

    /**
     * 校验当前步骤要求的权限或契约条件，不满足时立即拒绝继续处理。
     */
    public static void require(PermissionCode permission) {
        PermissionPolicy.require(context(), principal(), permission);
    }

    /**
     * 要求当前主体至少拥有一个指定权限，不满足时中止业务处理。
     */
    public static void requireAny(PermissionCode... permissions) {
        PermissionPolicy.requireAny(context(), principal(), permissions);
    }

    /**
     * 判断当前主体是否拥有任一指定权限，供可见范围和业务分支选择。
     */
    public static boolean hasAny(PermissionCode... permissions) {
        return PermissionPolicy.hasAny(principal(), permissions);
    }

    /**
     * 校验写请求幂等键格式与长度，返回可用于意图派生的键值。
     */
    public static String requireIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "IDEMPOTENCY_KEY_REQUIRED", "命令必须携带 Idempotency-Key。", false);
        }
        if (!IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "IDEMPOTENCY_KEY_INVALID", "Idempotency-Key 格式无效。", false);
        }
        return key;
    }

    /** 只接受单个强 ETag 中的锁版本；缺失或格式非法时拒绝执行状态变更。 */
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

    /**
     * 拒绝创建请求携带 If-Match，避免将资源版本条件误用于创建。
     */
    public static void rejectIfMatchOnCreate(String ifMatch) {
        if (ifMatch != null && !ifMatch.isBlank()) {
            throw ApiException.badRequest("PRECONDITION_INVALID", "创建命令不接受 If-Match。");
        }
    }

    /**
     * 构造乐观锁冲突响应，提示客户端重新读取资源当前版本。
     */
    public static ApiException versionConflict(Map<String, String> headers) {
        return new ApiException(HttpStatusCode.valueOf(412),
                "RESOURCE_VERSION_CONFLICT", "资源已更新，请刷新后重试。", false,
                java.util.List.of(), headers);
    }

    /**
     * 按当前锁版本构造包含 ETag 的响应头集合。
     */
    public static Map<String, String> etagHeaders(long lockVersion) {
        return Map.of(HttpHeaders.ETAG, "\"lv:" + lockVersion + "\"");
    }

    /**
     * 校验字段属于允许集合，否则返回约定的参数错误。
     */
    public static String requireEnumValue(String value, java.util.Set<String> allowed,
                                          String code, String message) {
        if (value == null) {
            return null;
        }
        if (!allowed.contains(value)) {
            throw ApiException.badRequest(code, message);
        }
        return value;
    }
}
