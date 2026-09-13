package com.flowdesk.common.web;

import java.io.IOException;

/** Raised while reading a request body that exceeds the API-SPEC size limit. */
public class RequestBodyTooLargeException extends IOException {

    private final long limitBytes;

    public RequestBodyTooLargeException(long limitBytes) {
        super("Request body exceeds " + limitBytes + " bytes");
        this.limitBytes = limitBytes;
    }

    public long limitBytes() {
        return limitBytes;
    }
}
