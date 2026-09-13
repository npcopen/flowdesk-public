package com.flowdesk.workflow.api;

import com.flowdesk.common.web.ApiException;
import org.springframework.util.MultiValueMap;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

final class WorkflowQueryParams {

    private WorkflowQueryParams() {
    }

    static void none(MultiValueMap<String, String> params) {
        if (!params.isEmpty()) {
            throw ApiException.badRequest("UNKNOWN_QUERY_PARAMETER", "请求包含未声明 query 参数。");
        }
    }

    static String single(MultiValueMap<String, String> params, String name, Set<String> allowed) {
        rejectUnknown(params, allowed);
        var values = params.get(name);
        if (values == null) {
            return null;
        }
        if (values.size() != 1 || values.get(0) == null || values.get(0).isBlank()) {
            throw ApiException.badRequest("INVALID_QUERY_PARAMETER", name + " 必须出现一次且非空。");
        }
        return values.get(0);
    }

    static int pageSize(String value) {
        if (value == null) {
            return 20;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1 || parsed > 100) {
                throw new NumberFormatException();
            }
            return parsed;
        }
        catch (NumberFormatException invalid) {
            throw ApiException.badRequest("INVALID_QUERY_PARAMETER", "pageSize 必须为 1..100。");
        }
    }

    static UUID uuid(String value) {
        if (value == null) {
            return null;
        }
        try {
            UUID result = UUID.fromString(value);
            if (!result.toString().equals(value)) {
                throw new IllegalArgumentException();
            }
            return result;
        }
        catch (IllegalArgumentException invalid) {
            throw ApiException.badRequest("INVALID_QUERY_PARAMETER", "UUID query 参数无效。");
        }
    }

    static Integer positiveInteger(String value) {
        if (value == null) {
            return null;
        }
        try {
            int result = Integer.parseInt(value);
            if (result <= 0) {
                throw new NumberFormatException();
            }
            return result;
        }
        catch (NumberFormatException invalid) {
            throw ApiException.badRequest("INVALID_QUERY_PARAMETER", "整数 query 参数无效。");
        }
    }

    static Instant instant(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        }
        catch (Exception invalid) {
            throw ApiException.badRequest("INVALID_QUERY_PARAMETER", "时间 query 参数无效。");
        }
    }

    private static void rejectUnknown(MultiValueMap<String, String> params, Set<String> allowed) {
        for (String key : params.keySet()) {
            if (!allowed.contains(key)) {
                throw ApiException.badRequest("UNKNOWN_QUERY_PARAMETER", "请求包含未声明 query 参数。");
            }
        }
    }
}
