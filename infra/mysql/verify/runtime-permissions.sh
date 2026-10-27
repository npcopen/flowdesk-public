#!/usr/bin/env bash
set -Eeuo pipefail

# 使用指定账号通过 TCP 执行 SQL，以环境变量传递密码并返回无表头的批量结果。
mysql_exec() {
  local user="$1"
  local password="$2"
  local statement="$3"
  MYSQL_PWD="$password" mysql --protocol=tcp --host=mysql --user="$user" --batch --skip-column-names --execute="$statement"
}

# 执行预期无权限的 SQL，只有明确的 MySQL 授权错误才算通过，普通连接或语法错误不能替代权限验证。
expect_denied() {
  local user="$1"
  local password="$2"
  local statement="$3"
  local output

  if output="$(mysql_exec "$user" "$password" "$statement" 2>&1)"; then
    echo "Expected access denial for $user, but statement succeeded: $statement" >&2
    return 1
  fi

  case "$output" in
    *denied*|*1044*|*1142*|*1143*) ;;
    *)
      echo "Expected a MySQL privilege error for $user, got: $output" >&2
      return 1
      ;;
  esac
}

# 清理权限验证生成的探针审计行及固定测试租户，清理失败不覆盖原始检查结果。
cleanup_probe_rows() {
  local schema
  for schema in flowdesk_iam flowdesk_ticket flowdesk_workflow flowdesk_alert flowdesk_notification; do
    mysql_exec root "$FLOWDESK_MYSQL_ROOT_PASSWORD" \
      "DELETE FROM ${schema}.audit_record WHERE action_code = 'M2_PERMISSION_PROBE'" >/dev/null 2>&1 || true
  done
  mysql_exec root "$FLOWDESK_MYSQL_ROOT_PASSWORD" "
    DELETE FROM flowdesk_iam.role_permission
      WHERE tenant_id = UUID_TO_BIN('5e12e2c2-0000-0000-0000-000000000001');
    DELETE FROM flowdesk_iam.user_role_assignment
      WHERE tenant_id = UUID_TO_BIN('5e12e2c2-0000-0000-0000-000000000001');
    DELETE FROM flowdesk_iam.permission
      WHERE tenant_id = UUID_TO_BIN('5e12e2c2-0000-0000-0000-000000000001');
    DELETE FROM flowdesk_iam.role
      WHERE tenant_id = UUID_TO_BIN('5e12e2c2-0000-0000-0000-000000000001');
    DELETE FROM flowdesk_iam.user_account
      WHERE tenant_id = UUID_TO_BIN('5e12e2c2-0000-0000-0000-000000000001');
    DELETE FROM flowdesk_iam.tenant
      WHERE tenant_id = UUID_TO_BIN('5e12e2c2-0000-0000-0000-000000000001');
  " >/dev/null 2>&1 || true
}

