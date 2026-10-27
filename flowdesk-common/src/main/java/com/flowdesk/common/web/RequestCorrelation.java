package com.flowdesk.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

/**
 * 读取由 {@link CorrelationIdFilter} 写入当前请求作用域的追踪关联标识。
 */
public final class RequestCorrelation {

    /**
     * 禁止直接实例化该工具类型，统一通过静态方法或工厂入口使用。
     */
    private RequestCorrelation() {
    }

    /**
     * 从当前 Servlet 请求属性读取 UUID，无请求作用域或未设置时生成新标识。
     */
    public static UUID current() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            Object value = attributes.getRequest().getAttribute(FlowDeskRequestAttributes.CORRELATION_ID);
            if (value instanceof UUID uuid) {
                return uuid;
            }
        }
        return UUID.randomUUID();
    }

    /**
     * 只读取给定请求已有的关联 UUID，缺失返回 null，由调用方决定是否生成。
     */
    public static UUID currentOrNull(HttpServletRequest request) {
        Object value = request.getAttribute(FlowDeskRequestAttributes.CORRELATION_ID);
        return value instanceof UUID uuid ? uuid : null;
    }
}
