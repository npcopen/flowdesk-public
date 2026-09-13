package com.flowdesk.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

/** Accessor for the request-scoped correlation id installed by {@link CorrelationIdFilter}. */
public final class RequestCorrelation {

    private RequestCorrelation() {
    }

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

    public static UUID currentOrNull(HttpServletRequest request) {
        Object value = request.getAttribute(FlowDeskRequestAttributes.CORRELATION_ID);
        return value instanceof UUID uuid ? uuid : null;
    }
}