# 构造已撤销角色及用户绑定，以运行账号重新授权并核验审计列和版本，确认具备所需最小列权限。
verify_iam_regrant_columns() {
  local t="UUID_TO_BIN('5e12e2c2-0000-0000-0000-000000000001')"
  local r="UUID_TO_BIN('5e12e2c2-0000-0000-0000-000000000002')"
  local p="UUID_TO_BIN('5e12e2c2-0000-0000-0000-000000000003')"
  local u="UUID_TO_BIN('5e12e2c2-0000-0000-0000-000000000004')"

  mysql_exec fd_iam_runtime "$FLOWDESK_IAM_DB_PASSWORD" "
    INSERT INTO flowdesk_iam.tenant
      (tenant_id, tenant_code, display_name, created_by_type, updated_by_type)
    VALUES
      (${t}, 'M2_REGRANT_PROBE', 'M2 regrant probe', 'SERVICE', 'SERVICE');
    INSERT INTO flowdesk_iam.role
      (tenant_id, role_id, role_code, role_name, status, created_by_type, updated_by_type)
    VALUES
      (${t}, ${r}, 'M2_PROBE_ROLE', 'M2 probe role', 'ACTIVE', 'SERVICE', 'SERVICE');
    INSERT INTO flowdesk_iam.permission
      (tenant_id, permission_id, permission_code, permission_name, created_by_type)
    VALUES
      (${t}, ${p}, 'M2_PROBE_PERMISSION', 'M2 probe permission', 'SERVICE');
    INSERT INTO flowdesk_iam.role_permission
      (tenant_id, role_id, permission_id, state, granted_at, granted_by_type,
       revoked_at, revoked_by_type, lock_version)
    VALUES
      (${t}, ${r}, ${p}, 'REVOKED', UTC_TIMESTAMP(6), 'SERVICE',
       UTC_TIMESTAMP(6), 'SERVICE', 0);
    INSERT INTO flowdesk_iam.user_account
      (tenant_id, user_id, username, display_name, status, created_by_type, updated_by_type)
    VALUES
      (${t}, ${u}, 'm2-probe-user', 'M2 probe user', 'ACTIVE', 'SERVICE', 'SERVICE');
    INSERT INTO flowdesk_iam.user_role_assignment
      (tenant_id, user_id, role_id, state, granted_at, granted_by_type,
       revoked_at, revoked_by_type, lock_version)
    VALUES
      (${t}, ${u}, ${r}, 'REVOKED', UTC_TIMESTAMP(6), 'SERVICE',
       UTC_TIMESTAMP(6), 'SERVICE', 0);
  " >/dev/null

  mysql_exec fd_iam_runtime "$FLOWDESK_IAM_DB_PASSWORD" "
    UPDATE flowdesk_iam.role_permission
    SET state = 'GRANTED',
        granted_at = UTC_TIMESTAMP(6),
        granted_by_type = 'USER',
        granted_by_id = UUID_TO_BIN(UUID()),
        revoked_at = NULL,
        revoked_by_type = NULL,
        revoked_by_id = NULL,
        lock_version = lock_version + 1
    WHERE tenant_id = ${t} AND role_id = ${r} AND permission_id = ${p}
      AND lock_version = 0;
    UPDATE flowdesk_iam.user_role_assignment
    SET state = 'GRANTED',
        granted_at = UTC_TIMESTAMP(6),
        granted_by_type = 'USER',
        granted_by_id = UUID_TO_BIN(UUID()),
        revoked_at = NULL,
        revoked_by_type = NULL,
        revoked_by_id = NULL,
        lock_version = lock_version + 1
    WHERE tenant_id = ${t} AND user_id = ${u} AND role_id = ${r}
      AND lock_version = 0;
  " >/dev/null

  local granted
  granted="$(mysql_exec fd_iam_runtime "$FLOWDESK_IAM_DB_PASSWORD" "
    SELECT CONCAT(
      (SELECT state FROM flowdesk_iam.role_permission
        WHERE tenant_id = ${t} AND role_id = ${r} AND permission_id = ${p}),
      '|',
      (SELECT state FROM flowdesk_iam.user_role_assignment
        WHERE tenant_id = ${t} AND user_id = ${u} AND role_id = ${r}));
  ")"
  if [[ "$granted" != "GRANTED|GRANTED" ]]; then
    echo "IAM re-grant UPDATE columns are not usable by fd_iam_runtime: $granted" >&2
    return 1
  fi
}

trap cleanup_probe_rows EXIT

business_accounts=(
  "fd_iam_runtime|$FLOWDESK_IAM_DB_PASSWORD|flowdesk_iam|user_account|tenant_id"
  "fd_ticket_runtime|$FLOWDESK_TICKET_DB_PASSWORD|flowdesk_ticket|ticket|requester_user_id"
  "fd_workflow_runtime|$FLOWDESK_WORKFLOW_DB_PASSWORD|flowdesk_workflow|workflow_instance|ticket_id"
  "fd_alert_runtime|$FLOWDESK_ALERT_DB_PASSWORD|flowdesk_alert|incident|fingerprint"
  "fd_notification_runtime|$FLOWDESK_NOTIFICATION_DB_PASSWORD|flowdesk_notification|notification|source_effect_key"
)

business_schemas=(flowdesk_iam flowdesk_ticket flowdesk_workflow flowdesk_alert flowdesk_notification)

