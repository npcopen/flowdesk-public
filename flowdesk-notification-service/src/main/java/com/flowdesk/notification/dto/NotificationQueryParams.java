package com.flowdesk.notification.dto;

import com.flowdesk.common.web.ApiException;
import org.springframework.util.MultiValueMap;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** 校验通知接口的参数白名单、单值约束及分页、时间和 UUID 格式。 */
public final class NotificationQueryParams {
    /**
     * 禁止直接实例化工具容器，相关静态规则与转换由调用方按业务需要复用。
     */
    private NotificationQueryParams() {
    }

    /**
     * 检查查询白名单与单值约束，拒绝未定义或重复筛选条件。
     */
    public static void validate(MultiValueMap<String, String> params, Set<String> allowed) {
        for (String key : params.keySet()) {
            if (!allowed.contains(key)) {
                throw ApiException.badRequest("UNKNOWN_QUERY_PARAMETER", "请求包含未声明 query 参数。");
            }
            var values = params.get(key);
            if (values == null || values.size() != 1 || values.get(0) == null
                    || values.get(0).isBlank()) {
                throw ApiException.badRequest("INVALID_QUERY_PARAMETER", key + " 必须恰好出现一次且非空。");
            }
        }
    }

    /**
     * 解析规范时间查询值；缺失时保留为空，非法格式返回参数错误。
     */
    public static Instant instant(String value) {
        if (value == null) return null;
        try { return Instant.parse(value); }
        catch (RuntimeException e) {
            throw ApiException.badRequest("INVALID_QUERY_PARAMETER", "时间参数无效。");
        }
    }

    /**
     * 解析分页大小并应用默认值，拒绝超出单次查询上限的请求。
     */
    public static int pageSize(String value) {
        if (value == null) return 20;
        try {
            int result = Integer.parseInt(value);
            if (result < 1 || result > 100) throw new NumberFormatException();
            return result;
        }
        catch (NumberFormatException e) {
            throw ApiException.badRequest("INVALID_QUERY_PARAMETER", "pageSize 必须为 1..100。");
        }
    }

    /**
     * 在 UUID 与持久化表示之间转换，保持标识高低位的编码顺序一致。
     */
    public static UUID uuid(String value) {
        if (value == null) return null;
        try { return UUID.fromString(value); }
        catch (RuntimeException e) {
            throw ApiException.badRequest("INVALID_QUERY_PARAMETER", "UUID 参数无效。");
        }
    }
}
