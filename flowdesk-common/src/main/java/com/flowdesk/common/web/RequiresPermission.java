package com.flowdesk.common.web;

import com.flowdesk.common.authz.PermissionCode;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the permission(s) a controller method requires. All listed permissions are
 * required. Endpoints with several alternative roles/permissions should use
 * {@link com.flowdesk.common.authz.PermissionPolicy#requireAny} directly.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {

    PermissionCode[] value();
}
