package com.flowdesk.common.security;

/**
 * JWT 的用途分类，保存在受签名保护的 {@code token_use} 声明中。
 * 签发方设置、验签方验证该声明，客户端修改它会导致签名失效。
 */
public enum JwtTokenType {

    /** IAM 为已认证的人类用户签发的令牌。 */
    HUMAN("human"),

    /** IAM 为已认证的内部服务身份签发的令牌。 */
    SERVICE("service");

    private final String claimValue;

    /**
     * 保存写入 token_use 声明的协议值，供签发与验签使用相同映射。
     */
    JwtTokenType(String claimValue) {
        this.claimValue = claimValue;
    }

    /**
     * 返回 token_use 声明的稳定协议值，使外部协议与 Java 枚举名称解耦。
     */
    public String claimValue() {
        return claimValue;
    }

    /**
     * 根据 token_use 查找身份类型，未知值抛出异常，禁止回退为默认身份。
     */
    public static JwtTokenType fromClaimValue(String value) {
        for (JwtTokenType type : values()) {
            if (type.claimValue.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown JWT token_use: " + value);
    }
}