for account in "${business_accounts[@]}"; do
  IFS='|' read -r user password own_schema frozen_table frozen_column <<<"$account"

  # Readiness checks the migration metadata from the service-owned schema.  The
  # grant must be usable after a successful migration, but must not open any
  # foreign Flyway history to the runtime principal.
  mysql_exec "$user" "$password" \
    "SELECT 1 FROM ${own_schema}.flyway_schema_history WHERE success = 1 LIMIT 1" >/dev/null

  mysql_exec "$user" "$password" "SELECT 1 FROM ${own_schema}.audit_record LIMIT 0" >/dev/null
  mysql_exec "$user" "$password" \
    "INSERT INTO ${own_schema}.audit_record
       (tenant_id, audit_id, action_code, target_type, outcome, actor_type, correlation_id, occurred_at)
     VALUES
       (UUID_TO_BIN(UUID()), UUID_TO_BIN(UUID()), 'M2_PERMISSION_PROBE', 'INFRASTRUCTURE',
        'SUCCEEDED', 'SERVICE', UUID_TO_BIN(UUID()), UTC_TIMESTAMP(6))" >/dev/null

  expect_denied "$user" "$password" \
    "UPDATE ${own_schema}.audit_record SET reason = reason WHERE action_code = 'M2_PERMISSION_PROBE'"
  expect_denied "$user" "$password" \
    "DELETE FROM ${own_schema}.audit_record WHERE action_code = 'M2_PERMISSION_PROBE'"
  expect_denied "$user" "$password" \
    "UPDATE ${own_schema}.${frozen_table} SET ${frozen_column} = ${frozen_column} WHERE 1 = 0"

  for target_schema in "${business_schemas[@]}"; do
    if [[ "$target_schema" == "$own_schema" ]]; then
      continue
    fi

    expect_denied "$user" "$password" "SELECT 1 FROM ${target_schema}.audit_record LIMIT 0"
    expect_denied "$user" "$password" \
      "SELECT 1 FROM ${target_schema}.flyway_schema_history LIMIT 0"
    expect_denied "$user" "$password" \
      "INSERT INTO ${target_schema}.audit_record
         (tenant_id, audit_id, action_code, target_type, outcome, actor_type, correlation_id, occurred_at)
       VALUES
         (UUID_TO_BIN(UUID()), UUID_TO_BIN(UUID()), 'M2_PERMISSION_PROBE', 'INFRASTRUCTURE',
          'SUCCEEDED', 'SERVICE', UUID_TO_BIN(UUID()), UTC_TIMESTAMP(6))"
    expect_denied "$user" "$password" \
      "UPDATE ${target_schema}.audit_record SET reason = reason WHERE 1 = 0"
    expect_denied "$user" "$password" \
      "CREATE TABLE ${target_schema}.m2_permission_probe (probe_id INT PRIMARY KEY)"
  done

  expect_denied "$user" "$password" "USE flowdesk_gateway"
  expect_denied "$user" "$password" \
    "CREATE TABLE flowdesk_gateway.m2_permission_probe (probe_id INT PRIMARY KEY)"
done

workflow_password="$FLOWDESK_WORKFLOW_DB_PASSWORD"
for immutable_inbox_column in body_bytes correlation_id causation_id occurred_at; do
  expect_denied fd_workflow_runtime "$workflow_password" \
    "UPDATE flowdesk_workflow.inbox_message
       SET ${immutable_inbox_column} = ${immutable_inbox_column}
     WHERE 1 = 0"
done
mysql_exec fd_workflow_runtime "$workflow_password" \
  "UPDATE flowdesk_workflow.message_recovery
      SET last_replay_correlation_id = last_replay_correlation_id
    WHERE 1 = 0" >/dev/null

ticket_password="$FLOWDESK_TICKET_DB_PASSWORD"
for immutable_inbox_column in body_bytes correlation_id causation_id occurred_at; do
  expect_denied fd_ticket_runtime "$ticket_password" \
    "UPDATE flowdesk_ticket.inbox_message
       SET ${immutable_inbox_column} = ${immutable_inbox_column}
     WHERE 1 = 0"
done
mysql_exec fd_ticket_runtime "$ticket_password" \
  "UPDATE flowdesk_ticket.message_recovery
      SET last_replay_correlation_id = last_replay_correlation_id
    WHERE 1 = 0" >/dev/null

# M8 Ticket runtime paths need all four new SLA tables, but mapping identities,
# creation audit facts and immutable recipient snapshots must remain frozen.
for m8_sla_table in sla_escalation_target sla_qualification_snapshot sla_index_rebuild_lease sla_trigger_recipient; do
  mysql_exec fd_ticket_runtime "$ticket_password" \
    "SELECT 1 FROM flowdesk_ticket.${m8_sla_table} LIMIT 0" >/dev/null
  expect_denied fd_ticket_runtime "$ticket_password" \
    "DELETE FROM flowdesk_ticket.${m8_sla_table} WHERE 1 = 0"
done

