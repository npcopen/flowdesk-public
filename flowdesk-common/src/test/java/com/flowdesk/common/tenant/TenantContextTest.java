package com.flowdesk.common.tenant;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TenantContext 最小语义测试（ARCHITECTURE §7.1）。
 *
 * <p>覆盖：信任根与来源类型、禁止默认租户、不可变、correlationId 不参与授权、
 * 跨对象引用与当前租户一致性。</p>
 */
class TenantContextTest {

    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID USER_1 = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID CORR_1 = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID CORR_2 = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    /**
     * 验证人类 JWT 上下文正确保存租户、用户操作者、认证来源及关联标识。
     */
    @Test
    void humanJwtCarriesTenantActorAndSource() {
        TenantContext context = TenantContext.ofHumanJwt(TENANT_A, USER_1, CORR_1);

        assertThat(context.tenantId()).isEqualTo(TENANT_A);
        assertThat(context.actor()).isEqualTo(new ActorRef.Human(USER_1));
        assertThat(context.source()).isEqualTo(TenantContextSource.HUMAN_JWT);
        assertThat(context.correlationId()).isEqualTo(CORR_1);
        assertThat(context.source().isHuman()).isTrue();
    }

    /**
     * 验证服务 JWT 上下文使用服务操作者及对应来源，避免与人类身份混淆。
     */
    @Test
    void serviceJwtCarriesServiceActorAndSource() {
        TenantContext context = TenantContext.ofServiceJwt(TENANT_A, "flowdesk-ticket-service", CORR_1);

        assertThat(context.actor()).isEqualTo(new ActorRef.Service("flowdesk-ticket-service"));
        assertThat(context.source()).isEqualTo(TenantContextSource.SERVICE_JWT);
        assertThat(context.source().isService()).isTrue();
    }

    /**
     * 验证 Webhook 上下文绑定集成操作者与已认证来源。
     */
    @Test
    void webhookCarriesIntegrationActorAndSource() {
        UUID integrationId = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
        TenantContext context = TenantContext.ofWebhook(TENANT_A, integrationId, CORR_1);

        assertThat(context.actor()).isEqualTo(new ActorRef.Integration(integrationId));
        assertThat(context.source()).isEqualTo(TenantContextSource.WEBHOOK);
        assertThat(context.source().isIntegration()).isTrue();
    }

    /**
     * 验证消息上下文保留已通过信封校验的租户、生产者及关联语义。
     */
    @Test
    void validatedBrokerMessageCarriesGuardedEnvelopeSemantics() {
        TenantContext context = TenantContext.ofValidatedBrokerMessage(
                TENANT_A, "flowdesk-ticket-service", CORR_1);

        assertThat(context.tenantId()).isEqualTo(TENANT_A);
        assertThat(context.actor()).isEqualTo(new ActorRef.Service("flowdesk-ticket-service"));
        assertThat(context.source()).isEqualTo(TenantContextSource.BROKER_MESSAGE);
        assertThat(context.source().isBrokerMessage()).isTrue();
        assertThat(context.source().isService()).isFalse();
        assertThat(context.correlationId()).isEqualTo(CORR_1);
        // 工厂只构造已验证值的快照；消息消费者仍必须显式打开单次处理作用域。
        assertThat(TenantContextHolder.currentOrNull()).isNull();
    }

    /**
     * 验证持久化任务只能使用已确认的操作者身份构造可信上下文。
     */
    @Test
    void persistedTaskAcceptsVerifiedActor() {
        TenantContext context = TenantContext.ofPersistedTask(TENANT_A, ActorRef.human(USER_1), CORR_1);

        assertThat(context.source()).isEqualTo(TenantContextSource.PERSISTED_TASK);
        assertThat(context.source().isPersistedTask()).isTrue();
    }

