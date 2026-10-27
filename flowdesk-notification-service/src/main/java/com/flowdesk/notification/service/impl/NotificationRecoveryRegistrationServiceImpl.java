package com.flowdesk.notification.service.impl;
import com.flowdesk.notification.service.NotificationRecoveryRegistrationService;
import com.flowdesk.notification.mapper.*;
import com.flowdesk.notification.model.*;
import com.flowdesk.notification.event.NotificationContract;
import com.flowdesk.common.notification.NotificationWireCodec;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import static com.flowdesk.notification.service.NotificationPersistenceSupport.*;
/**
 * 通知业务契约实现，协调声明的持久化与外部依赖，并在明确事务边界内维护业务事实一致性。
 */
@Service
public final class NotificationRecoveryRegistrationServiceImpl implements NotificationRecoveryRegistrationService {
    private final NotificationRecoveryMapper mapper;
    private final NotificationInboxMapper inboxMapper;
    /**
     * 注入恢复登记及 Inbox 映射，用原来源事实核验恢复配对并持久化待人工处理记录。
     */
    public NotificationRecoveryRegistrationServiceImpl(@org.springframework.beans.factory.annotation.Qualifier("notificationRecoveryMapper") NotificationRecoveryMapper mapper, NotificationInboxMapper inboxMapper) {
        this.mapper=mapper; this.inboxMapper=inboxMapper;
    }
    /**
     * 持久化恢复记录，沿用稳定身份与业务事实，原子性由上层事务保证。
     */
    public void insertRecovery(UUID tenantId, UUID recoveryId, UUID inboxId,
                               byte[] businessIntent, String errorCode, String summary,
                               Instant now) {
        requireTenant(tenantId);
        requireTransaction();
        UUID expectedRecoveryId = uuidFromKey(NotificationWireCodec.deriveKey(
                "FD-NOTIFICATION-RECOVERY-V1", tenantId, inboxId));
        if (!expectedRecoveryId.equals(recoveryId)) {
            throw new IllegalStateException("Notification Recovery identity is not deterministic");
        }
        NotificationInboxRow source = inboxMapper.findById(tenantId, inboxId, true).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Notification Recovery source Inbox is missing"));
        if (!"RECOVERY_REQUIRED".equals(source.status())
                || !Arrays.equals(businessIntent, source.businessIntentKey())) {
            throw new IllegalStateException("Notification Recovery source/content conflict");
        }
        try {
            mapper.insertRecovery(tenantId, recoveryId, inboxId, businessIntent, NotificationContract.DELIVERY_ATTEMPTS, errorCode, summary, now);
        }
        catch (DuplicateKeyException duplicate) {
            // 死信重复投递可能争用确定性恢复 ID 或唯一原消息键；读取已提交记录并比对不可变内容后才吸收重复，冲突必须保留为失败。
            NotificationRecoveryRow existing = mapper.findRecoveryBySource(tenantId, inboxId, true).stream().findFirst()
                    .orElseThrow(() -> duplicate);
            if (!expectedRecoveryId.equals(existing.recoveryId())
                    || !"INBOX".equals(existing.direction())
                    || !inboxId.equals(existing.sourceRecordId())
                    || !Arrays.equals(existing.businessIntentKey(), businessIntent)
                    || existing.attemptCount() != NotificationContract.DELIVERY_ATTEMPTS
                    || !Objects.equals(existing.lastErrorCode(), errorCode)
                    || !Objects.equals(existing.lastErrorSummary(), summary)
                    || !java.util.Set.of("OPEN", "REPLAYING", "RESOLVED").contains(existing.state())) {
                throw new IllegalStateException("Notification Recovery identity/content conflict", duplicate);
            }
        }
    }
}
