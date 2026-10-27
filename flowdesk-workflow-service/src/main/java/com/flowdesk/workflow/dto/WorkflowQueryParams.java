package com.flowdesk.workflow.dto;

import com.flowdesk.common.web.ApiException;
import org.springframework.util.MultiValueMap;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * 校验工作流接口的查询参数、分页和筛选值，拒绝未声明及重复的参数。
 */
public final class WorkflowQueryParams {

    /**
     * 禁止直接实例化工具容器，相关静态规则与转换由调用方按业务需要复用。
     */
    private WorkflowQueryParams() {
    }

    /**
     * 拒绝全部查询参数，保持无筛选接口的严格输入契约。
     */
    public static void none(MultiValueMap<String, String> params) {
        if (!params.isEmpty()) {
            throw ApiException.badRequest("UNKNOWN_QUERY_PARAMETER", "请求包含未声明 query 参数。");
        }
    }

    /**
     * 核验查询字段白名单和单值约束，防止重复参数造成不明确解析。
     */
    public static String single(MultiValueMap<String, String> params, String name, Set<String> allowed) {
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

    /**
     * 解析分页大小并应用默认值，拒绝超出单次查询上限的请求。
     */
    public static int pageSize(String value) {
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

    /**
     * 在 UUID 与持久化表示之间转换，保持标识高低位的编码顺序一致。
     */
    public static UUID uuid(String value) {
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

    /**
     * 解析严格正整数，非法格式或非正值转为查询参数错误。
     */
    public static Integer positiveInteger(String value) {
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

    /**
     * 解析规范时间查询值；缺失时保留为空，非法格式返回参数错误。
     */
    public static Instant instant(String value) {
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

    /**
     * 拒绝未声明查询字段，避免拼写错误或额外条件被静默忽略。
     */
    private static void rejectUnknown(MultiValueMap<String, String> params, Set<String> allowed) {
        for (String key : params.keySet()) {
            if (!allowed.contains(key)) {
                throw ApiException.badRequest("UNKNOWN_QUERY_PARAMETER", "请求包含未声明 query 参数。");
            }
        }
    }
}
