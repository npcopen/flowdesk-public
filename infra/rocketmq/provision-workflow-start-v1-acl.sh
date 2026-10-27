#!/usr/bin/env sh
set -eu

# Run from an authenticated RocketMQ 5.3.4 admin image whose conf/tools.yml
# contains the flowdeskmqadmin credentials. Application credentials are supplied
# through the environment and are never printed. RocketMQ's mqadmin can print a
# Java exception while still returning exit status 0, so every command is checked
# for both its process status and known error markers.
nameserver="${FLOWDESK_ROCKETMQ_ADMIN_NAME_SERVER:-rocketmq-nameserver:9876}"
broker_host="${FLOWDESK_ROCKETMQ_ADMIN_BROKER_HOST:-127.0.0.1}"
broker_port="${FLOWDESK_ROCKETMQ_BROKER_PUBLISH_PORT:-10911}"
broker_address="${FLOWDESK_ROCKETMQ_ADMIN_BROKER_ADDRESS:-$broker_host:$broker_port}"
ticket_user="${FLOWDESK_TICKET_ROCKETMQ_ACCESS_KEY:-flowdeskticket}"
workflow_user="${FLOWDESK_WORKFLOW_ROCKETMQ_ACCESS_KEY:-flowdeskworkflow}"
alert_user="${FLOWDESK_ALERT_ROCKETMQ_ACCESS_KEY:-flowdeskalert}"
notification_user="${FLOWDESK_NOTIFICATION_ROCKETMQ_ACCESS_KEY:-flowdesknotification}"
start_topic="flowdesk_workflow_start_v1"
start_group="flowdesk_workflow_start_v1_workflow_cg"
start_acceptor_group="flowdesk_workflow_start_v1_workflow_dlq_acceptor_cg"
instance_topic="flowdesk_workflow_instance_established_v1"
instance_group="flowdesk_workflow_instance_established_v1_ticket_cg"
instance_acceptor_group="flowdesk_workflow_instance_established_v1_ticket_dlq_acceptor_cg"
final_topic="flowdesk_workflow_approval_finalized_v1"
final_group="flowdesk_workflow_approval_finalized_v1_ticket_cg"
final_acceptor_group="flowdesk_workflow_approval_finalized_v1_ticket_dlq_acceptor_cg"

ticket_pub_resources="Topic:$start_topic"
ticket_sub_resources="Topic:$instance_topic,Topic:%RETRY%$instance_group,Topic:%DLQ%$instance_group,Topic:%RETRY%$instance_acceptor_group,Group:$instance_group,Group:$instance_acceptor_group,Topic:$final_topic,Topic:%RETRY%$final_group,Topic:%DLQ%$final_group,Topic:%RETRY%$final_acceptor_group,Group:$final_group,Group:$final_acceptor_group"
workflow_sub_resources="Topic:$start_topic,Topic:%RETRY%$start_group,Topic:%DLQ%$start_group,Topic:%RETRY%$start_acceptor_group,Group:$start_group,Group:$start_acceptor_group"
workflow_pub_resources="Topic:$instance_topic,Topic:$final_topic"
notification_sub_resources=""

if [ "${FLOWDESK_ROCKETMQ_M7_ENABLED:-false}" = "true" ]; then
  create_topic="flowdesk_incident_ticket_create_requested_v1"
  create_group="flowdesk_incident_ticket_create_requested_v1_ticket_cg"
  create_acceptor_group="flowdesk_incident_ticket_create_requested_v1_ticket_dlq_acceptor_cg"
  established_topic="flowdesk_incident_ticket_established_v1"
  established_group="flowdesk_incident_ticket_established_v1_alert_cg"
  established_acceptor_group="flowdesk_incident_ticket_established_v1_alert_dlq_acceptor_cg"
  recovered_topic="flowdesk_incident_recovered_v1"
  recovered_group="flowdesk_incident_recovered_v1_ticket_cg"
  recovered_acceptor_group="flowdesk_incident_recovered_v1_ticket_dlq_acceptor_cg"
  ticket_pub_resources="$ticket_pub_resources,Topic:$established_topic"
  ticket_sub_resources="$ticket_sub_resources,Topic:$create_topic,Topic:%RETRY%$create_group,Topic:%DLQ%$create_group,Topic:%RETRY%$create_acceptor_group,Group:$create_group,Group:$create_acceptor_group,Topic:$recovered_topic,Topic:%RETRY%$recovered_group,Topic:%DLQ%$recovered_group,Topic:%RETRY%$recovered_acceptor_group,Group:$recovered_group,Group:$recovered_acceptor_group"
  alert_pub_resources="Topic:$create_topic,Topic:$recovered_topic"
  alert_sub_resources="Topic:$established_topic,Topic:%RETRY%$established_group,Topic:%DLQ%$established_group,Topic:%RETRY%$established_acceptor_group,Group:$established_group,Group:$established_acceptor_group"
