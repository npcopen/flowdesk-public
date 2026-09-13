package com.flowdesk.workflow.start;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Fails entry activation before any RocketMQ lifecycle can start when the M6
 * historical IAM-denial takeover has not converged.
 */
final class WorkflowStartMigrationGuard {

    WorkflowStartMigrationGuard(JdbcTemplate jdbc) {
        Integer remaining = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM inbox_message
                WHERE status = 'REJECTED'
                  AND last_error_code = 'WORKFLOW_APPROVER_NOT_ELIGIBLE'
                  AND contract_ref = 'flowdesk.workflow.start.v1'
                  AND contract_version = 1
                """, Integer.class);
        if (remaining == null || remaining != 0) {
            throw new IllegalStateException(
                    "M6 Workflow-start IAM-denial takeover is incomplete");
        }
    }
}
