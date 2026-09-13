package com.flowdesk.common.tenant;

import java.util.Objects;
import java.util.UUID;

/**
 * 一次可信 TenantContext 内的操作者或服务主体（ARCHITECTURE §7.2 受信 ActorRef）。
 *
 * <p>它是不可变、已由受信路径验证的主体引用，不是客户端可编辑的业务输入。
 * 人类主体以 IAM 权威用户标识；服务主体以部署单元名标识；
 * Webhook 主体以唯一通过验签的 MonitoringIntegration 标识。</p>
 */
public sealed interface ActorRef permits ActorRef.Human, ActorRef.Service, ActorRef.Integration {

    /** 人类操作者：IAM 权威用户标识。 */
    record Human(UUID userId) implements ActorRef {
        public Human {
            Objects.requireNonNull(userId, "userId");
        }
    }

    /** 受认证服务主体：部署单元名（如 flowdesk-ticket-service）。 */
    record Service(String serviceId) implements ActorRef {
        public Service {
            Objects.requireNonNull(serviceId, "serviceId");
            if (serviceId.isBlank()) {
                throw new IllegalArgumentException("serviceId must not be blank");
            }
        }
    }

    /** Webhook 集成主体：唯一通过全量验签的 MonitoringIntegration 标识。 */
    record Integration(UUID integrationId) implements ActorRef {
        public Integration {
            Objects.requireNonNull(integrationId, "integrationId");
        }
    }

    /**
     * 以人类主体构建引用。
     *
     * @param userId IAM 权威用户标识
     * @return 人类主体引用
     */
    static ActorRef human(UUID userId) {
        return new Human(userId);
    }

    /**
     * 以服务主体构建引用。
     *
     * @param serviceId 部署单元名
     * @return 服务主体引用
     */
    static ActorRef service(String serviceId) {
        return new Service(serviceId);
    }

    /**
     * 以集成主体构建引用。
     *
     * @param integrationId 唯一通过验签的 MonitoringIntegration 标识
     * @return 集成主体引用
     */
    static ActorRef integration(UUID integrationId) {
        return new Integration(integrationId);
    }
}
