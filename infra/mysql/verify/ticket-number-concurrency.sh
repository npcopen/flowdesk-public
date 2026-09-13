#!/usr/bin/env bash
set -Eeuo pipefail

: "${FLOWDESK_MYSQL_ROOT_PASSWORD:?set FLOWDESK_MYSQL_ROOT_PASSWORD}"
: "${FLOWDESK_TICKET_DB_PASSWORD:?set FLOWDESK_TICKET_DB_PASSWORD}"

mysql_exec() {
  local user="$1"
  local password="$2"
  local statement="$3"
  MYSQL_PWD="$password" mysql \
    --protocol=tcp \
    --host=mysql \
    --user="$user" \
    --batch \
    --raw \
    --skip-column-names \
    --connect-timeout=10 \
    --execute="$statement"
}

expect_denied() {
  local statement="$1"
  local output
  if output="$(mysql_exec fd_ticket_runtime "$FLOWDESK_TICKET_DB_PASSWORD" "$statement" 2>&1)"; then
    echo "Expected Ticket counter privilege denial, but statement succeeded: ${statement}" >&2
    return 1
  fi
  case "$output" in
    *denied*|*1142*|*1143*) ;;
    *)
      echo "Expected a MySQL privilege error, got: ${output}" >&2
      return 1
      ;;
  esac
}

allocation_count=100
probe_tenant="$(mysql_exec fd_ticket_runtime "$FLOWDESK_TICKET_DB_PASSWORD" "SELECT UUID()")"
business_date="$(mysql_exec fd_ticket_runtime "$FLOWDESK_TICKET_DB_PASSWORD" "SELECT UTC_DATE()")"
date_segment="${business_date//-/}"
work_dir="$(mktemp -d)"

cleanup() {
  mysql_exec root "$FLOWDESK_MYSQL_ROOT_PASSWORD" "
    DELETE FROM flowdesk_ticket.ticket
    WHERE tenant_id = UUID_TO_BIN('${probe_tenant}');
    DELETE FROM flowdesk_ticket.ticket_number_counter
    WHERE tenant_id = UUID_TO_BIN('${probe_tenant}');
  " >/dev/null 2>&1 || true
  rm -rf -- "$work_dir"
}
trap cleanup EXIT

# Allocation is transaction-bound: an explicit rollback must remove a newly allocated value.
mysql_exec fd_ticket_runtime "$FLOWDESK_TICKET_DB_PASSWORD" "
  START TRANSACTION;
  INSERT INTO flowdesk_ticket.ticket_number_counter
    (tenant_id, business_date, sequence_value)
  VALUES
    (UUID_TO_BIN('${probe_tenant}'), '${business_date}', 1)
  ON DUPLICATE KEY UPDATE
    sequence_value = sequence_value + 1,
    updated_at = UTC_TIMESTAMP(6);
  ROLLBACK;
" >/dev/null

rolled_back_rows="$(mysql_exec fd_ticket_runtime "$FLOWDESK_TICKET_DB_PASSWORD" "
  SELECT COUNT(sequence_value)
  FROM flowdesk_ticket.ticket_number_counter
  WHERE tenant_id = UUID_TO_BIN('${probe_tenant}')
    AND business_date = '${business_date}';
")"
if [[ "$rolled_back_rows" != "0" ]]; then
  echo "Rolled-back Ticket number allocation persisted unexpectedly" >&2
  exit 1
fi

allocate_ticket() {
  local worker="$1"
  mysql_exec fd_ticket_runtime "$FLOWDESK_TICKET_DB_PASSWORD" "
    START TRANSACTION;

    INSERT INTO flowdesk_ticket.ticket_number_counter
      (tenant_id, business_date, sequence_value)
    VALUES
      (UUID_TO_BIN('${probe_tenant}'), '${business_date}', 1)
    ON DUPLICATE KEY UPDATE
      sequence_value = sequence_value + 1,
      updated_at = UTC_TIMESTAMP(6);

    SELECT sequence_value INTO @ticket_sequence
    FROM flowdesk_ticket.ticket_number_counter
    WHERE tenant_id = UUID_TO_BIN('${probe_tenant}')
      AND business_date = '${business_date}'
    FOR UPDATE;

    SET @sequence_text = CAST(@ticket_sequence AS CHAR);
    SET @ticket_no = CONCAT(
      'FD-${date_segment}-',
      IF(CHAR_LENGTH(@sequence_text) < 4, LPAD(@sequence_text, 4, '0'), @sequence_text)
    );

    INSERT INTO flowdesk_ticket.ticket
      (tenant_id, ticket_id, ticket_no, creation_intent_key, origin_type,
       subject, description, status, first_processable_at,
       created_by_type, updated_by_type)
    VALUES
      (UUID_TO_BIN('${probe_tenant}'), UUID_TO_BIN(UUID()), @ticket_no,
       UNHEX(SHA2(CONCAT('${probe_tenant}', ':', '${worker}'), 256)), 'INCIDENT',
       CONCAT('Ticket number concurrency probe ', '${worker}'),
       'Real MySQL concurrent Ticket number allocation probe',
       'PENDING_ASSIGNMENT', UTC_TIMESTAMP(6), 'SERVICE', 'SERVICE');

    SELECT @ticket_no;
    COMMIT;
  "
}

declare -a pids=()
declare -a workers=()
for worker in $(seq 1 "$allocation_count"); do
  allocate_ticket "$worker" \
    >"$work_dir/ticket-${worker}.out" \
    2>"$work_dir/ticket-${worker}.err" &
  pids+=("$!")
  workers+=("$worker")
done

