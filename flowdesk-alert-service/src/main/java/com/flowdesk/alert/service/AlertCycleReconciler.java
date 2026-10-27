package com.flowdesk.alert.service;
import com.flowdesk.alert.model.*;
import com.flowdesk.alert.mapper.AlertPersistenceSupport;

import com.flowdesk.common.tenant.*;
import com.flowdesk.alert.mapper.AlertCycleMapper;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.UUID;

/**
 * 后台补偿任务：为已经接纳、但因同一指纹存在活动故障而等待的告警推进后续周期。
 *
 * <p>扫描只挑选“已有恢复历史、当前无活动故障、仍有未归属 FIRING”的指纹，
 * 每轮最多 100 个候选。真正的周期裁决交给 WebhookAcceptanceService，
 * 由它在事务内重查数据库，避免扫描到执行之间的并发变化造成重复建单。
 */
@Component
@EnableScheduling
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "flowdesk.alert.webhook.enabled",
        havingValue = "true")
public class AlertCycleReconciler {
    private final AlertCycleMapper mapper;
    private final WebhookAcceptanceService service;

    /**
     * 注入等待周期候选查询及 Webhook 接纳用例，让定期扫描复用已有事务裁决而不直接改写故障事实。
     */
    public AlertCycleReconciler(AlertCycleMapper mapper, WebhookAcceptanceService service) {
        this.mapper = mapper;
        this.service = service;
    }

    /**
     * 有界读取等待协调的告警周期候选，并逐条恢复相应租户上下文，调用接纳用例重新裁决已持久化事实。
     */
    @Scheduled(
            fixedDelayString = "${flowdesk.alert.reconciliation.delay-ms:30000}",
            initialDelayString = "${flowdesk.alert.reconciliation.initial-delay-ms:30000}")
    public void scan() {
        // 这里是跨租户候选扫描；租户身份来自数据库中的已有事实，逐项处理时再建立上下文。
        var candidates =
                mapper.findCandidates();
        for (var candidate : candidates) {
            try (var scope =
                    TenantContextScope.open(
                            TenantContext.ofPersistedTask(
                                    candidate.tenant(),
                                    ActorRef.service("flowdesk-alert-service"),
                                    UUID.randomUUID()))) {
                service.reconcilePersistedCycles(
                        candidate.tenant(), candidate.fingerprint(), candidate.recoveredAt());
            } catch (RuntimeException failure) {
                org.slf4j.LoggerFactory.getLogger(getClass())
                        .warn("Waiting alert cycle reconciliation will retry", failure);
            }
        }
    }


}