fi

if [ "${FLOWDESK_ROCKETMQ_M8_ENABLED:-false}" = "true" ]; then
  ticket_notification_topic="flowdesk_ticket_notification_requested_v1"
  ticket_notification_group="flowdesk_ticket_notification_requested_v1_notification_cg"
  ticket_notification_acceptor_group="flowdesk_ticket_notification_requested_v1_notification_dlq_acceptor_cg"
  workflow_notification_topic="flowdesk_workflow_notification_requested_v1"
  workflow_notification_group="flowdesk_workflow_notification_requested_v1_notification_cg"
  workflow_notification_acceptor_group="flowdesk_workflow_notification_requested_v1_notification_dlq_acceptor_cg"
  ticket_pub_resources="$ticket_pub_resources,Topic:$ticket_notification_topic"
  workflow_pub_resources="$workflow_pub_resources,Topic:$workflow_notification_topic"
  notification_sub_resources="Topic:$ticket_notification_topic,Topic:%RETRY%$ticket_notification_group,Topic:%DLQ%$ticket_notification_group,Topic:%RETRY%$ticket_notification_acceptor_group,Group:$ticket_notification_group,Group:$ticket_notification_acceptor_group,Topic:$workflow_notification_topic,Topic:%RETRY%$workflow_notification_group,Topic:%DLQ%$workflow_notification_group,Topic:%RETRY%$workflow_notification_acceptor_group,Group:$workflow_notification_group,Group:$workflow_notification_acceptor_group"
fi

: "${FLOWDESK_ROCKETMQ_ACL_ENABLED:?set FLOWDESK_ROCKETMQ_ACL_ENABLED}"
: "${FLOWDESK_ROCKETMQ_ADMIN_ACCESS_KEY:?set FLOWDESK_ROCKETMQ_ADMIN_ACCESS_KEY}"
: "${FLOWDESK_ROCKETMQ_ADMIN_SECRET_KEY:?set FLOWDESK_ROCKETMQ_ADMIN_SECRET_KEY}"
: "${FLOWDESK_TICKET_ROCKETMQ_SECRET_KEY:?set FLOWDESK_TICKET_ROCKETMQ_SECRET_KEY}"
: "${FLOWDESK_WORKFLOW_ROCKETMQ_SECRET_KEY:?set FLOWDESK_WORKFLOW_ROCKETMQ_SECRET_KEY}"
if [ "${FLOWDESK_ROCKETMQ_M7_ENABLED:-false}" = "true" ]; then
  : "${FLOWDESK_ALERT_ROCKETMQ_SECRET_KEY:?set FLOWDESK_ALERT_ROCKETMQ_SECRET_KEY}"
fi
if [ "${FLOWDESK_ROCKETMQ_M8_ENABLED:-false}" = "true" ]; then
  : "${FLOWDESK_NOTIFICATION_ROCKETMQ_SECRET_KEY:?set FLOWDESK_NOTIFICATION_ROCKETMQ_SECRET_KEY}"
fi

# 输出当前脚本的稳定失败标记及诊断并退出，阻止后续初始化或验证继续执行。
fail() {
  printf '%s\n' "FLOWDESK_ROCKETMQ_ACL_PROVISION_FAILED: $*" >&2
  exit 1
}