worker_failure=0
for index in "${!pids[@]}"; do
  if ! wait "${pids[$index]}"; then
    worker="${workers[$index]}"
    echo "Ticket number worker ${worker} failed:" >&2
    cat "$work_dir/ticket-${worker}.err" >&2
    worker_failure=1
  fi
done
if [[ "$worker_failure" != "0" ]]; then
  exit 1
fi

cat "$work_dir"/ticket-*.out >"$work_dir/numbers.txt"
number_count="$(grep -c . "$work_dir/numbers.txt")"
if [[ "$number_count" != "$allocation_count" ]]; then
  echo "Expected ${allocation_count} Ticket numbers, got ${number_count}" >&2
  exit 1
fi

if grep -Ev "^FD-${date_segment}-[0-9]{4,}$" "$work_dir/numbers.txt" >"$work_dir/invalid-format.txt"; then
  echo "Ticket number format validation failed:" >&2
  cat "$work_dir/invalid-format.txt" >&2
  exit 1
fi

unique_count="$(sort -u "$work_dir/numbers.txt" | wc -l | tr -d '[:space:]')"
if [[ "$unique_count" != "$allocation_count" ]]; then
  echo "Expected ${allocation_count} unique Ticket numbers, got ${unique_count}" >&2
  exit 1
fi

read -r stored_count stored_unique min_sequence max_sequence <<<"$(
  mysql_exec fd_ticket_runtime "$FLOWDESK_TICKET_DB_PASSWORD" "
    SELECT COUNT(*),
           COUNT(DISTINCT ticket_no),
           MIN(CAST(SUBSTRING(ticket_no, 13) AS UNSIGNED)),
           MAX(CAST(SUBSTRING(ticket_no, 13) AS UNSIGNED))
    FROM flowdesk_ticket.ticket
    WHERE tenant_id = UUID_TO_BIN('${probe_tenant}');
  "
)"
if [[ "$stored_count" != "$allocation_count" || "$stored_unique" != "$allocation_count" ]]; then
  echo "Ticket unique-key verification failed: rows=${stored_count}, unique=${stored_unique}" >&2
  exit 1
fi
if [[ "$min_sequence" != "1" || "$max_sequence" != "$allocation_count" ]]; then
  echo "Unexpected Ticket number range: min=${min_sequence}, max=${max_sequence}" >&2
  exit 1
fi

counter_value="$(mysql_exec fd_ticket_runtime "$FLOWDESK_TICKET_DB_PASSWORD" "
  SELECT sequence_value
  FROM flowdesk_ticket.ticket_number_counter
  WHERE tenant_id = UUID_TO_BIN('${probe_tenant}')
    AND business_date = '${business_date}';
")"
if [[ "$counter_value" != "$allocation_count" ]]; then
  echo "Unexpected Ticket counter value: ${counter_value}" >&2
  exit 1
fi

# The duplicate-key update branch is transactional too: a rolled-back increment is reusable.
mysql_exec fd_ticket_runtime "$FLOWDESK_TICKET_DB_PASSWORD" "
  START TRANSACTION;
  INSERT INTO flowdesk_ticket.ticket_number_counter
    (tenant_id, business_date, sequence_value)
  VALUES
    (UUID_TO_BIN('${probe_tenant}'), '${business_date}', 1)
  ON DUPLICATE KEY UPDATE
    sequence_value = sequence_value + 1,
    updated_at = UTC_TIMESTAMP(6);
  SELECT sequence_value
  FROM flowdesk_ticket.ticket_number_counter
  WHERE tenant_id = UUID_TO_BIN('${probe_tenant}')
    AND business_date = '${business_date}'
  FOR UPDATE;
  ROLLBACK;
" >/dev/null

counter_after_rollback="$(mysql_exec fd_ticket_runtime "$FLOWDESK_TICKET_DB_PASSWORD" "
  SELECT sequence_value
  FROM flowdesk_ticket.ticket_number_counter
  WHERE tenant_id = UUID_TO_BIN('${probe_tenant}')
    AND business_date = '${business_date}';
")"
if [[ "$counter_after_rollback" != "$allocation_count" ]]; then
  echo "Rolled-back Ticket counter increment persisted: ${counter_after_rollback}" >&2
  exit 1
fi

if grep -R -i "duplicate entry" "$work_dir"/*.err >/dev/null; then
  echo "A duplicate-key error occurred during Ticket creation" >&2
  exit 1
fi

# The runtime account may only read allocation keys/value and advance sequence_value/updated_at.
expect_denied "
  SELECT created_at
  FROM flowdesk_ticket.ticket_number_counter
  WHERE tenant_id = UUID_TO_BIN('${probe_tenant}')
  LIMIT 1;
"
expect_denied "
  INSERT INTO flowdesk_ticket.ticket_number_counter
    (tenant_id, business_date, sequence_value, created_at)
  VALUES
    (UUID_TO_BIN('${probe_tenant}'), DATE_ADD('${business_date}', INTERVAL 1 DAY), 1, UTC_TIMESTAMP(6));
"
expect_denied "
  UPDATE flowdesk_ticket.ticket_number_counter
  SET business_date = business_date
  WHERE tenant_id = UUID_TO_BIN('${probe_tenant}')
    AND business_date = '${business_date}';
"
expect_denied "
  DELETE FROM flowdesk_ticket.ticket_number_counter
  WHERE tenant_id = UUID_TO_BIN('${probe_tenant}')
    AND business_date = '${business_date}';
"

echo "FLOWDESK_TICKET_NUMBER_CONCURRENCY_OK allocations=${allocation_count} unique=${unique_count} counter=${counter_value} rollback_rows=${rolled_back_rows} rollback_counter=${counter_after_rollback}"
