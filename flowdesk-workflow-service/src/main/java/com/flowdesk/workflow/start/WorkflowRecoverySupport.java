package com.flowdesk.workflow.start;

import com.flowdesk.common.web.ApiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import java.util.Map;
import java.util.regex.Pattern;

/** Strict API-060 header and reason validation. */
final class WorkflowRecoverySupport {

    private static final Pattern IDEMPOTENCY_KEY =
            Pattern.compile("^[A-Za-z0-9._:-]{16,128}$");
    private static final Pattern ETAG = Pattern.compile("^\"lv:([0-9]+)\"$");

    private WorkflowRecoverySupport() {
    }

    static String requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 1000) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "RECOVERY_REASON_REQUIRED",
                    "人工重放必须提供 1 到 1000 个字符的非空原因。", false);
        }
        for (int i = 0; i < reason.length(); i++) {
            char value = reason.charAt(i);
            if (Character.isISOControl(value) && value != '\n' && value != '\r' && value != '\t') {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "RECOVERY_REASON_REQUIRED", "人工重放原因包含不允许的控制字符。", false);
            }
        }
        return reason;
    }

    static String requireIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw ApiException.badRequest(
                    "IDEMPOTENCY_KEY_REQUIRED", "命令必须携带 Idempotency-Key。");
        }
        if (!IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw ApiException.badRequest(
                    "IDEMPOTENCY_KEY_INVALID", "Idempotency-Key 格式无效。");
        }
        return key;
    }

    static long parseIfMatch(String header) {
        if (header == null || header.isBlank()) {
            throw ApiException.preconditionRequired("人工重放必须携带 If-Match。");
        }
        var matcher = ETAG.matcher(header.trim());
        if (!matcher.matches()) {
            throw ApiException.badRequest("PRECONDITION_INVALID", "If-Match 必须是单个强 ETag。");
        }
        try {
            return Long.parseLong(matcher.group(1));
        }
        catch (NumberFormatException invalidVersion) {
            throw ApiException.badRequest("PRECONDITION_INVALID", "If-Match 版本格式无效。");
        }
    }

    static ApiException versionConflict(long currentVersion) {
        return new ApiException(HttpStatusCode.valueOf(412),
                "RESOURCE_VERSION_CONFLICT", "恢复记录已更新，请刷新后重试。", false,
                java.util.List.of(), Map.of(HttpHeaders.ETAG, etag(currentVersion)));
    }

    static String etag(long lockVersion) {
        return "\"lv:" + lockVersion + "\"";
    }
}
