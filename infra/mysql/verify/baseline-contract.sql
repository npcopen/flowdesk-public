-- Fail-fast structural assertions for the clean FlowDesk MySQL baseline.
USE flowdesk_gateway;

DELIMITER //
CREATE PROCEDURE assert_equal(
    IN assertion_name VARCHAR(64),
    IN actual_value BIGINT,
    IN expected_value BIGINT
)
BEGIN
    IF actual_value <> expected_value THEN
        SET @assertion_message = CONCAT(
            assertion_name, ': expected ', expected_value, ', got ', actual_value
        );
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = @assertion_message;
    END IF;
END//
DELIMITER ;

SELECT COUNT(*) INTO @actual
FROM information_schema.TABLES
WHERE TABLE_SCHEMA IN ('flowdesk_iam','flowdesk_ticket','flowdesk_workflow','flowdesk_alert','flowdesk_notification')
  AND TABLE_NAME <> 'flyway_schema_history';
CALL assert_equal('application tables', @actual, 50);

SELECT COUNT(*) INTO @actual
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA IN ('flowdesk_iam','flowdesk_ticket','flowdesk_workflow','flowdesk_alert','flowdesk_notification')
  AND TABLE_NAME <> 'flyway_schema_history';
CALL assert_equal('application columns', @actual, 852);

SELECT COUNT(*) INTO @actual
FROM (
    SELECT TABLE_SCHEMA, TABLE_NAME, INDEX_NAME
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA IN ('flowdesk_iam','flowdesk_ticket','flowdesk_workflow','flowdesk_alert','flowdesk_notification')
      AND TABLE_NAME <> 'flyway_schema_history'
    GROUP BY TABLE_SCHEMA, TABLE_NAME, INDEX_NAME
) AS physical_indexes;
CALL assert_equal('application indexes', @actual, 192);

SELECT COUNT(*) INTO @actual
FROM information_schema.TABLE_CONSTRAINTS
WHERE TABLE_SCHEMA IN ('flowdesk_iam','flowdesk_ticket','flowdesk_workflow','flowdesk_alert','flowdesk_notification')
  AND TABLE_NAME <> 'flyway_schema_history'
  AND CONSTRAINT_TYPE = 'PRIMARY KEY';
CALL assert_equal('primary keys', @actual, 50);

SELECT COUNT(*) INTO @actual
FROM information_schema.TABLE_CONSTRAINTS
WHERE TABLE_SCHEMA IN ('flowdesk_iam','flowdesk_ticket','flowdesk_workflow','flowdesk_alert','flowdesk_notification')
  AND TABLE_NAME <> 'flyway_schema_history'
  AND CONSTRAINT_TYPE = 'UNIQUE';
CALL assert_equal('unique constraints', @actual, 50);

SELECT COUNT(*) INTO @actual
FROM information_schema.TABLE_CONSTRAINTS
WHERE TABLE_SCHEMA IN ('flowdesk_iam','flowdesk_ticket','flowdesk_workflow','flowdesk_alert','flowdesk_notification')
  AND TABLE_NAME <> 'flyway_schema_history'
  AND CONSTRAINT_TYPE = 'FOREIGN KEY';
CALL assert_equal('foreign keys', @actual, 28);

SELECT COUNT(*) INTO @actual
FROM information_schema.TABLE_CONSTRAINTS
WHERE TABLE_SCHEMA IN ('flowdesk_iam','flowdesk_ticket','flowdesk_workflow','flowdesk_alert','flowdesk_notification')
  AND TABLE_NAME <> 'flyway_schema_history'
  AND CONSTRAINT_TYPE = 'CHECK';
CALL assert_equal('check constraints', @actual, 89);

SELECT COUNT(*) INTO @actual
FROM information_schema.TRIGGERS
WHERE TRIGGER_SCHEMA IN ('flowdesk_iam','flowdesk_ticket','flowdesk_workflow','flowdesk_alert','flowdesk_notification');
CALL assert_equal('state guards', @actual, 12);

SELECT COUNT(*) INTO @actual
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'flowdesk_gateway';
CALL assert_equal('gateway tables', @actual, 0);

SELECT COUNT(*) INTO @actual
FROM information_schema.TABLES
WHERE TABLE_SCHEMA IN ('flowdesk_iam','flowdesk_ticket','flowdesk_workflow','flowdesk_alert','flowdesk_notification')
  AND TABLE_NAME = 'flyway_schema_history';
CALL assert_equal('flyway history tables', @actual, 5);

SELECT COUNT(*) INTO @actual
FROM information_schema.TABLE_CONSTRAINTS
WHERE TABLE_SCHEMA IN ('flowdesk_iam','flowdesk_ticket','flowdesk_workflow','flowdesk_alert','flowdesk_notification')
  AND TABLE_NAME <> 'flyway_schema_history'
  AND CONSTRAINT_TYPE = 'CHECK'
  AND ENFORCED = 'YES';
CALL assert_equal('enforced checks', @actual, 89);