# Deployment can provision and read back metadata without publishing probe messages.
# With no option, retain the existing negative authorization checks.
provision_only=false
case "$#" in
  0) ;;
  1)
    [ "$1" = "--provision-only" ] || fail "usage: provision-workflow-start-v1-acl.sh [--provision-only]"
    provision_only=true
    ;;
  *) fail "usage: provision-workflow-start-v1-acl.sh [--provision-only]" ;;
esac

# 要求配置值仅含 ASCII 字母和数字，拒绝空值及可能破坏配置格式的字符。
validate_alphanumeric() {
  label="$1"
  value="$2"
  case "$value" in
    ''|*[!A-Za-z0-9]*) fail "$label must contain ASCII letters and digits only" ;;
  esac
}

validate_alphanumeric "Ticket AccessKey" "$ticket_user"
validate_alphanumeric "Workflow AccessKey" "$workflow_user"
validate_alphanumeric "Ticket SecretKey" "$FLOWDESK_TICKET_ROCKETMQ_SECRET_KEY"
validate_alphanumeric "Workflow SecretKey" "$FLOWDESK_WORKFLOW_ROCKETMQ_SECRET_KEY"
[ "$FLOWDESK_ROCKETMQ_ACL_ENABLED" = "true" ] || fail "ACL provisioning requires FLOWDESK_ROCKETMQ_ACL_ENABLED=true"
validate_alphanumeric "Admin AccessKey" "$FLOWDESK_ROCKETMQ_ADMIN_ACCESS_KEY"
validate_alphanumeric "Admin SecretKey" "$FLOWDESK_ROCKETMQ_ADMIN_SECRET_KEY"
[ "$FLOWDESK_ROCKETMQ_ADMIN_ACCESS_KEY" = 'flowdeskmqadmin' ] || fail "Admin AccessKey is frozen as flowdeskmqadmin"
[ "${#FLOWDESK_ROCKETMQ_ADMIN_SECRET_KEY}" -ge 16 ] || fail "Admin SecretKey must contain at least 16 characters"
[ "$ticket_user" = 'flowdeskticket' ] || fail "Ticket AccessKey is frozen as flowdeskticket"
[ "$workflow_user" = 'flowdeskworkflow' ] || fail "Workflow AccessKey is frozen as flowdeskworkflow"
[ "${#FLOWDESK_TICKET_ROCKETMQ_SECRET_KEY}" -ge 16 ] || fail "Ticket SecretKey must contain at least 16 characters"
[ "${#FLOWDESK_WORKFLOW_ROCKETMQ_SECRET_KEY}" -ge 16 ] || fail "Workflow SecretKey must contain at least 16 characters"
[ "$FLOWDESK_TICKET_ROCKETMQ_SECRET_KEY" != "$FLOWDESK_WORKFLOW_ROCKETMQ_SECRET_KEY" ] || \
  fail "Ticket and Workflow SecretKeys must be independently generated"
if [ "${FLOWDESK_ROCKETMQ_M7_ENABLED:-false}" = "true" ]; then
  validate_alphanumeric "Alert AccessKey" "$alert_user"
  validate_alphanumeric "Alert SecretKey" "$FLOWDESK_ALERT_ROCKETMQ_SECRET_KEY"
  [ "$alert_user" = 'flowdeskalert' ] || fail "Alert AccessKey is frozen as flowdeskalert"
  [ "${#FLOWDESK_ALERT_ROCKETMQ_SECRET_KEY}" -ge 16 ] || fail "Alert SecretKey must contain at least 16 characters"
  [ "$FLOWDESK_ALERT_ROCKETMQ_SECRET_KEY" != "$FLOWDESK_TICKET_ROCKETMQ_SECRET_KEY" ] || \
    fail "Alert and Ticket SecretKeys must be independently generated"
  [ "$FLOWDESK_ALERT_ROCKETMQ_SECRET_KEY" != "$FLOWDESK_WORKFLOW_ROCKETMQ_SECRET_KEY" ] || \
    fail "Alert and Workflow SecretKeys must be independently generated"
