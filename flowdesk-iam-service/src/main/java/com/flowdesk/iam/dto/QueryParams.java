package com.flowdesk.iam.dto;

import com.flowdesk.common.web.ApiException;
import org.springframework.util.MultiValueMap;

import java.util.Set;

/**
 * 严格检查查询参数白名单、重复参数和租户输入，防止调用方绕过接口约束。
 */
public final class QueryParams {

    /**
     * 禁止直接实例化该无状态工具类；调用方通过其静态方法或嵌套契约模型使用能力。
     */
    private QueryParams() {
    }

    /**
     * 读取指定查询参数并拒绝同名多值，避免代理和服务对参数取值产生不同解释。
     */
    public static String single(MultiValueMap<String, String> params, String name,
                                Set<String> allowed) {
        validate(params, allowed);
        return params.getFirst(name);
    }

    /**
     * 对未声明查询输入的接口拒绝任何查询参数。
     */
    public static void none(MultiValueMap<String, String> params) {
        validate(params, Set.of());
    }

    /**
     * 检查 IAM 查询参数仅包含当前接口白名单，并拒绝重复值；空白参数仍按具体字段规则处理。
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
     * 解析分页大小并检查允许范围；缺省时使用契约默认值，非法整数或越界值统一返回参数错误。
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
