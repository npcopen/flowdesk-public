package com.flowdesk.ticket.api;

import com.flowdesk.common.web.ApiException;
import org.springframework.util.MultiValueMap;

import java.util.Set;
import java.util.UUID;

/** Strict query-string validation: undeclared/duplicate/tenant input is rejected. */
public final class QueryParams {

    private QueryParams() {
    }

    public static String single(MultiValueMap<String, String> params, String name,
                                Set<String> allowed) {
        validate(params, allowed);
        return params.getFirst(name);
    }

    /** Rejects every query parameter for endpoints whose contract declares no query input. */
    public static void none(MultiValueMap<String, String> params) {
        validate(params, Set.of());
    }

    private static void validate(MultiValueMap<String, String> params, Set<String> allowed) {
        if (params == null) {
            return;
        }
        if (!params.keySet().isEmpty() && !allowed.containsAll(params.keySet())) {
            throw ApiException.badRequest("INVALID_QUERY_PARAMETER", "包含未声明 query 参数。");
        }
        params.forEach((key, values) -> {
            if (values != null && values.size() > 1) {
                throw ApiException.badRequest("INVALID_QUERY_PARAMETER",
                        "query 参数重复提交：" + key + "。");
            }
            if (values == null || values.isEmpty() || values.get(0) == null
                    || values.get(0).isBlank()) {
                throw ApiException.badRequest("INVALID_QUERY_PARAMETER",
                        "query 参数不能为空：" + key + "。");
            }
        });
        if (params.containsKey("tenantId") || params.containsKey("tenant_id")) {
            throw ApiException.badRequest("TENANT_INPUT_FORBIDDEN", "禁止提交 tenantId。");
        }
    }

    public static java.time.Instant instant(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return java.time.Instant.parse(raw);
        } catch (java.time.format.DateTimeParseException e) {
            throw ApiException.badRequest("INVALID_QUERY_PARAMETER", "时间参数必须是 UTC Instant。");
        }
    }

    public static UUID uuid(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_QUERY_PARAMETER", "query 参数不是合法 UUID。");
        }
    }

    public static int pageSize(String raw) {
        if (raw == null) {
            return 20;
        }
        try {
            int value = Integer.parseInt(raw);
            if (value < 1 || value > 100) {
                throw new NumberFormatException("range");
            }
            return value;
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("INVALID_QUERY_PARAMETER", "pageSize 必须在 1..100。");
        }
    }
}