fi
if [ "${FLOWDESK_ROCKETMQ_M8_ENABLED:-false}" = "true" ]; then
  validate_alphanumeric "Notification AccessKey" "$notification_user"
  validate_alphanumeric "Notification SecretKey" "$FLOWDESK_NOTIFICATION_ROCKETMQ_SECRET_KEY"
  [ "$notification_user" = 'flowdesknotification' ] || fail "Notification AccessKey is frozen as flowdesknotification"
  [ "${#FLOWDESK_NOTIFICATION_ROCKETMQ_SECRET_KEY}" -ge 16 ] || fail "Notification SecretKey must contain at least 16 characters"
  [ "$FLOWDESK_NOTIFICATION_ROCKETMQ_SECRET_KEY" != "$FLOWDESK_TICKET_ROCKETMQ_SECRET_KEY" ] || \
    fail "Notification and Ticket SecretKeys must be independently generated"
  [ "$FLOWDESK_NOTIFICATION_ROCKETMQ_SECRET_KEY" != "$FLOWDESK_WORKFLOW_ROCKETMQ_SECRET_KEY" ] || \
    fail "Notification and Workflow SecretKeys must be independently generated"
  if [ "${FLOWDESK_ROCKETMQ_M7_ENABLED:-false}" = "true" ]; then
    [ "$FLOWDESK_NOTIFICATION_ROCKETMQ_SECRET_KEY" != "$FLOWDESK_ALERT_ROCKETMQ_SECRET_KEY" ] || \
      fail "Notification and Alert SecretKeys must be independently generated"
  fi
fi

# mqadmin automatically reads ../conf/tools.yml. Keep deployment credentials in
# this short-lived container only; business JVMs never receive them.
umask 077
printf 'accessKey: %s\nsecretKey: %s\n' \
  "$FLOWDESK_ROCKETMQ_ADMIN_ACCESS_KEY" \
  "$FLOWDESK_ROCKETMQ_ADMIN_SECRET_KEY" > ../conf/tools.yml

mqadmin_output=''
# 执行管理命令并保存输出，同时检查退出码和文本错误标记，避免 mqadmin 返回零仍误报成功。
run_mqadmin() {
  operation="$1"
  shift
  mqadmin_status=0
  mqadmin_output="$(sh mqadmin "$@" 2>&1)" || mqadmin_status=$?
  if [ "$mqadmin_status" -ne 0 ]; then
    fail "$operation (mqadmin exit status $mqadmin_status)"
  fi
  if printf '%s\n' "$mqadmin_output" | grep -Eiq \
    'SubCommandException|Caused by:|CODE:[[:space:]]*[1-9][0-9]*|command failed|system error|Remoting[A-Za-z]*Exception'; then
    fail "$operation (mqadmin reported an error despite exit status 0)"
  fi
}

# 解析 Broker 配置键值并要求与冻结 ACL 栈完全一致，避免在不受支持的安全配置上写入授权。
require_broker_value() {
  key="$1"
  expected="$2"
  if ! printf '%s\n' "$broker_config" | awk -F '=' -v key="$key" -v expected="$expected" '
    {
      current_key = $1
      current_value = $2
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", current_key)
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", current_value)
      if (current_key == key && current_value == expected) found = 1
    }
    END { exit(found ? 0 : 1) }
  '; then
    fail "Broker $key must be $expected at $broker_address"
  fi
}

# Refuse to touch users or ACL records unless the target Broker exposes the exact
# frozen ACL 2.0 stack. This intentionally fails against the ACL-disabled M4
# baseline instead of reporting a false successful provision.
run_mqadmin "read Broker ACL configuration" getBrokerConfig \
  -n "$nameserver" -b "$broker_address"
broker_config="$mqadmin_output"
require_broker_value "authenticationEnabled" "true"
require_broker_value "authenticationMetadataProvider" \
  "org.apache.rocketmq.auth.authentication.provider.LocalAuthenticationMetadataProvider"
require_broker_value "authenticationStrategy" \
  "org.apache.rocketmq.auth.authentication.strategy.StatefulAuthenticationStrategy"