mysql_exec fd_ticket_runtime "$ticket_password" \
  "INSERT INTO flowdesk_ticket.sla_escalation_target
      (tenant_id,target_id,purpose,recipient_user_id,status,effective_at,
       qualification_state,created_by_type,updated_by_type)
    SELECT UUID_TO_BIN(UUID()),UUID_TO_BIN(UUID()),'SLA',UUID_TO_BIN(UUID()),
           'ACTIVE',UTC_TIMESTAMP(6),'UNKNOWN','SERVICE','SERVICE'
    WHERE FALSE;
   UPDATE flowdesk_ticket.sla_escalation_target
      SET status=status,
          qualification_version=qualification_version,
          qualification_checked_at=qualification_checked_at,
          qualification_valid_until=qualification_valid_until,
          qualification_state=qualification_state,
          qualification_reason_code=qualification_reason_code,
          lock_version=lock_version,
          updated_at=updated_at,
          updated_by_type=updated_by_type,
          updated_by_id=updated_by_id
    WHERE 1 = 0" >/dev/null
for immutable_target_column in tenant_id target_id purpose recipient_user_id effective_at created_at created_by_type created_by_id; do
  expect_denied fd_ticket_runtime "$ticket_password" \
    "UPDATE flowdesk_ticket.sla_escalation_target
        SET ${immutable_target_column} = ${immutable_target_column}
      WHERE 1 = 0"
done

mysql_exec fd_ticket_runtime "$ticket_password" \
  "INSERT INTO flowdesk_ticket.sla_qualification_snapshot
      (tenant_id,target_id,recipient_user_id,purpose,qualification_version,
       checked_at,valid_until,eligible,reason_code,created_by_type)
    SELECT UUID_TO_BIN(UUID()),UUID_TO_BIN(UUID()),UUID_TO_BIN(UUID()),'SLA',0,
           UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,'M8_PERMISSION_PROBE','SERVICE'
    WHERE FALSE;
   UPDATE flowdesk_ticket.sla_qualification_snapshot
      SET recipient_user_id=recipient_user_id,
          qualification_version=qualification_version,
          checked_at=checked_at,
          valid_until=valid_until,
          eligible=eligible,
          reason_code=reason_code
    WHERE 1 = 0" >/dev/null
for immutable_qualification_column in tenant_id target_id purpose created_at created_by_type created_by_id; do
  expect_denied fd_ticket_runtime "$ticket_password" \
    "UPDATE flowdesk_ticket.sla_qualification_snapshot
        SET ${immutable_qualification_column} = ${immutable_qualification_column}
      WHERE 1 = 0"
done

mysql_exec fd_ticket_runtime "$ticket_password" \
  "INSERT INTO flowdesk_ticket.sla_index_rebuild_lease
      (tenant_id,fencing_epoch,state,created_at,updated_at,updated_by_type)
    SELECT UUID_TO_BIN(UUID()),0,'IDLE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),'SCHEDULER'
    WHERE FALSE;
   UPDATE flowdesk_ticket.sla_index_rebuild_lease
      SET fencing_epoch=fencing_epoch,
          run_id=run_id,
          high_water_updated_at=high_water_updated_at,
          high_water_sla_clock_id=high_water_sla_clock_id,
          expires_at=expires_at,
          state=state,
          last_error_code=last_error_code,
          lock_version=lock_version,
          updated_at=updated_at,
          updated_by_type=updated_by_type
    WHERE 1 = 0" >/dev/null
for immutable_rebuild_column in tenant_id created_at updated_by_id; do
  expect_denied fd_ticket_runtime "$ticket_password" \
    "UPDATE flowdesk_ticket.sla_index_rebuild_lease
        SET ${immutable_rebuild_column} = ${immutable_rebuild_column}
      WHERE 1 = 0"
done

mysql_exec fd_ticket_runtime "$ticket_password" \
  "INSERT INTO flowdesk_ticket.sla_trigger_recipient
      (tenant_id,sla_trigger_id,recipient_user_id,created_by_type)
    SELECT UUID_TO_BIN(UUID()),UUID_TO_BIN(UUID()),UUID_TO_BIN(UUID()),'SCHEDULER'
    WHERE FALSE" >/dev/null
expect_denied fd_ticket_runtime "$ticket_password" \
  "UPDATE flowdesk_ticket.sla_trigger_recipient
      SET recipient_user_id = recipient_user_id
    WHERE 1 = 0"
expect_denied fd_ticket_runtime "$ticket_password" \
  "SELECT recipient_user_id
     FROM flowdesk_ticket.sla_trigger_recipient
    WHERE 1 = 0
    FOR UPDATE"

notification_password="$FLOWDESK_NOTIFICATION_DB_PASSWORD"
expect_denied fd_notification_runtime "$notification_password" \
  "SELECT body_bytes FROM flowdesk_notification.inbox_message LIMIT 0"
