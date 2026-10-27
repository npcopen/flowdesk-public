package com.flowdesk.alert.recovery;

import com.flowdesk.common.web.ApiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 人工恢复 HTTP 契约的无状态辅助校验：原因用于审计，幂等键识别重复命令，强 ETag 保护操作员读取到的版本。
 *
 * <p>具体资源状态能否重放仍由 Service 在事务内裁决；这里仅解析格式，并为版本冲突响应附带当前 ETag。
 */
public final class AlertRecoverySupport {
    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9._:-]{16,128}$"),
            ETAG = Pattern.compile("^\"lv:([0-9]+)\"$");

    /**
     * 禁止直接实例化该无状态工具类；调用方通过其静态方法或嵌套契约模型使用能力。
     */
    private AlertRecoverySupport() {}

    /** 保留原因原文，不做 trim 或规范化，保证审计与幂等意图摘要使用操作员实际提交的同一内容。 */
    public static String reason(String v) {
        if (v == null || v.isBlank() || !validText(v, 1000))
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "RECOVERY_REASON_REQUIRED",
                    "人工重放必须提供 1 到 1000 个字符的原因。",
                    false);
        return v;
    }

    /**
     * 要求并校验人工操作幂等键，只有合法键才可参与稳定审计身份派生。
     */
    public static String key(String v) {
        if (v == null || v.isBlank())
            throw ApiException.badRequest("IDEMPOTENCY_KEY_REQUIRED", "命令必须携带 Idempotency-Key。");
        if (!KEY.matcher(v).matches())
            throw ApiException.badRequest("IDEMPOTENCY_KEY_INVALID", "Idempotency-Key 格式无效。");
        return v;
    }

    /** 仅接受单个强 ETag，拒绝通配符、弱 ETag 和版本列表，保证条件更新对应一个明确版本。 */
    public static long version(String v) {
        if (v == null || v.isBlank()) throw ApiException.preconditionRequired("人工重放必须携带 If-Match。");
        var m = ETAG.matcher(v.trim());
        if (!m.matches())
            throw ApiException.badRequest("PRECONDITION_INVALID", "If-Match 必须是单个强 ETag。");
        try {
            return Long.parseLong(m.group(1));
        } catch (Exception e) {
            throw ApiException.badRequest("PRECONDITION_INVALID", "If-Match 版本无效。");
        }
    }

    /** 按 Unicode 码点限制长度，拒绝断裂代理对及 ISO 控制字符；制表、换行和回车允许用于文本排版。 */
    private static boolean validText(String v, int max) {
        int count = v.codePointCount(0, v.length());
        if (count < 1 || count > max) return false;
        for (int i = 0; i < v.length(); ) {
            char c = v.charAt(i);
            if (Character.isSurrogate(c)) {
                if (!Character.isHighSurrogate(c)
                        || i + 1 >= v.length()
                        || !Character.isLowSurrogate(v.charAt(i + 1))) return false;
                i += 2;
                continue;
            }
            if (c != '\t' && c != '\n' && c != '\r' && Character.isISOControl(c)) return false;
            i++;
        }
        return true;
    }

    /**
     * 将锁版本编码为单个强 ETag，供恢复记录的 If-Match 条件请求使用。
     */
    public static String etag(long v) {
        return "\"lv:" + v + "\"";
    }

    /** 使用 412 表达读取版本已过期，并返回当前版本，便于客户端刷新后重新做人工判断。 */
    public static ApiException versionConflict(long v) {
        return new ApiException(
                org.springframework.http.HttpStatusCode.valueOf(412),
                "RESOURCE_VERSION_CONFLICT",
                "恢复记录已更新，请刷新后重试。",
                false,
                List.of(),
                Map.of(HttpHeaders.ETAG, etag(v)));
    }
}
