package com.flowdesk.ticket.sla;

import com.flowdesk.common.tenant.TenantContextHolder;

import java.time.Instant;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * SLA 升级通知对象的保守启用门禁，只有映射唯一、已生效且资格快照有效时才放行。
 * 对外仅返回具体收件人，不将角色名当作通知目标。
 */
public final class SlaTargetConfigurationGate {
    private final SlaTargetPersistenceMapper mapper;
    private final SlaTargetQualificationService qualification;
    private final Clock clock;

    /**
     * 构造SLA组件并保存调用依赖；支持注入时钟以控制时间边界。
     */
    public SlaTargetConfigurationGate(SlaTargetPersistenceMapper mapper, SlaTargetQualificationService qualification,
                                      Clock clock) {
        this.mapper = mapper;
        this.qualification = qualification;
        this.clock = clock;
    }

    /** 仅当映射唯一、已到生效时刻且资格快照有效时返回具体收件人，否则保持门禁关闭。 */
    public GateResult evaluate(UUID tenantId, Instant at, boolean lock) {
        if (tenantId == null) return GateResult.closed("SLA_TARGET_TENANT_REQUIRED");
        TenantContextHolder.current().requireTenant(tenantId);
        Instant now = (at == null ? clock.instant() : at).truncatedTo(ChronoUnit.MICROS);
        Integer count = mapper.countActiveSlaEscalationTargetsSelect(com.flowdesk.ticket.persistence.Uuids.toBytes(tenantId));
        if (count == null || count != 1) return GateResult.closed("SLA_TARGET_MISSING_OR_DUPLICATE");
        if (lock) com.flowdesk.ticket.persistence.SlaPersistenceSupport.requireTransaction();
        var target = mapper.findActiveSlaEscalationTargetSelect(
                com.flowdesk.ticket.persistence.Uuids.toBytes(tenantId), lock).stream().findFirst().orElse(null);
        if (target == null || target.effectiveAt() == null || target.effectiveAt().isAfter(now)) {
            return GateResult.closed("SLA_TARGET_NOT_EFFECTIVE");
        }
        com.flowdesk.ticket.model.sla.Validation result = qualification.validate(tenantId, now, lock);
        return result.eligible()
                ? GateResult.open(result.recipientUserId(), result.qualificationVersion())
                : GateResult.closed(result.reasonCode());
    }

    /**
     * SLA 配置门裁决结果，说明当前目标是否满足继续处理条件。
     */
    public record GateResult(boolean open, UUID recipientUserId,
                             Long qualificationVersion, String reasonCode) {
        /**
         * 创建允许继续执行的配置门结果，表示目标条件已满足。
         */
        public static GateResult open(UUID recipient, Long version) {
            return new GateResult(true, recipient, version, "ELIGIBLE");
        }
        /**
         * 创建阻止继续执行的配置门结果，并保留未满足原因。
         */
        public static GateResult closed(String reason) {
            return new GateResult(false, null, null,
                    reason == null ? "SLA_TARGET_GATE_CLOSED" : reason);
        }
    }
}
