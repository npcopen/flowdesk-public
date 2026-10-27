package com.flowdesk.alert.webhook;

import java.time.Instant;
import java.util.UUID;

/**
 * 保存一次请求通过集成凭据、HMAC 签名及时间窗口校验后的事实，由认证过滤器传给控制器和接纳服务。
 *
 * <p>tenantId 与 integrationId 来自唯一通过认证的数据库候选，不采用客户端自报的租户身份。
 * timestampText 保留参与签名的原始时间文本，requestTimestamp 用于接纳事务的 nonce 记录；
 * rawBody 交给控制器解析，rawBodySha256 供后续事件冲突裁决使用，二者都不重新从请求流读取。
 *
 * <p>该快照表示身份已验证，尚不表示 nonce、事件或故障变更已被数据库接纳。业务租户上下文由接纳服务
 * 在相应约束确认后建立。构造和访问时复制字节数组，保证后续代码不能改写已校验的报文或摘要。
 */
public record VerifiedWebhook(
        UUID tenantId,
        UUID integrationId,
        String integrationCode,
        String integrationName,
        String timestampText,
        Instant requestTimestamp,
        String nonce,
        UUID correlationId,
        byte[] rawBody,
        byte[] rawBodySha256) {
    /**
     * 构造不可变快照并复制可变字节数组，防止调用方在校验完成后修改原文、摘要或认证事实。
     */
    public VerifiedWebhook {
        // 隔离调用方传入的可变数组；否则校验后再修改原数组会改变控制器看到的内容。
        rawBody = rawBody.clone();
        rawBodySha256 = rawBodySha256.clone();
    }

    /**
     * 返回已验签原始请求正文的防御性副本，供业务解析与持久化复用同一报文字节。
     */
    @Override
    public byte[] rawBody() {
        return rawBody.clone();
    }

    /**
     * 返回已验签正文摘要的防御性副本，保证事件幂等核对与验签时的原文一致。
     */
    @Override
    public byte[] rawBodySha256() {
        return rawBodySha256.clone();
    }
}