require_broker_value "authorizationEnabled" "true"
require_broker_value "authorizationMetadataProvider" \
  "org.apache.rocketmq.auth.authorization.provider.LocalAuthorizationMetadataProvider"
require_broker_value "authorizationStrategy" \
  "org.apache.rocketmq.auth.authorization.strategy.StatefulAuthorizationStrategy"

# 创建或更新业务用户密码，并用独立管理命令确保用户类型为 Normal 且状态启用。
upsert_user() {
  username="$1"
  password="$2"
  run_mqadmin "list user $username" listUser \
    -n "$nameserver" -b "$broker_address" -f "$username"
  if printf '%s\n' "$mqadmin_output" | awk -v username="$username" '
    $1 == username { found = 1 }
    END { exit(found ? 0 : 1) }
  '; then
    run_mqadmin "update password for user $username" updateUser \
      -n "$nameserver" -b "$broker_address" -u "$username" -p "$password"
  else
    run_mqadmin "create user $username" createUser \
      -n "$nameserver" -b "$broker_address" -u "$username" -p "$password" -t Normal
  fi
  # updateUser exposes password, type and status as a mutually-exclusive option
  # group, so enforce each mutable invariant with a separate checked command.
  run_mqadmin "set Normal type for user $username" updateUser \
    -n "$nameserver" -b "$broker_address" -u "$username" -t Normal
  run_mqadmin "enable user $username" updateUser \
    -n "$nameserver" -b "$broker_address" -u "$username" -s enable
}

# 删除主体已有的整套 ACL，为精确授权重建清场，防止旧通配或多余权限残留。
reset_acl() {
  subject="$1"
  run_mqadmin "list ACL for $subject" listAcl \
    -n "$nameserver" -b "$broker_address" -s "$subject"
  if printf '%s\n' "$mqadmin_output" | awk -v subject="$subject" '
    $1 == subject { found = 1 }
    END { exit(found ? 0 : 1) }
  '; then
    # Replace the complete application ACL so a pre-existing wildcard or stale
    # permission cannot survive an otherwise successful provision.
    run_mqadmin "delete existing ACL for $subject" deleteAcl \
      -n "$nameserver" -b "$broker_address" -s "$subject"
  fi
}

# 为指定主体、资源及动作创建明确的 Allow 授权，不增加额外资源范围。
create_acl_entry() {
  subject="$1"
  resources="$2"
  actions="$3"
  run_mqadmin "create exact ACL for $subject" createAcl \
    -n "$nameserver" -b "$broker_address" -s "$subject" \
    -r "$resources" -a "$actions" -d Allow
}

# 读回用户，确认身份存在且为已启用的 Normal 用户，否则判定授权初始化失败。
verify_user() {
  username="$1"
  run_mqadmin "verify user $username" getUser \
    -n "$nameserver" -b "$broker_address" -u "$username"
  if ! printf '%s\n' "$mqadmin_output" | awk -v username="$username" '
    $1 == username && toupper($3) == "NORMAL" && toupper($4) == "ENABLE" { found = 1 }
    END { exit(found ? 0 : 1) }
  '; then
    fail "user $username is missing, non-Normal or disabled after provision"
  fi
}

# 逐项核对指定资源上的 Allow 动作，并恢复字段分隔符，保证每项期望权限都已落地。
verify_acl_resources() {
  subject="$1"
  resources="$2"
  action="$3"
  run_mqadmin "verify ACL for $subject" getAcl \
    -n "$nameserver" -b "$broker_address" -s "$subject"

  expected_count=0
  previous_ifs="$IFS"
  IFS=','
  for resource in $resources; do
    expected_count=$((expected_count + 1))
    if ! printf '%s\n' "$mqadmin_output" | awk \
      -v subject="$subject" -v resource="$resource" -v action="$action" '
      $1 == subject && $3 == resource &&
        index(toupper($4), toupper(action)) > 0 && toupper($NF) == "ALLOW" { found = 1 }
      END { exit(found ? 0 : 1) }
    '; then
      IFS="$previous_ifs"
      fail "exact Allow $action on $resource is missing for $subject"
    fi
  done
  IFS="$previous_ifs"

}

