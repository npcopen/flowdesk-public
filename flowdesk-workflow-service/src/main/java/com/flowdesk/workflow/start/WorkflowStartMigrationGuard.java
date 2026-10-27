package com.flowdesk.workflow.start;


/**
 * 在启动 RocketMQ 生命周期前检查历史 IAM 拒绝记录迁移是否完成，避免未收敛数据进入新消费路径。
 */
final class WorkflowStartMigrationGuard {

    /**
     * 检查遗留 IAM 拒绝记录是否已完成接管迁移，仍有残留或查询异常时阻止启动消费入口。
     */
    WorkflowStartMigrationGuard(WorkflowStartMapper mapper) {
        Integer remaining = mapper.countLegacyIamDenials();
        if (remaining == null || remaining != 0) {
            throw new IllegalStateException(
                    "M6 Workflow-start IAM-denial takeover is incomplete");
        }
    }
}
