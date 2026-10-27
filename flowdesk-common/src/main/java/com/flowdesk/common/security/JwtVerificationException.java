package com.flowdesk.common.security;

/**
 * JWT 未通过密码学或协议校验时抛出的异常。
 * {@link Kind} 只用于内部分类和观测；公开 API 返回固定错误码，避免暴露具体校验细节。
 */
public class JwtVerificationException extends RuntimeException {

    /**
     * JWT 校验失败原因枚举，区分格式、算法、签名、时间、身份类型及声明错误。
     */
    public enum Kind {
        MALFORMED,
        ALGORITHM_REJECTED,
        SIGNATURE_INVALID,
        ISSUER_REJECTED,
        AUDIENCE_REJECTED,
        EXPIRED,
        TYPE_REJECTED,
        CLAIMS_INVALID
    }

    private final Kind kind;

    /**
     * 保存错误类别、消息及可选原因，供统一异常处理流程保留失败语义。
     */
    public JwtVerificationException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    /**
     * 保存错误类别、消息及可选原因，供统一异常处理流程保留失败语义。
     */
    public JwtVerificationException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    /**
     * 返回结构化验证失败类别，供认证入口映射过期、类型错误等对外错误码。
     */
    public Kind kind() {
        return kind;
    }
}