# 统计主体实际 ACL 数量，要求与精确预期一致，以发现额外授权。
verify_acl_count() {
  subject="$1"
  expected_count="$2"
  run_mqadmin "verify exact ACL count for $subject" getAcl \
    -n "$nameserver" -b "$broker_address" -s "$subject"
  actual_count="$(printf '%s\n' "$mqadmin_output" | awk -v subject="$subject" '
    $1 == subject { count++ }
    END { print count + 0 }
  ')"
  [ "$actual_count" -eq "$expected_count" ] || \
    fail "$subject has unexpected additional ACL entries after provision"
}

# 用指定身份尝试向禁止主题发送探针，要求明确授权拒绝；仅初始化模式跳过探针，模糊结果仍判失败。
expect_publish_denied() {
  marker="$1"
  label="$2"
  username="$3"
  password="$4"
  topic="$5"
  if [ "$provision_only" = "true" ]; then
    printf '%s\n' "FLOWDESK_ROCKETMQ_${marker}_NEGATIVE_ACL_SKIPPED identity=$label topic=$topic mode=provision-only"
    return
  fi
  printf 'accessKey: %s\nsecretKey: %s\n' "$username" "$password" > ../conf/tools.yml
  denied_status=0
  denied_output="$(sh mqadmin sendMessage \
    -n "$nameserver" -t "$topic" -k flowdesk-m7-negative-acl-probe -p '{}' 2>&1)" || \
    denied_status=$?
  if printf '%s\n' "$denied_output" | grep -Eiq \
    'NO_PERMISSION|No permission|not have permission|authorization|CODE:[[:space:]]*16'; then
    printf '%s\n' "FLOWDESK_ROCKETMQ_${marker}_NEGATIVE_ACL_DENIED identity=$label topic=$topic"
    return
  fi
  if printf '%s\n' "$denied_output" | grep -q 'SEND_OK'; then
    fail "forbidden $marker publish was allowed for $label on $topic"
  fi
  fail "forbidden $marker publish produced an indeterminate result for $label on $topic (exit $denied_status)"
}

upsert_user "$ticket_user" "$FLOWDESK_TICKET_ROCKETMQ_SECRET_KEY"
upsert_user "$workflow_user" "$FLOWDESK_WORKFLOW_ROCKETMQ_SECRET_KEY"
if [ "${FLOWDESK_ROCKETMQ_M7_ENABLED:-false}" = "true" ]; then
  upsert_user "$alert_user" "$FLOWDESK_ALERT_ROCKETMQ_SECRET_KEY"
fi
if [ "${FLOWDESK_ROCKETMQ_M8_ENABLED:-false}" = "true" ]; then
  upsert_user "$notification_user" "$FLOWDESK_NOTIFICATION_ROCKETMQ_SECRET_KEY"
fi
reset_acl "User:$ticket_user"
create_acl_entry "User:$ticket_user" "$ticket_pub_resources" "Pub"
create_acl_entry "User:$ticket_user" "$ticket_sub_resources" "Sub"
reset_acl "User:$workflow_user"
create_acl_entry "User:$workflow_user" "$workflow_sub_resources" "Sub"
create_acl_entry "User:$workflow_user" "$workflow_pub_resources" "Pub"
verify_user "$ticket_user"
verify_user "$workflow_user"
verify_acl_resources "User:$ticket_user" "$ticket_pub_resources" "Pub"
verify_acl_resources "User:$ticket_user" "$ticket_sub_resources" "Sub"
ticket_acl_count=13
workflow_acl_count=8
if [ "${FLOWDESK_ROCKETMQ_M7_ENABLED:-false}" = "true" ]; then ticket_acl_count=$((ticket_acl_count + 13)); fi
if [ "${FLOWDESK_ROCKETMQ_M8_ENABLED:-false}" = "true" ]; then
  ticket_acl_count=$((ticket_acl_count + 1))
  workflow_acl_count=$((workflow_acl_count + 1))
