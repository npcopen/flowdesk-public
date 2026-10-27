package com.flowdesk.ticket.recovery.impl;

import com.flowdesk.common.web.ApiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.util.Map;
import java.util.regex.Pattern;

/** 人工重放的输入规则，统一原因文本、幂等键、强 ETag 与版本冲突响应。 */
final class TicketRecoverySupport {
    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9._:-]{16,128}$");
    private static final Pattern ETAG = Pattern.compile("^\"lv:([0-9]+)\"$");
    /**
     * 私有构造方法，阻止工具类实例化，调用方应使用静态入口。
     */
    private TicketRecoverySupport() { }
    /**
     * 返回校验或执行原因，供分类与审计使用。
     */
    static String reason(String value) {
        if (value == null || value.isBlank() || !validText(value, 1000)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "RECOVERY_REASON_REQUIRED", "人工重放必须提供 1 到 1000 个字符的原因。", false);
        }
        return value;
    }
    /**
     * 检查文本是否满足非空、长度与字符约束。
     */
    private static boolean validText(String value, int max) {
        int count = value.codePointCount(0, value.length());
        if (count < 1 || count > max) return false;
        for (int i = 0; i < value.length();) {
            char current = value.charAt(i);
            if (Character.isSurrogate(current)) {
                if (!Character.isHighSurrogate(current) || i + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(i + 1))) return false;
                i += 2;
                continue;
            }
            if (current != '\t' && current != '\n' && current != '\r'
                    && Character.isISOControl(current)) return false;
            i++;
        }
        return true;
    }
    /**
     * 构造租户索引键，使相关 Redis 键共享租户哈希槽。
     */
    static String key(String value) {
        if (value == null || value.isBlank()) throw ApiException.badRequest(
                "IDEMPOTENCY_KEY_REQUIRED", "命令必须携带 Idempotency-Key。");
        if (!KEY.matcher(value).matches()) throw ApiException.badRequest(
                "IDEMPOTENCY_KEY_INVALID", "Idempotency-Key 格式无效。");
        return value;
    }
    /**
     * 返回规则或记录版本，供资格与并发核验。
     */
    static long version(String value) {
        if (value == null || value.isBlank()) throw ApiException.preconditionRequired(
                "人工重放必须携带 If-Match。");
        var matcher = ETAG.matcher(value.trim());
        if (!matcher.matches()) throw ApiException.badRequest(
                "PRECONDITION_INVALID", "If-Match 必须是单个强 ETag。");
        try { return Long.parseLong(matcher.group(1)); }
        catch (NumberFormatException invalid) { throw ApiException.badRequest(
                "PRECONDITION_INVALID", "If-Match 版本无效。"); }
    }
    /**
     * 按锁版本构造 HTTP ETag，供后续 If-Match 并发控制。
     */
    static String etag(long version) { return "\"lv:" + version + "\""; }
    /**
     * 构造乐观锁冲突响应，提示客户端重新读取资源当前版本。
     */
    static ApiException versionConflict(long current) {
        return new ApiException(org.springframework.http.HttpStatusCode.valueOf(412),
                "RESOURCE_VERSION_CONFLICT", "恢复记录已更新，请刷新后重试。", false,
                java.util.List.of(), Map.of(HttpHeaders.ETAG, etag(current)));
    }
}
