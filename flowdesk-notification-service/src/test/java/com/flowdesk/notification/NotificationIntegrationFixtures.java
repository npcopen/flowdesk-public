package com.flowdesk.notification;

import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.authz.RoleCode;
import com.flowdesk.common.notification.NotificationEventContract;
import com.flowdesk.common.notification.NotificationWireCodec;
import com.flowdesk.common.security.JwtIssuer;
import com.flowdesk.notification.event.NotificationTransportMessage;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.nio.ByteBuffer;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 冻结测试样例或传输快照，保留原始身份、字节和摘要供正常及错误变体场景复用。
 */
final class NotificationIntegrationFixtures {
    static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID USER_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID USER_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    static final UUID ADMIN_A = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    private static final KeyPair KEY_PAIR = newKeyPair();
    private static final JwtIssuer ISSUER = new JwtIssuer(KEY_PAIR.getPrivate(),
            "flowdesk-iam-service", "flowdesk-api", "flowdesk-internal", 900, 300);

    /**
     * 初始化组件依赖与运行参数，使后续调用使用一致的协作对象。
     */
    private NotificationIntegrationFixtures() {
    }

    /**
     * 注册测试专用数据库、密钥和开关配置，使应用上下文使用隔离依赖。
     */
    static void properties(DynamicPropertyRegistry registry, String database) {
        String url = "jdbc:h2:mem:" + database
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        registry.add("flowdesk.security.jwt.public-key-pem", NotificationIntegrationFixtures::publicKeyPem);
        registry.add("FLOWDESK_NOTIFICATION_DB_PASSWORD", () -> "");
        registry.add("flowdesk.notification.cursor-token.hmac-key", () ->
                "0123456789abcdef0123456789abcdef");
        registry.add("spring.cloud.service-registry.auto-registration.enabled", () -> "false");
        registry.add("spring.cloud.nacos.discovery.enabled", () -> "false");
        registry.add("spring.cloud.nacos.discovery.register-enabled", () -> "false");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.schema-locations", () -> "classpath:schema-test.sql");
        registry.add("flowdesk.notification.recovery.datasource.url", () -> url);
        registry.add("flowdesk.notification.recovery.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("flowdesk.notification.recovery.datasource.username", () -> "sa");
        registry.add("flowdesk.notification.recovery.datasource.password", () -> "");
    }

    /**
     * 签发当前场景需要的测试身份令牌，固定租户、角色与最小权限供真实认证路径校验。
     */
    static String token(UUID user, RoleCode role, PermissionCode... permissions) {
        return ISSUER.issueHuman(TENANT_A, user, Set.of(role), Set.of(permissions)).token();
    }

    /**
     * 签发当前场景需要的测试身份令牌，固定租户、角色与最小权限供真实认证路径校验。
     */
    static String userToken(UUID user) {
        return token(user, RoleCode.REQUESTER, PermissionCode.NOTIFICATION_READ_SELF,
                PermissionCode.NOTIFICATION_WRITE_SELF);
    }

    /**
     * 签发当前场景需要的测试身份令牌，固定租户、角色与最小权限供真实认证路径校验。
     */
    static String recoveryAdminToken() {
        return token(ADMIN_A, RoleCode.SERVICE_DESK_ADMIN, PermissionCode.RECOVERY_READ_TENANT,
                PermissionCode.RECOVERY_REPLAY_TENANT);
    }

    /**
     * 按固定绑定构造带完整身份和摘要的测试投递，供消费入口验证。
     */
    static Delivery delivery(NotificationEventContract.Binding binding, UUID recipient,
                             UUID sourceId, String kind, String title) {
        byte[] sourceEffect = NotificationWireCodec.deriveKey("FD-NOTIFICATION-TEST-SOURCE-V1",
                binding.contractRef(), sourceId);
        NotificationWireCodec.Payload payload = new NotificationWireCodec.Payload("1", recipient,
                kind, NotificationWireCodec.hex(sourceEffect), "TICKET", sourceId, title, "测试正文");
        byte[] body = NotificationWireCodec.encodePayload(payload);
        byte[] intent = NotificationWireCodec.deriveKey("FD-NOTIFICATION-INTENT-V1", TENANT_A,
                sourceEffect, recipient, kind);
        byte[] publication = NotificationWireCodec.deriveKey("FD-NOTIFICATION-PUBLICATION-V1",
                TENANT_A, sourceEffect, recipient, kind);
        UUID messageId = NotificationWireCodec.uuidFromKey(publication);
        Map<String, String> properties = NotificationWireCodec.envelope(binding, TENANT_A, messageId,
                intent, publication, sourceId,
                UUID.fromString("44444444-4444-4444-4444-444444444444"), null,
                Instant.parse("2026-08-30T00:00:00.000000Z"), body);
        NotificationTransportMessage main = new NotificationTransportMessage(binding.topic(),
                binding.consumerGroup(), messageId.toString(), null, body, properties);
        NotificationTransportMessage dlq = new NotificationTransportMessage(binding.dlqTopic(),
                binding.dlqAcceptorGroup(), messageId.toString(), null, body, properties);
        return new Delivery(messageId, sourceId, body, properties, main, dlq);
    }

    /**
     * 将 UUID 编码为数据库使用的二进制值，空标识保持为空。
     */
    static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }

    /**
     * 在 UUID 与持久化表示之间转换，保持标识高低位的编码顺序一致。
     */
    static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    /**
     * 生成仅供测试签发与验签的 RSA 密钥对，使身份流程无需外部密钥文件。
     */
    private static KeyPair newKeyPair() {
        try {
            return KeyPairGenerator.getInstance("RSA").generateKeyPair();
        }
        catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    /**
     * 将测试公钥编码为 PEM，供应用配置中的实际验签器加载。
     */
    private static String publicKeyPem() {
        String nl = System.lineSeparator();
        String encoded = Base64.getEncoder().encodeToString(KEY_PAIR.getPublic().getEncoded());
        return "-----BEGIN PUBLIC KEY-----" + nl + encoded + nl + "-----END PUBLIC KEY-----";
    }

    /**
     * 冻结测试样例或传输快照，保留原始身份、字节和摘要供正常及错误变体场景复用。
     */
    record Delivery(UUID messageId, UUID sourceId, byte[] body, Map<String, String> properties,
                    NotificationTransportMessage main, NotificationTransportMessage dlq) {
    }
}
