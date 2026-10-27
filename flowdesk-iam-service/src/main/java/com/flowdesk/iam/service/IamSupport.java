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

/**
 * IAM 用例共用的授权、幂等请求键及 HTTP 乐观并发前置条件检查。
 */
public final class IamSupport {

    static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._:-]{16,128}$");
    private static final Pattern ETAG = Pattern.compile("^\"lv:([0-9]+)\"$");

    /**
     * 禁止直接实例化该无状态工具类；调用方通过其静态方法或嵌套契约模型使用能力。
     */
    private IamSupport() {
    }

    /**
     * 返回当前可信租户上下文，供服务层从认证事实取得租户及请求关联信息。
     */
    public static TenantContext context() {
        return TenantContextHolder.current();
    }

    /**
     * 读取已认证 JWT 主体，供权限检查和操作者识别使用，避免从请求字段推断身份。
     */
    public static JwtPrincipal principal() {
        return JwtPrincipalHolder.current();
    }

    /**
     * 从当前人类认证主体取得用户标识，用于审计及稳定业务身份，不接受请求正文指定操作者。
     */
    public static UUID actorId() {
        return principal().humanUserId();
    }

    /**
     * 检查当前主体具备指定权限，未获授权时立即终止业务处理。
     */
    public static void require(PermissionCode permission) {
        PermissionPolicy.require(context(), principal(), permission);
    }

    /**
     * 检查当前主体至少具备一项指定权限，支持多个业务入口共享同一最小查询能力。
     */
    public static void requireAny(PermissionCode... permissions) {
        PermissionPolicy.requireAny(context(), principal(), permissions);
    }

    /**
     * 判断当前主体是否命中任一给定权限，供查询范围和候选可见性分支使用。
     */
    public static boolean hasAny(PermissionCode... permissions) {
        return PermissionPolicy.hasAny(principal(), permissions);
    }

    /**
     * 解析单个强 ETag 中的锁版本，拒绝弱标签、通配符、列表和非法版本，保证条件更新目标明确。
     */
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
     * 校验新建绑定必须使用的 If-None-Match 条件，避免客户端以更新语义覆盖尚不存在的关系。
     */
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

    /**
     * 核对既有资源的 If-Match 版本及互斥前提；版本落后时返回带当前 ETag 的前提失败响应。
     */
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

    /**
     * 要求命令携带格式合法的幂等键，保留原键值用于稳定业务身份派生。
     */
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

    /**
     * 构造资源版本冲突响应并附带当前 ETag，供客户端刷新资源后重新提交。
     */
    public static ApiException versionConflict(Map<String, String> headers) {
        return new ApiException(org.springframework.http.HttpStatusCode.valueOf(412),
                "RESOURCE_VERSION_CONFLICT", "资源已更新，请刷新后重试。", false,
                java.util.List.of(), headers);
    }

    /**
     * 把数据库锁版本编码为强 ETag 响应头，供后续条件请求使用。
     */
    public static Map<String, String> etagHeaders(long lockVersion) {
        return Map.of(HttpHeaders.ETAG, "\"lv:" + lockVersion + "\"");
    }
}
