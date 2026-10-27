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

/**
 * 工作流 HTTP 用例共用的授权、幂等请求键和乐观并发检查。
 */
public final class WorkflowSupport {

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._:-]{16,128}$");
    private static final Pattern ETAG = Pattern.compile("^\"lv:([0-9]+)\"$");

    /**
     * 禁止直接实例化工具容器，相关静态规则与转换由调用方按业务需要复用。
     */
    private WorkflowSupport() {
    }

    /**
     * 取得当前线程的租户上下文，供查询、授权和审计关联使用。
     */
    public static TenantContext context() {
        return TenantContextHolder.current();
    }

    /**
     * 读取当前 JWT 主体，供业务操作确认调用身份。
     */
    public static JwtPrincipal principal() {
        return JwtPrincipalHolder.current();
    }

    /**
     * 取得当前操作者标识，供权限、幂等及审计记录使用。
     */
    public static UUID actorId() {
        return principal().humanUserId();
    }

    /**
     * 检查调用方是否具有指定权限，缺少权限时立即终止业务处理。
     */
    public static void require(PermissionCode permission) {
        PermissionPolicy.require(context(), principal(), permission);
    }

    /**
     * 要求调用方至少具有给定权限之一，避免无可见范围的业务读取。
     */
    public static void requireAny(PermissionCode... permissions) {
        PermissionPolicy.requireAny(context(), principal(), permissions);
    }

    /**
     * 判断当前主体是否具有指定权限之一，供选择查询范围。
     */
    public static boolean hasAny(PermissionCode... permissions) {
        return PermissionPolicy.hasAny(principal(), permissions);
    }

    /**
     * 验证请求幂等键并返回其规范值，供业务身份及重复意图校验使用。
     */
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

    /**
     * 解析 If-Match 的锁版本，缺失或格式错误时拒绝写入以保持并发保护。
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
        }
        catch (NumberFormatException invalid) {
            throw ApiException.badRequest("PRECONDITION_INVALID", "If-Match 版本格式无效。");
        }
    }

    /**
     * 拒绝创建接口不支持的 If-Match 条件，避免混淆创建与资源更新的语义。
     */
    public static void rejectIfMatchOnCreate(String ifMatch) {
        if (ifMatch != null && !ifMatch.isBlank()) {
            throw ApiException.badRequest("PRECONDITION_INVALID", "创建命令不接受 If-Match。");
        }
    }

    /**
     * 构造资源版本冲突响应，并携带当前版本信息供客户端刷新后重试。
     */
    public static ApiException versionConflict(long currentVersion) {
        return new ApiException(HttpStatusCode.valueOf(412), "RESOURCE_VERSION_CONFLICT",
                "资源已更新，请刷新后重试。", false, java.util.List.of(),
                Map.of(HttpHeaders.ETAG, etag(currentVersion)));
    }

    /**
     * 将锁版本转换为统一 ETag，供后续条件写入传回服务端。
     */
    public static String etag(long lockVersion) {
        return "\"lv:" + lockVersion + "\"";
    }
}
