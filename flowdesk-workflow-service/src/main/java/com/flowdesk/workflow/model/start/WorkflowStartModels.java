package com.flowdesk.workflow.model.start;

import java.time.Instant;
import java.util.UUID;
import java.security.MessageDigest;
import com.flowdesk.workflow.start.AcceptedWorkflowStart;

/**
 * 工作流启动共享的不可变事实和决策模型，连接服务契约、事务写入及持久化读取，避免各入口自行解释状态。
 */
public final class WorkflowStartModels {
    /**
     * 禁止直接实例化工具容器，当前类型通过静态方法提供共享规则和转换。
     */
    private WorkflowStartModels() {}
    /**
     * 启动消息已持久化的接纳事实，保留身份、摘要、处理状态与结果，供去重及恢复核验。
     */
    public record InboxRecord(UUID inboxId,
                       UUID messageId,
                       byte[] businessIntentKey,
                       String contractRef,
                       int contractVersion,
                       byte[] bodySha256,
                       String status,
                       String resultType,
                       UUID resultId,
                       long lockVersion) {

        /**
         * 构造不可变快照并防御性复制可变内容，防止外部修改原数组或集合改变已接纳事实。
         */
        public InboxRecord {
            businessIntentKey = businessIntentKey.clone();
            bodySha256 = bodySha256.clone();
        }
        /**
         * 返回业务意图键的防御性副本，避免调用方通过数组修改已保存事实。
         */
        @Override public byte[] businessIntentKey() { return businessIntentKey.clone(); }
        /**
         * 返回消息体摘要的防御性副本，避免调用方通过数组修改已保存事实。
         */
        @Override public byte[] bodySha256() { return bodySha256.clone(); }
    }

    /**
     * 启动 Inbox 配对恢复快照，携带生命周期状态、版本和最近重放链路。
     */
    public record RecoveryRecord(UUID recoveryId,
                          String state,
                          long lockVersion,
                          UUID lastReplayCorrelationId) {
    }

    /**
     * 重复检查分类，区分首次、相同消息、相同业务意图及身份内容冲突。
     */
    public enum DuplicateKind {
        FRESH,
        SAME_MESSAGE,
        SAME_BUSINESS_INTENT,
        CONFLICT
    }

    /**
     * 重复校验结果，组合分类、既有 Inbox 和冲突码供启动及恢复分支判断。
     */
    public record DuplicateCheck(DuplicateKind kind,
                          InboxRecord existing,
                          String conflictCode) {

        /**
         * 构造不存在既有事实的结果，让调用方进入首次业务处理路径。
         */
        public static DuplicateCheck fresh() {
            return new DuplicateCheck(DuplicateKind.FRESH, null, null);
        }

        /**
         * 构造相同消息身份和内容的重复结果，保留既有记录供后续复用。
         */
        public static DuplicateCheck sameMessage(InboxRecord existing) {
            return new DuplicateCheck(DuplicateKind.SAME_MESSAGE, existing, null);
        }

        /**
         * 构造相同业务意图的重复结果，供调用方沿用此前产生的业务事实。
         */
        public static DuplicateCheck sameIntent(InboxRecord existing) {
            return new DuplicateCheck(DuplicateKind.SAME_BUSINESS_INTENT, existing, null);
        }

        /**
         * 构造身份或内容冲突结果，携带错误信息阻止覆盖既有业务事实。
         */
        public static DuplicateCheck conflict(String code, InboxRecord existing) {
            return new DuplicateCheck(DuplicateKind.CONFLICT, existing, code);
        }

        /**
         * 判断是否命中相同消息或业务意图，供终态与恢复分支选择。
         */
        public boolean exactDuplicate() {
            return kind == DuplicateKind.SAME_MESSAGE
                    || kind == DuplicateKind.SAME_BUSINESS_INTENT;
        }

        /**
         * 判断重复记录是否已离开待恢复状态，终态重复直接复用已有结果。
         */
        public boolean terminalDuplicate() {
            return exactDuplicate() && !"RECOVERY_REQUIRED".equals(existing.status());
        }

        /**
         * 判断重复记录是否仍待恢复，后续须同时结算配对的恢复记录。
         */
        public boolean recoverableDuplicate() {
            return exactDuplicate() && "RECOVERY_REQUIRED".equals(existing.status());
        }
    }

