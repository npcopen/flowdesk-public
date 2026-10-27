package com.flowdesk.alert.dto;

import com.flowdesk.common.web.ApiException;
import org.springframework.util.MultiValueMap;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * 查询接口共用的参数格式校验，仅负责白名单、单值、非空以及基础类型转换。
 *
 * <p>同一参数多次出现时直接拒绝，避免网关和应用选取不同值；未知参数也拒绝，
 * 让拼写错误及时暴露。事件类型是否合法、时间范围先后等业务语义由查询服务继续校验。
 */
public final class AlertQueryParams {
    /**
     * 禁止直接实例化该无状态工具类；调用方通过其静态方法或嵌套契约模型使用能力。
     */
    private AlertQueryParams() {}

    /**
     * 校验告警列表查询参数白名单并拒绝同名多值，使筛选与游标作用域只有一种解释。
     */
    public static void validate(MultiValueMap<String, String> params, Set<String> allowed) {
        for (String key : params.keySet()) {
            if (!allowed.contains(key))
                throw ApiException.badRequest("UNKNOWN_QUERY_PARAMETER", "请求包含未声明 query 参数。");
            var values = params.get(key);
            if (values == null
                    || values.size() != 1
                    || values.get(0) == null
                    || values.get(0).isBlank())
                throw ApiException.badRequest("INVALID_QUERY_PARAMETER", key + " 必须恰好出现一次且非空。");
        }
    }

    /**
     * 将受校验时间文本转换为 Instant，格式错误使用当前接口约定的参数或契约错误处理。
     */
    public static Instant instant(String value) {
        if (value == null) return null;
        try {
            return Instant.parse(value);
        } catch (RuntimeException invalid) {
            throw ApiException.badRequest("INVALID_QUERY_PARAMETER", "时间参数无效。");
        }
    }

    /**
     * 把固定长度二进制或受校验文本转换为 UUID，保持消息与数据库身份映射一致。
     */
    public static UUID uuid(String value) {
        if (value == null) return null;
        try {
            return UUID.fromString(value);
        } catch (RuntimeException invalid) {
            throw ApiException.badRequest("INVALID_QUERY_PARAMETER", "UUID 参数无效。");
        }
    }

    /**
     * 解析分页大小并检查允许范围；缺省时使用契约默认值，非法整数或越界值统一返回参数错误。
     */
    public static int pageSize(String value) {
        if (value == null) return 20;
        try {
            int size = Integer.parseInt(value);
            if (size < 1 || size > 100) throw new NumberFormatException();
            return size;
        } catch (NumberFormatException invalid) {
            throw ApiException.badRequest("INVALID_QUERY_PARAMETER", "pageSize 必须为 1..100。");
        }
    }
}
