package com.flowdesk.alert.service;

import com.flowdesk.alert.dto.AlertDtos;
import com.flowdesk.alert.webhook.VerifiedWebhook;
import com.flowdesk.alert.webhook.WebhookAlertPayload;
import java.time.Instant;
import java.util.UUID;

/** 业务用例契约；HTTP 层只依赖接口。 */
public interface WebhookAcceptanceService {
    /**
     * 将已验签 Webhook 和解析后的载荷交给接纳用例，返回收据及已登记的异步工作进度。
     */
    AlertDtos.WebhookReceipt accept(VerifiedWebhook verified, WebhookAlertPayload payload);
    /**
     * 扫描并重新裁决历史持久化的等待周期，复用告警接纳逻辑补齐后续工作，不改写原始事件事实。
     */
    void reconcilePersistedCycles(UUID tenantId, String fingerprint, Instant recoveredAt);
}