    /**
     * 流程版本读取结果，不可用时保留确定性错误码，避免缺失版本进入启动路径。
     */
    public record VersionLookup(VersionSnapshot version, String errorCode) {

        /**
         * 返回或构造有效版本结果，版本存在时允许调用方继续预检，不包含错误码。
         */
        public static VersionLookup valid(VersionSnapshot version) {
            return new VersionLookup(version, null);
        }

        /**
         * 构造携带稳定错误码的版本读取失败结果，避免缺失版本进入启动路径。
         */
        public static VersionLookup invalid(String errorCode) {
            return new VersionLookup(null, errorCode);
        }

        /**
         * 返回或构造有效版本结果，版本存在时允许调用方继续预检，不包含错误码。
         */
        public boolean valid() {
            return version != null;
        }
    }

    /**
     * 已发布规则的不可变快照，供事务外预检与事务内再次核对，防止规则变化后复用旧预检。
     */
    public record VersionSnapshot(UUID workflowVersionId,
                           UUID definitionId,
                           int versionNo,
                           String versionStatus,
                           String definitionStatus,
                           String conditionFieldCode,
                           String conditionOperator,
                           String conditionValueType,
                           String conditionValueText,
                           int approvalLevelCount,
                           UUID level1ApproverUserId,
                           UUID level2ApproverUserId,
                           byte[] snapshotSha256) {

        /**
         * 构造不可变快照并防御性复制可变内容，防止外部修改原数组或集合改变已接纳事实。
         */
        public VersionSnapshot {
            snapshotSha256 = snapshotSha256.clone();
        }

        /**
         * 返回版本快照摘要的防御性副本，避免调用方通过数组修改已保存事实。
         */
        @Override
        public byte[] snapshotSha256() {
            return snapshotSha256.clone();
        }

        /**
         * 根据版本条件与消息风险级别决定是否进入两级人工审批。
         */
        public boolean requiresApproval(AcceptedWorkflowStart message) {
            return conditionValueText.equals(message.serviceRiskLevel());
        }

        /**
         * 逐项核对不可变规则及摘要，防止事务外预检被套用到变化后的版本。
         */
        public boolean sameImmutableSnapshot(VersionSnapshot other) {
            return workflowVersionId.equals(other.workflowVersionId)
                    && definitionId.equals(other.definitionId)
                    && versionNo == other.versionNo
                    && versionStatus.equals(other.versionStatus)
                    && definitionStatus.equals(other.definitionStatus)
                    && conditionFieldCode.equals(other.conditionFieldCode)
                    && conditionOperator.equals(other.conditionOperator)
                    && conditionValueType.equals(other.conditionValueType)
                    && conditionValueText.equals(other.conditionValueText)
                    && approvalLevelCount == other.approvalLevelCount
                    && level1ApproverUserId.equals(other.level1ApproverUserId)
                    && level2ApproverUserId.equals(other.level2ApproverUserId)
                    && MessageDigest.isEqual(snapshotSha256, other.snapshotSha256);
        }
    }

    /**
     * 既有实例的稳定启动事实，供核对工单、意图键、输入摘要与处理结果。
     */
    public record InstanceRecord(UUID workflowInstanceId,
                          UUID ticketId,
                          byte[] startIntentKey,
                          UUID definitionId,
                          UUID workflowVersionId,
                          int workflowVersionNo,
                          byte[] inputSnapshotSha256,
                          boolean conditionResult) {
        /**
         * 构造不可变快照并防御性复制可变内容，防止外部修改原数组或集合改变已接纳事实。
         */
        public InstanceRecord {
            startIntentKey = startIntentKey.clone();
            inputSnapshotSha256 = inputSnapshotSha256.clone();
        }
        /**
         * 返回启动意图键的防御性副本，避免调用方通过数组修改已保存事实。
         */
        @Override public byte[] startIntentKey() { return startIntentKey.clone(); }
        /**
         * 返回输入快照摘要的防御性副本，避免调用方通过数组修改已保存事实。
         */
        @Override public byte[] inputSnapshotSha256() { return inputSnapshotSha256.clone(); }
    }

