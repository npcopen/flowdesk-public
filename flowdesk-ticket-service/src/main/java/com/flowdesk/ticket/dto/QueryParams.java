package com.flowdesk.ticket.dto;

import com.flowdesk.common.web.ApiException;
import org.springframework.util.MultiValueMap;

import java.util.Set;
import java.util.UUID;

/** 严格校验查询参数：拒绝未声明字段、重复值及客户端传入的租户信息。 */
public final class QueryParams {

    /**
     * 私有构造方法，阻止工具类实例化，调用方应使用静态入口。
     */
    private QueryParams() {
    }

    /**
     * 校验参数白名单与重复参数，返回指定参数的唯一值。
     */
    public static String single(MultiValueMap<String, String> params, String name,
                                Set<String> allowed) {
        validate(params, allowed);
        return params.getFirst(name);
    }

    /** 端点契约未声明查询输入时，拒绝所有查询参数。 */
    public static void none(MultiValueMap<String, String> params) {
        validate(params, Set.of());
    }

    /**
     * 校验查询参数白名单和重复项，拒绝未知参数或非法组合。
     */
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

    /**
     * 解析时间文本或转换数据库时间值，遵循空值约定并保留业务精度。
     */
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

    /**
     * 转换业务标识与规范 UUID 字节表示，遵循既有空值与格式校验约定。
     */
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

    /**
     * 解析分页大小，补充默认值并校验单页处理上限。
     */
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