SELECT COUNT(*) INTO @actual
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'flowdesk_workflow'
  AND TABLE_NAME = 'inbox_message'
  AND (
      (COLUMN_NAME = 'body_bytes'
          AND DATA_TYPE = 'longblob'
          AND IS_NULLABLE = 'NO'
          AND COLUMN_DEFAULT IS NULL)
      OR (COLUMN_NAME = 'correlation_id'
          AND COLUMN_TYPE = 'binary(16)'
          AND IS_NULLABLE = 'NO'
          AND COLUMN_DEFAULT IS NULL)
      OR (COLUMN_NAME = 'causation_id'
          AND COLUMN_TYPE = 'binary(16)'
          AND IS_NULLABLE = 'YES'
          AND COLUMN_DEFAULT IS NULL)
      OR (COLUMN_NAME = 'occurred_at'
          AND DATA_TYPE = 'timestamp'
          AND DATETIME_PRECISION = 6
          AND IS_NULLABLE = 'NO'
          AND COLUMN_DEFAULT IS NULL)
  );
CALL assert_equal('workflow inbox replay source columns', @actual, 4);

SELECT COUNT(*) INTO @actual
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'flowdesk_workflow'
  AND TABLE_NAME = 'message_recovery'
  AND COLUMN_NAME = 'last_replay_correlation_id'
  AND COLUMN_TYPE = 'binary(16)'
  AND IS_NULLABLE = 'YES'
  AND COLUMN_DEFAULT IS NULL;
CALL assert_equal('workflow replay correlation column', @actual, 1);

SELECT COUNT(*) INTO @actual
FROM information_schema.TABLE_CONSTRAINTS tc
JOIN information_schema.CHECK_CONSTRAINTS cc
  ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA
 AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
WHERE tc.CONSTRAINT_SCHEMA = 'flowdesk_workflow'
  AND tc.TABLE_NAME = 'message_recovery'
  AND tc.CONSTRAINT_NAME = 'ck_wf_recovery_replay_correlation'
  AND tc.CONSTRAINT_TYPE = 'CHECK'
  AND tc.ENFORCED = 'YES'
  AND LOWER(REPLACE(cc.CHECK_CLAUSE, '`', '')) =
      '(((replay_count = 0) and (last_replay_correlation_id is null)) or ((replay_count > 0) and (last_replay_correlation_id is not null)))';
CALL assert_equal('workflow replay correlation check', @actual, 1);

SELECT COUNT(*) INTO @actual
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'flowdesk_ticket'
  AND TABLE_NAME = 'inbox_message'
  AND (
      (COLUMN_NAME = 'body_bytes'
          AND DATA_TYPE = 'longblob'
          AND IS_NULLABLE = 'NO'
          AND COLUMN_DEFAULT IS NULL)
      OR (COLUMN_NAME = 'correlation_id'
          AND COLUMN_TYPE = 'binary(16)'
          AND IS_NULLABLE = 'NO'
          AND COLUMN_DEFAULT IS NULL)
      OR (COLUMN_NAME = 'causation_id'
          AND COLUMN_TYPE = 'binary(16)'
          AND IS_NULLABLE = 'YES'
          AND COLUMN_DEFAULT IS NULL)
      OR (COLUMN_NAME = 'occurred_at'
          AND DATA_TYPE = 'timestamp'
          AND DATETIME_PRECISION = 6
          AND IS_NULLABLE = 'NO'
          AND COLUMN_DEFAULT IS NULL)
  );
CALL assert_equal('ticket inbox replay source columns', @actual, 4);

SELECT COUNT(*) INTO @actual
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'flowdesk_ticket'
  AND TABLE_NAME = 'message_recovery'
  AND COLUMN_NAME = 'last_replay_correlation_id'
  AND COLUMN_TYPE = 'binary(16)'
  AND IS_NULLABLE = 'YES'
  AND COLUMN_DEFAULT IS NULL;
CALL assert_equal('ticket replay correlation column', @actual, 1);

SELECT COUNT(*) INTO @actual
FROM information_schema.TABLE_CONSTRAINTS tc
JOIN information_schema.CHECK_CONSTRAINTS cc
  ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA
 AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
WHERE tc.CONSTRAINT_SCHEMA = 'flowdesk_ticket'
  AND tc.TABLE_NAME = 'message_recovery'
  AND tc.CONSTRAINT_NAME = 'ck_tkt_recovery_replay_correlation'
  AND tc.CONSTRAINT_TYPE = 'CHECK'
  AND tc.ENFORCED = 'YES'
  AND LOWER(REPLACE(cc.CHECK_CLAUSE, '`', '')) =
      '(((replay_count = 0) and (last_replay_correlation_id is null)) or ((replay_count > 0) and (last_replay_correlation_id is not null)))';
CALL assert_equal('ticket replay correlation check', @actual, 1);

SELECT COUNT(*) INTO @actual
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'flowdesk_ticket'
  AND TABLE_NAME = 'ticket_number_counter'
  AND COLUMN_NAME IN ('tenant_id','business_date','sequence_value','created_at','updated_at');
