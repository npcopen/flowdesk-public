package com.flowdesk.iam.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 不透明分页游标的 HMAC 签名配置；各 IAM 实例和重启前后必须共用稳定密钥。
 */
@ConfigurationProperties(prefix = "flowdesk.security.cursor-token")
public class CursorTokenProperties {

    /**
     * Base64 编码的 HMAC-SHA-256 密钥，解码后至少包含 32 个随机字节。
     */
    private String hmacKey;

    /**
     * 返回分页游标的 Base64 HMAC 签名密钥，供业务校验或组件初始化读取。
     */
    public String hmacKey() {
        return hmacKey;
    }

    /**
     * 更新分页游标的 Base64 HMAC 签名密钥，供 Spring 配置绑定及组件初始化使用。
     */
    public void setHmacKey(String hmacKey) {
        this.hmacKey = hmacKey;
    }
}
