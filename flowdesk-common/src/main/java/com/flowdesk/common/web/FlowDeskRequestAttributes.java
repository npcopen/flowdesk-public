package com.flowdesk.common.web;

/**
 * 安全过滤器与控制器共用的 Servlet 请求属性名。
 */
public final class FlowDeskRequestAttributes {

    public static final String CORRELATION_ID = FlowDeskRequestAttributes.class.getName() + ".correlationId";
    public static final String JWT_PRINCIPAL = FlowDeskRequestAttributes.class.getName() + ".jwtPrincipal";

    /**
     * 禁止直接实例化该工具类型，统一通过静态方法或工厂入口使用。
     */
    private FlowDeskRequestAttributes() {
    }
}