CALL assert_equal('ticket number counter columns', @actual, 5);

SELECT COUNT(*) INTO @actual
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = 'flowdesk_ticket'
  AND TABLE_NAME = 'ticket_number_counter'
  AND INDEX_NAME = 'PRIMARY'
  AND COLUMN_NAME IN ('tenant_id','business_date');
CALL assert_equal('ticket number counter primary key columns', @actual, 2);

SELECT COUNT(*) INTO @actual
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'flowdesk_alert'
  AND TABLE_NAME = 'alert_event'
  AND COLUMN_NAME = 'alert_cycle_ref'
  AND COLUMN_TYPE = 'varchar(128)'
  AND CHARACTER_SET_NAME = 'utf8mb4'
  AND COLLATION_NAME = 'utf8mb4_0900_bin'
  AND IS_NULLABLE = 'NO';
CALL assert_equal('alert cycle reference column', @actual, 1);

SELECT COUNT(*) INTO @actual
FROM (
    SELECT INDEX_NAME
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = 'flowdesk_alert'
      AND TABLE_NAME = 'alert_event'
      AND INDEX_NAME = 'idx_alt_m7_cycle'
      AND NON_UNIQUE = 1
      AND INDEX_TYPE = 'BTREE'
    GROUP BY INDEX_NAME
    HAVING COUNT(*) = 5
       AND GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',') =
           'tenant_id,fingerprint,alert_cycle_ref,received_at,alert_event_id'
) AS alert_cycle_indexes;
CALL assert_equal('alert cycle index', @actual, 1);

SELECT COUNT(*) INTO @actual
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'flowdesk_alert'
  AND TABLE_NAME = 'inbox_message'
  AND (
      (COLUMN_NAME = 'body_bytes'
          AND DATA_TYPE = 'longblob'
          AND IS_NULLABLE = 'NO'
          AND COLUMN_DEFAULT IS NULL)
      OR (COLUMN_NAME = 'correlation_id'
          AND COLUMN_TYPE = 'binary(16)'
          AND IS_NULLABLE = 'NO'
          AND COLUMN_DEFAULT IS NULL)
      OR (COLUMN_NAME = 'causation_id'
          AND COLUMN_TYPE = 'binary(16)'
          AND IS_NULLABLE = 'YES'
          AND COLUMN_DEFAULT IS NULL)
      OR (COLUMN_NAME = 'occurred_at'
          AND DATA_TYPE = 'timestamp'
          AND DATETIME_PRECISION = 6
          AND IS_NULLABLE = 'NO'
          AND COLUMN_DEFAULT IS NULL)
  );
CALL assert_equal('alert inbox replay source columns', @actual, 4);

SELECT COUNT(*) INTO @actual
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'flowdesk_alert'
  AND TABLE_NAME = 'message_recovery'
  AND COLUMN_NAME = 'last_replay_correlation_id'
  AND COLUMN_TYPE = 'binary(16)'
  AND IS_NULLABLE = 'YES'
  AND COLUMN_DEFAULT IS NULL;
CALL assert_equal('alert replay correlation column', @actual, 1);

SELECT COUNT(*) INTO @actual
FROM information_schema.TABLE_CONSTRAINTS tc
JOIN information_schema.CHECK_CONSTRAINTS cc
  ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA
 AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
WHERE tc.CONSTRAINT_SCHEMA = 'flowdesk_alert'
  AND tc.TABLE_NAME = 'message_recovery'
  AND tc.CONSTRAINT_NAME = 'ck_alt_recovery_replay_correlation'
  AND tc.CONSTRAINT_TYPE = 'CHECK'
  AND tc.ENFORCED = 'YES'
  AND LOWER(REPLACE(cc.CHECK_CLAUSE, '`', '')) =
      '(((replay_count = 0) and (last_replay_correlation_id is null)) or ((replay_count > 0) and (last_replay_correlation_id is not null)))';
CALL assert_equal('alert replay correlation check', @actual, 1);

SELECT @@GLOBAL.innodb_flush_log_at_trx_commit INTO @actual;
CALL assert_equal('local InnoDB commit durability', @actual, 1);

SELECT @@GLOBAL.sync_binlog INTO @actual;
CALL assert_equal('local unused binlog sync disabled', @actual, 0);

SELECT @@GLOBAL.innodb_buffer_pool_size >= 1073741824 INTO @actual;
CALL assert_equal('local InnoDB buffer pool is at least 1 GiB', @actual, 1);

SELECT @@GLOBAL.innodb_redo_log_capacity INTO @actual;
CALL assert_equal('local InnoDB redo capacity bytes', @actual, 536870912);

SELECT @@GLOBAL.innodb_log_writer_threads INTO @actual;
CALL assert_equal('local low-concurrency redo writer mode', @actual, 0);

DROP PROCEDURE assert_equal;
SELECT 'FLOWDESK_BASELINE_CONTRACT_OK' AS verification_result;