mysql_exec fd_notification_runtime "$notification_password" \
  "SELECT body_sha256, topic, consumer_group FROM flowdesk_notification.inbox_message LIMIT 0" >/dev/null

notification_recovery_password="$FLOWDESK_NOTIFICATION_RECOVERY_DB_PASSWORD"
mysql_exec fd_notification_recovery "$notification_recovery_password" \
  "SELECT body_bytes FROM flowdesk_notification.inbox_message LIMIT 0" >/dev/null
mysql_exec fd_notification_recovery "$notification_recovery_password" \
  "SELECT recovery_id, state FROM flowdesk_notification.message_recovery LIMIT 0" >/dev/null
expect_denied fd_notification_recovery "$notification_recovery_password" \
  "INSERT INTO flowdesk_notification.notification
     (tenant_id,notification_id,recipient_user_id,notification_kind,source_service,
      source_effect_key,source_ref_type,source_ref_id,title,body,created_at,updated_at)
   VALUES (UUID_TO_BIN(UUID()),UUID_TO_BIN(UUID()),UUID_TO_BIN(UUID()),'M8_PROBE',
      'flowdesk-ticket-service',UNHEX(SHA2('m8',256)),'INFRASTRUCTURE',UUID_TO_BIN(UUID()),
      'probe','probe',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))"
expect_denied fd_notification_recovery "$notification_recovery_password" \
  "UPDATE flowdesk_notification.inbox_message
      SET status = status
    WHERE 1 = 0"
expect_denied fd_notification_recovery "$notification_recovery_password" \
  "DELETE FROM flowdesk_notification.message_recovery WHERE 1 = 0"
expect_denied fd_notification_recovery "$notification_recovery_password" \
  "SELECT 1 FROM flowdesk_ticket.audit_record LIMIT 0"

gateway_password="$FLOWDESK_GATEWAY_DB_PASSWORD"
mysql_exec fd_gateway_runtime "$gateway_password" "SELECT 1" >/dev/null
expect_denied fd_gateway_runtime "$gateway_password" "USE flowdesk_gateway"
expect_denied fd_gateway_runtime "$gateway_password" "SELECT 1 FROM flowdesk_iam.audit_record LIMIT 0"
expect_denied fd_gateway_runtime "$gateway_password" \
  "INSERT INTO flowdesk_iam.audit_record
     (tenant_id, audit_id, action_code, target_type, outcome, actor_type, correlation_id, occurred_at)
   VALUES
     (UUID_TO_BIN(UUID()), UUID_TO_BIN(UUID()), 'M2_PERMISSION_PROBE', 'INFRASTRUCTURE',
      'SUCCEEDED', 'SERVICE', UUID_TO_BIN(UUID()), UTC_TIMESTAMP(6))"
expect_denied fd_gateway_runtime "$gateway_password" \
  "UPDATE flowdesk_iam.audit_record SET reason = reason WHERE 1 = 0"
expect_denied fd_gateway_runtime "$gateway_password" \
  "CREATE TABLE flowdesk_gateway.m2_permission_probe (probe_id INT PRIMARY KEY)"

migrator_accounts=(
  "fd_iam_migrator|$FLOWDESK_IAM_MIGRATOR_PASSWORD|flowdesk_iam|flowdesk_ticket"
  "fd_ticket_migrator|$FLOWDESK_TICKET_MIGRATOR_PASSWORD|flowdesk_ticket|flowdesk_workflow"
  "fd_workflow_migrator|$FLOWDESK_WORKFLOW_MIGRATOR_PASSWORD|flowdesk_workflow|flowdesk_alert"
  "fd_alert_migrator|$FLOWDESK_ALERT_MIGRATOR_PASSWORD|flowdesk_alert|flowdesk_notification"
  "fd_notification_migrator|$FLOWDESK_NOTIFICATION_MIGRATOR_PASSWORD|flowdesk_notification|flowdesk_iam"
)

for account in "${migrator_accounts[@]}"; do
  IFS='|' read -r user password own_schema foreign_schema <<<"$account"
  mysql_exec "$user" "$password" "SELECT 1 FROM ${own_schema}.flyway_schema_history LIMIT 0" >/dev/null
  expect_denied "$user" "$password" "SELECT 1 FROM ${foreign_schema}.audit_record LIMIT 0"
  expect_denied "$user" "$password" "CREATE DATABASE m2_permission_probe"
done

verify_iam_regrant_columns

echo FLOWDESK_RUNTIME_PERMISSIONS_OK