fi
verify_acl_count "User:$ticket_user" "$ticket_acl_count"
verify_acl_resources "User:$workflow_user" "$workflow_sub_resources" "Sub"
verify_acl_resources "User:$workflow_user" "$workflow_pub_resources" "Pub"
verify_acl_count "User:$workflow_user" "$workflow_acl_count"
if [ "${FLOWDESK_ROCKETMQ_M7_ENABLED:-false}" = "true" ]; then
  reset_acl "User:$alert_user"
  create_acl_entry "User:$alert_user" "$alert_pub_resources" "Pub"
  create_acl_entry "User:$alert_user" "$alert_sub_resources" "Sub"
  verify_user "$alert_user"
  verify_acl_resources "User:$alert_user" "$alert_pub_resources" "Pub"
  verify_acl_resources "User:$alert_user" "$alert_sub_resources" "Sub"
  verify_acl_count "User:$alert_user" 8
  # Exercise the Broker authorization path, not just metadata inspection. These
  # two identities may subscribe to the target Topic but must never publish it.
  expect_publish_denied "M7" "ticket" "$ticket_user" \
    "$FLOWDESK_TICKET_ROCKETMQ_SECRET_KEY" "$create_topic"
  expect_publish_denied "M7" "alert" "$alert_user" \
    "$FLOWDESK_ALERT_ROCKETMQ_SECRET_KEY" "$established_topic"
  printf 'accessKey: %s\nsecretKey: %s\n' \
    "$FLOWDESK_ROCKETMQ_ADMIN_ACCESS_KEY" \
    "$FLOWDESK_ROCKETMQ_ADMIN_SECRET_KEY" > ../conf/tools.yml
fi
if [ "${FLOWDESK_ROCKETMQ_M8_ENABLED:-false}" = "true" ]; then
  reset_acl "User:$notification_user"
  create_acl_entry "User:$notification_user" "$notification_sub_resources" "Sub"
  verify_user "$notification_user"
  verify_acl_resources "User:$notification_user" "$notification_sub_resources" "Sub"
  verify_acl_count "User:$notification_user" 12
  # Each producer can publish only its own frozen Notification topic, while
  # Notification itself is subscription-only.
  expect_publish_denied "M8" "ticket" "$ticket_user" \
    "$FLOWDESK_TICKET_ROCKETMQ_SECRET_KEY" "$workflow_notification_topic"
  expect_publish_denied "M8" "workflow" "$workflow_user" \
    "$FLOWDESK_WORKFLOW_ROCKETMQ_SECRET_KEY" "$ticket_notification_topic"
  expect_publish_denied "M8" "notification" "$notification_user" \
    "$FLOWDESK_NOTIFICATION_ROCKETMQ_SECRET_KEY" "$ticket_notification_topic"
  printf 'accessKey: %s\nsecretKey: %s\n' \
    "$FLOWDESK_ROCKETMQ_ADMIN_ACCESS_KEY" \
    "$FLOWDESK_ROCKETMQ_ADMIN_SECRET_KEY" > ../conf/tools.yml
fi

# Deliberately report identities/resources only, never credential material.
printf '%s\n' "FLOWDESK_ROCKETMQ_ACL_OK broker=$broker_address startProducer=$ticket_user startConsumer=$workflow_user reverseProducer=$workflow_user reverseConsumer=$ticket_user reverseTopics=$instance_topic,$final_topic"
if [ "${FLOWDESK_ROCKETMQ_M7_ENABLED:-false}" = "true" ]; then
  printf '%s\n' "FLOWDESK_ROCKETMQ_M7_ACL_OK broker=$broker_address alert=$alert_user ticket=$ticket_user topics=$create_topic,$established_topic,$recovered_topic"
fi
if [ "${FLOWDESK_ROCKETMQ_M8_ENABLED:-false}" = "true" ]; then
  printf '%s\n' "FLOWDESK_ROCKETMQ_M8_ACL_OK broker=$broker_address ticket=$ticket_user workflow=$workflow_user notification=$notification_user topics=$ticket_notification_topic,$workflow_notification_topic"
fi
