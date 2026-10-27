package com.flowdesk.alert.model;
import java.util.*;
import java.time.Instant;

/**
 * 按 API key 选择器找到的集成候选；租户信息只有在凭据、签名、有效期等检查全部通过后才能被信任。
 */
public record WebhookIntegrationCandidate(
            UUID tenantId,
            UUID integrationId,
            String integrationCode,
            String integrationName,
            String status,
            byte[] apiKeyVerifier,
            String verifierScheme,
            int signingVersion,
            Instant validFrom,
            Instant validUntil) {
        /**
         * 构造不可变快照并复制可变字节数组，防止调用方在校验完成后修改原文、摘要或认证事实。
         */
        public WebhookIntegrationCandidate {
            apiKeyVerifier = apiKeyVerifier.clone();
        }

        /**
         * 返回 API key 校验摘要的防御性副本，避免认证候选信息被调用者改写。
         */
        @Override
        public byte[] apiKeyVerifier() {
            return apiKeyVerifier.clone();
        }
    }
