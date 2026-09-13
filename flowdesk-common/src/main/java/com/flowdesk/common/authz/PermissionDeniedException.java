package com.flowdesk.common.authz;

/**
 * A verified principal is missing one of the required permissions/role ceilings.
 */
public class PermissionDeniedException extends RuntimeException {

    public PermissionDeniedException(String message) {
        super(message);
    }
}