    /**
     * 既有实例比对分类，决定当前启动应创建、复用还是拒绝。
     */
    public enum ExistingInstanceKind {
        FRESH,
        SAME,
        CONFLICT
    }

    /**
     * 既有实例对比结果，明确首次创建、相同事实复用或内容冲突三种决策。
     */
    public record ExistingInstanceDecision(ExistingInstanceKind kind, UUID workflowInstanceId) {

        /**
         * 构造不存在既有事实的结果，让调用方进入首次业务处理路径。
         */
        public static ExistingInstanceDecision fresh() {
            return new ExistingInstanceDecision(ExistingInstanceKind.FRESH, null);
        }

        /**
         * 构造已有实例内容一致的结果，返回可复用的实例标识。
         */
        public static ExistingInstanceDecision same(UUID workflowInstanceId) {
            return new ExistingInstanceDecision(ExistingInstanceKind.SAME, workflowInstanceId);
        }

        /**
         * 构造身份或内容冲突结果，携带错误信息阻止覆盖既有业务事实。
         */
        public static ExistingInstanceDecision conflict() {
            return new ExistingInstanceDecision(ExistingInstanceKind.CONFLICT, null);
        }
    }
    /**
     * 构造版本和资格预检通过的结果，供本地事务再次核对后提交。
     */
    public static Preflight ready(VersionSnapshot version,
                           UUID eligibleApproverUserId) {
        return new Preflight(PreflightKind.START, version, eligibleApproverUserId, null);
    }
    /**
     * 构造启动预检拒绝结果，保留版本与错误码供记录拒绝事实。
     */
    public static Preflight rejected(VersionSnapshot version,
                              String errorCode) {
        return new Preflight(PreflightKind.REJECT, version, null, errorCode);
    }
    /**
     * 构造只处理重复冲突的预检结果，首次正常启动仍需完成外部预检。
     */
    public static Preflight conflictOnly() {
        return new Preflight(PreflightKind.CONFLICT_ONLY, null, null, null);
    }
    /**
     * 返回资格不足的稳定错误码，供启动预检及拒绝事实保持一致。
     */
    public static String approverNotEligibleCode() {
        return "WORKFLOW_APPROVER_NOT_ELIGIBLE";
    }
    /**
     * 预检决策分类，区分正常启动、明确拒绝和仅处理重复冲突的入口。
     */
    public enum PreflightKind {
        START,
        REJECT,
        CONFLICT_ONLY
    }

    /**
     * 事务外启动预检结果，保存版本快照、已获资格的审批人或确定性拒绝原因。
     */
    public record Preflight(PreflightKind kind,
                     VersionSnapshot version,
                     UUID eligibleApproverUserId,
                     String errorCode) {
    }

    /**
     * 本地启动事务的明确结果，保留业务结果与错误码，供入口决定确认投递或继续预检。
     */
    public record Completion(CompletionKind kind, String code, UUID workflowInstanceId) {

        /**
         * 构造可向 Broker 确认的结果，错误码说明接纳或拒绝的业务原因。
         */
        public static Completion ack(String code, UUID workflowInstanceId) {
            return new Completion(CompletionKind.ACK, code, workflowInstanceId);
        }

        /**
         * 构造重复消息完成结果，复用原结果标识且不再创建副作用。
         */
        public static Completion duplicate(UUID workflowInstanceId) {
            return ack("WORKFLOW_START_DUPLICATE", workflowInstanceId);
        }

        /**
         * 返回需要补做预检的状态，防止在依据不完整时写入实例。
         */
        public static Completion needsPreflight() {
            return new Completion(CompletionKind.NEEDS_PREFLIGHT,
                    "WORKFLOW_START_PREFLIGHT_REQUIRED", null);
        }
    }

    /**
     * 启动事务完成分类，供入口区分可确认结果与尚需补充预检的情况。
     */
    public enum CompletionKind {
        ACK,
        NEEDS_PREFLIGHT
    }
}
