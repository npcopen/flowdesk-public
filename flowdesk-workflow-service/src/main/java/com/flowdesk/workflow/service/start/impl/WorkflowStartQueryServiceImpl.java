package com.flowdesk.workflow.service.start.impl;
import com.flowdesk.workflow.start.*;
import com.flowdesk.workflow.model.start.WorkflowStartModels.*;
import com.flowdesk.workflow.service.start.*;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;

/**
 * 工作流业务契约实现，协调声明的持久化与外部依赖，并在明确事务边界内维护业务事实一致性。
 */
public final class WorkflowStartQueryServiceImpl implements WorkflowStartQueryService {
    private static final String MESSAGE_CONFLICT = "MESSAGE_ID_CONTENT_CONFLICT";
    private static final String INTENT_CONFLICT = "BUSINESS_INTENT_CONTENT_CONFLICT";
    private static final String VERSION_REFERENCE_INVALID = "WORKFLOW_VERSION_REFERENCE_INVALID";
    private static final String VERSION_STATE_INVALID = "WORKFLOW_VERSION_STATE_INVALID";
    private static final String VERSION_RULE_INVALID = "WORKFLOW_VERSION_RULE_INVALID";
    private static final String INSTANCE_CONFLICT = "WORKFLOW_INSTANCE_CONTENT_CONFLICT";

    private final WorkflowStartMapper mapper;
    /**
     * 注入启动读取映射，为消息去重和版本预检提供同一持久化事实来源。
     */
    public WorkflowStartQueryServiceImpl(WorkflowStartMapper mapper) { this.mapper = mapper; }
    /**
     * 按声明的租户与业务条件查询消息重复事实，供调用方核对可见性、状态或幂等关系。
     */
    public DuplicateCheck findDuplicate(AcceptedWorkflowStart message) {
        List<InboxRecord> byMessage = mapper.findInboxByMessage(uuid(message.tenantId()), message.producerService(), uuid(message.messageId()),
                false);
        if (!byMessage.isEmpty()) {
            InboxRecord existing = byMessage.get(0);
            return sameContent(existing, message)
                    ? DuplicateCheck.sameMessage(existing)
                    : DuplicateCheck.conflict(MESSAGE_CONFLICT, existing);
        }

        List<InboxRecord> byIntent = mapper.findInboxByIntent(uuid(message.tenantId()), message.producerService(), message.contractRef(),
                message.businessIntentKey(), false);
        if (!byIntent.isEmpty()) {
            InboxRecord existing = byIntent.get(0);
            return sameContent(existing, message)
                    ? DuplicateCheck.sameIntent(existing)
                    : DuplicateCheck.conflict(INTENT_CONFLICT, existing);
        }
        return DuplicateCheck.fresh();
    }
    /**
     * 按声明的租户与业务条件查询流程版本，供调用方核对可见性、状态或幂等关系。
     */
    public VersionLookup findVersion(AcceptedWorkflowStart message) {
        List<VersionSnapshot> rows = mapper.findVersion(uuid(message.tenantId()), uuid(message.workflowVersionId()),
                uuid(message.workflowDefinitionId()), message.versionNo());
        if (rows.isEmpty()) {
            return VersionLookup.invalid(VERSION_REFERENCE_INVALID);
        }
        VersionSnapshot version = rows.get(0);
        if (!"PUBLISHED".equals(version.versionStatus())
                || !"ACTIVE".equals(version.definitionStatus())) {
            return VersionLookup.invalid(VERSION_STATE_INVALID);
        }
        if (!"SERVICE_RISK_LEVEL".equals(version.conditionFieldCode())
                || !"EQUALS".equals(version.conditionOperator())
                || !"STRING".equals(version.conditionValueType())
                || !("NORMAL".equals(version.conditionValueText())
                || "HIGH".equals(version.conditionValueText()))
                || version.approvalLevelCount() != 2
                || version.snapshotSha256().length != 32) {
            return VersionLookup.invalid(VERSION_RULE_INVALID);
        }
        return VersionLookup.valid(version);
    }
    /**
     * 比较契约、意图键与内容摘要，区分安全重复投递和身份内容冲突。
     */
    private static boolean sameContent(InboxRecord existing, AcceptedWorkflowStart incoming) {
        return existing.contractRef().equals(incoming.contractRef())
                && existing.contractVersion() == incoming.contractVersion()
                && MessageDigest.isEqual(existing.businessIntentKey(), incoming.businessIntentKey())
                && MessageDigest.isEqual(existing.bodySha256(), incoming.bodySha256());
    }
    /**
     * 在 UUID 与持久化表示之间转换，保持标识高低位的编码顺序一致。
     */
    private static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
    /**
     * 在 UUID 与持久化表示之间转换，保持标识高低位的编码顺序一致。
     */
    private static byte[] uuid(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }
}
