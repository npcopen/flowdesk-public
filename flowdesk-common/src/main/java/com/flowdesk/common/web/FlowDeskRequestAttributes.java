package com.flowdesk.common.web;

/** Servlet request attribute names shared by the security filters and controllers. */
public final class FlowDeskRequestAttributes {

    public static final String CORRELATION_ID = FlowDeskRequestAttributes.class.getName() + ".correlationId";
    public static final String JWT_PRINCIPAL = FlowDeskRequestAttributes.class.getName() + ".jwtPrincipal";

    private FlowDeskRequestAttributes() {
    }
}
