package com.flowdesk.common.web;

import com.flowdesk.common.authz.PermissionCode;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明控制器所需的权限，列表中的权限必须全部满足。
 * 允许多种角色或权限择一通过的接口应直接使用 {@link com.flowdesk.common.authz.PermissionPolicy#requireAny}。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {

    /**
     * 声明目标控制器或方法要求的权限集合，由权限拦截器在业务执行前检查。
     */
    PermissionCode[] value();
}