    /**
     * 验证认证来源与操作者类别不兼容时拒绝创建上下文。
     */
    @Test
    void incompatibleActorForSourceIsRejected() {
        // Webhook 来源不允许携带人类主体：伪造集成主体被拒绝。
        assertThatThrownBy(() -> TenantContextSource.WEBHOOK.requireCompatible(ActorRef.human(USER_1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not compatible");

        // 人类 JWT 来源不允许携带服务主体。
        assertThatThrownBy(() -> TenantContextSource.HUMAN_JWT.requireCompatible(ActorRef.service("flowdesk-ticket-service")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not compatible");

        // 服务 JWT 来源不允许携带集成主体。
        UUID integrationId = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
        assertThatThrownBy(() -> TenantContextSource.SERVICE_JWT.requireCompatible(ActorRef.integration(integrationId)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not compatible");

        // Broker 消息来源只能代表 guard 已验证的生产者服务，不能冒充人类或集成主体。
        assertThatThrownBy(() -> TenantContextSource.BROKER_MESSAGE.requireCompatible(ActorRef.human(USER_1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not compatible");
        assertThatThrownBy(() -> TenantContextSource.BROKER_MESSAGE.requireCompatible(ActorRef.integration(integrationId)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not compatible");
    }

    /**
     * 验证消息校验结果缺少必填身份或追踪值时不能建立可信上下文。
     */
    @Test
    void validatedBrokerMessageRejectsMissingGuardOutputs() {
        assertThatThrownBy(() -> TenantContext.ofValidatedBrokerMessage(
                null, "flowdesk-ticket-service", CORR_1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> TenantContext.ofValidatedBrokerMessage(TENANT_A, null, CORR_1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> TenantContext.ofValidatedBrokerMessage(TENANT_A, "   ", CORR_1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("serviceId must not be blank");
        assertThatThrownBy(() -> TenantContext.ofValidatedBrokerMessage(
                TENANT_A, "flowdesk-ticket-service", null))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * 验证租户标识为 null 时直接拒绝，避免后续数据库访问失去租户边界。
     */
    @Test
    void nullTenantIsRejected() {
        // 禁止默认租户：任何工厂都不接受缺失租户。
        assertThatThrownBy(() -> TenantContext.ofHumanJwt(null, USER_1, CORR_1))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * 验证可信上下文必须携带请求关联标识，保证处理和审计可追踪。
     */
    @Test
    void nullCorrelationIdIsRejected() {
        assertThatThrownBy(() -> TenantContext.ofHumanJwt(TENANT_A, USER_1, null))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * 验证关联标识仅用于追踪，不改变租户及操作者的授权语义。
     */
    @Test
    void correlationIdDoesNotParticipateInAuthorization() {
        TenantContext withCorr1 = TenantContext.ofHumanJwt(TENANT_A, USER_1, CORR_1);
        TenantContext withCorr2 = TenantContext.ofHumanJwt(TENANT_A, USER_1, CORR_2);

        // correlationId 只用于追踪：相同的 tenant+actor、不同 correlationId 的上下文
        // 在授权语义上等价，不因 correlationId 不同而成为不同主体。
        assertThat(withCorr1.tenantId()).isEqualTo(withCorr2.tenantId());
        assertThat(withCorr1.actor()).isEqualTo(withCorr2.actor());
        assertThat(withCorr1.source()).isEqualTo(withCorr2.source());
        assertThat(withCorr1.correlationId()).isNotEqualTo(withCorr2.correlationId());
    }

    /**
     * 验证目标租户与当前上下文一致时租户一致性检查通过。
     */
    @Test
    void tenantConsistencyCheckPassesForMatchingTenant() {
        TenantContext context = TenantContext.ofHumanJwt(TENANT_A, USER_1, CORR_1);
        context.requireTenant(TENANT_A); // 不抛异常即通过
    }

    /**
     * 验证跨租户目标立即触发一致性错误，不能继续执行内部数据访问。
     */
    @Test
    void tenantConsistencyCheckRejectsDifferentTenant() {
        TenantContext context = TenantContext.ofHumanJwt(TENANT_A, USER_1, CORR_1);

        assertThatThrownBy(() -> context.requireTenant(TENANT_B))
                .isInstanceOf(TenantContextMismatchException.class);
    }

    /**
     * 验证上下文持有器在无当前租户时失败，避免调用者获得隐式默认值。
     */
    @Test
    void holderRequiresCurrentTenant() {
        TenantContext context = TenantContext.ofHumanJwt(TENANT_A, USER_1, CORR_1);
        try (TenantContextScope ignored = TenantContextScope.open(context)) {
            TenantContexts.requireCurrentTenant(TENANT_A); // 不抛异常即通过
        }
        assertThatThrownBy(() -> TenantContexts.requireCurrentTenant(TENANT_A))
                .isInstanceOf(NoTenantContextException.class);
    }

    /**
     * 验证上下文字符串仅展示允许诊断的信息，不泄露认证秘密。
     */
    @Test
    void toStringDoesNotLeakSecrets() {
        TenantContext context = TenantContext.ofHumanJwt(TENANT_A, USER_1, CORR_1);
        String rendered = context.toString();
        assertThat(rendered).contains(TENANT_A.toString()).doesNotContain("password");
    }
}
