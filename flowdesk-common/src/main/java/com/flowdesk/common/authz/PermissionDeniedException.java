package com.flowdesk.common.authz;

/**
 * 已通过身份验证的主体缺少所需权限，或超出了其角色允许的操作范围。
 */
public class PermissionDeniedException extends RuntimeException {

    /**
     * 保存错误类别、消息及可选原因，供统一异常处理流程保留失败语义。
     */
    public PermissionDeniedException(String message) {
        super(message);
    }
}
