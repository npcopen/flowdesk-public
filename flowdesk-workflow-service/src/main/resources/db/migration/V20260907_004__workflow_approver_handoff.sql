CREATE TABLE workflow_approver_override (
    tenant_id BINARY(16) NOT NULL,
    workflow_instance_id BINARY(16) NOT NULL,
    level_no INT NOT NULL,
    approver_user_id BINARY(16) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (tenant_id,workflow_instance_id,level_no),
    CONSTRAINT ck_wf_override_level CHECK (level_no IN (1,2)),
    CONSTRAINT fk_wf_override_instance FOREIGN KEY (tenant_id,workflow_instance_id)
        REFERENCES workflow_instance (tenant_id,workflow_instance_id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

CREATE TABLE workflow_approver_change (
    tenant_id BINARY(16) NOT NULL,
    change_id BINARY(16) NOT NULL,
    workflow_instance_id BINARY(16) NOT NULL,
    level_no INT NOT NULL,
    previous_approver_user_id BINARY(16) NOT NULL,
    approver_user_id BINARY(16) NOT NULL,
    changed_by_user_id BINARY(16) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    request_digest VARCHAR(64) NOT NULL,
    changed_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (tenant_id,change_id),
    KEY idx_wf_change_instance (tenant_id,workflow_instance_id,changed_at,change_id),
    CONSTRAINT ck_wf_change_level CHECK (level_no IN (1,2)),
    CONSTRAINT fk_wf_change_instance FOREIGN KEY (tenant_id,workflow_instance_id)
        REFERENCES workflow_instance (tenant_id,workflow_instance_id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

DROP TRIGGER trg_wf_task_before_update;
DELIMITER $$
CREATE TRIGGER trg_wf_task_before_update BEFORE UPDATE ON approval_task FOR EACH ROW
BEGIN
    IF NOT (NEW.tenant_id <=> OLD.tenant_id)
       OR NOT (NEW.approval_task_id <=> OLD.approval_task_id)
       OR NOT (NEW.workflow_instance_id <=> OLD.workflow_instance_id)
       OR NOT (NEW.level_no <=> OLD.level_no)
       OR NOT (NEW.created_at <=> OLD.created_at)
       OR NOT (NEW.created_by_type <=> OLD.created_by_type)
       OR NOT (NEW.created_by_id <=> OLD.created_by_id) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'approval task identity is immutable';
    END IF;
    IF OLD.status <> 'PENDING' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'approval task is terminal';
    END IF;
    IF NEW.status = 'PENDING' THEN
        IF NEW.approver_user_id = OLD.approver_user_id OR NEW.lock_version <> OLD.lock_version + 1
           OR NEW.updated_by_type <> 'USER' OR NEW.updated_by_id IS NULL
           OR NOT EXISTS (SELECT 1 FROM workflow_approver_change c
              WHERE c.tenant_id=NEW.tenant_id AND c.workflow_instance_id=NEW.workflow_instance_id
                AND c.level_no=NEW.level_no AND c.previous_approver_user_id=OLD.approver_user_id
                AND c.approver_user_id=NEW.approver_user_id AND c.changed_by_user_id=NEW.updated_by_id
                AND c.changed_at=NEW.updated_at) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'approval handoff requires a recorded change';
        END IF;
    ELSEIF NEW.status NOT IN ('APPROVED','REJECTED')
        OR NEW.approver_user_id <> OLD.approver_user_id THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'invalid approval task transition';
    END IF;
END$$
CREATE TRIGGER trg_wf_approver_change_bu BEFORE UPDATE ON workflow_approver_change FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'approval handoff history is immutable';
END$$
CREATE TRIGGER trg_wf_approver_change_bd BEFORE DELETE ON workflow_approver_change FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'approval handoff history is immutable';
END$$
DELIMITER ;
