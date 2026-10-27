package com.flowdesk.ticket.iam;

import java.time.Instant;
import java.util.UUID;

/** SLA 升级通知的资格校验端口，向 IAM API-016 核实具体 ServiceDeskAdmin 用户。 */
public interface SlaTargetQualificationChecker {

    /**
     * 核验目标用户的服务台管理员资格，供 SLA 升级收件人校验。
     */
    Qualification checkServiceDeskAdmin(UUID tenantId, UUID userId);

    /**
     * IAM 资格结果快照，保留是否具备管理员资格及规则版本、有效期。
     */
    record Qualification(UUID subjectUserId, boolean eligible, String reasonCode,
                         long qualificationVersion, Instant checkedAt) {
        /**
         * 构造IAM 资格记录，并执行当前值对象所要求的输入校验。
         */
        public Qualification {
            if (subjectUserId == null || reasonCode == null || reasonCode.isBlank()
                    || qualificationVersion <= 0 || checkedAt == null) {
                throw new IllegalArgumentException("Invalid IAM qualification result");
            }
        }
    }
}
