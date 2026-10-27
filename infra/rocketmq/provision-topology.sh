#!/usr/bin/env sh
set -eu

# Provision business Topics and subscription Groups without publishing messages.
nameserver="${FLOWDESK_ROCKETMQ_ADMIN_NAME_SERVER:-rocketmq-nameserver:9876}"
broker_host="${FLOWDESK_ROCKETMQ_ADMIN_BROKER_HOST:-127.0.0.1}"
broker_port="${FLOWDESK_ROCKETMQ_BROKER_PUBLISH_PORT:-10911}"
broker_address="${FLOWDESK_ROCKETMQ_ADMIN_BROKER_ADDRESS:-$broker_host:$broker_port}"
cluster="FlowDeskLocalCluster"
broker_name="flowdesk-broker"

# 输出当前脚本的稳定失败标记及诊断并退出，阻止后续初始化或验证继续执行。
fail() {
  printf '%s\n' "FLOWDESK_ROCKETMQ_TOPOLOGY_PROVISION_FAILED: $*" >&2
  exit 1
}

case "${FLOWDESK_ROCKETMQ_ACL_ENABLED:-false}" in
  true)
    : "${FLOWDESK_ROCKETMQ_ADMIN_ACCESS_KEY:?set FLOWDESK_ROCKETMQ_ADMIN_ACCESS_KEY}"
    : "${FLOWDESK_ROCKETMQ_ADMIN_SECRET_KEY:?set FLOWDESK_ROCKETMQ_ADMIN_SECRET_KEY}"
    case "$FLOWDESK_ROCKETMQ_ADMIN_ACCESS_KEY$FLOWDESK_ROCKETMQ_ADMIN_SECRET_KEY" in
      *[!A-Za-z0-9]*) fail "RocketMQ admin credentials must be alphanumeric" ;;
    esac
    [ "$FLOWDESK_ROCKETMQ_ADMIN_ACCESS_KEY" = "flowdeskmqadmin" ] ||
      fail "RocketMQ admin AccessKey is frozen"
    [ "${#FLOWDESK_ROCKETMQ_ADMIN_SECRET_KEY}" -ge 16 ] ||
      fail "RocketMQ admin SecretKey must contain at least 16 characters"
    umask 077
    printf 'accessKey: %s\nsecretKey: %s\n' \
      "$FLOWDESK_ROCKETMQ_ADMIN_ACCESS_KEY" \
      "$FLOWDESK_ROCKETMQ_ADMIN_SECRET_KEY" > ../conf/tools.yml
    ;;
  false) ;;
  *) fail "FLOWDESK_ROCKETMQ_ACL_ENABLED must be true or false" ;;
esac

mqadmin_output=''
# 执行管理命令并保存输出，同时检查退出码和文本错误标记，避免 mqadmin 返回零仍误报成功。
run_mqadmin() {
  operation="$1"
  shift
  mqadmin_status=0
  mqadmin_output="$(sh mqadmin "$@" 2>&1)" || mqadmin_status=$?
  [ "$mqadmin_status" -eq 0 ] || fail "$operation (mqadmin exit status $mqadmin_status)"
  if printf '%s\n' "$mqadmin_output" | grep -Eiq \
    'SubCommandException|Caused by:|CODE:[[:space:]]*[1-9][0-9]*|command failed|system error|Remoting[A-Za-z]*Exception'; then
    fail "$operation (mqadmin reported an error despite exit status 0)"
  fi
}

bindings='flowdesk_workflow_start_v1
flowdesk_workflow_instance_established_v1
flowdesk_workflow_approval_finalized_v1'
if [ "${FLOWDESK_ROCKETMQ_M7_ENABLED:-false}" = "true" ]; then
  bindings="$bindings
flowdesk_incident_ticket_create_requested_v1
flowdesk_incident_ticket_established_v1
flowdesk_incident_recovered_v1"
fi
if [ "${FLOWDESK_ROCKETMQ_M8_ENABLED:-false}" = "true" ]; then
  bindings="$bindings
flowdesk_ticket_notification_requested_v1
flowdesk_workflow_notification_requested_v1"
fi

# 按冻结的主题归属确定消费服务，再派生业务消费组及死信接收组，未知主题立即拒绝。
binding_groups() {
  case "$1" in
    flowdesk_workflow_start_v1) consumer_service=workflow ;;
    flowdesk_workflow_instance_established_v1|flowdesk_workflow_approval_finalized_v1|\
    flowdesk_incident_ticket_create_requested_v1|flowdesk_incident_recovered_v1) consumer_service=ticket ;;
    flowdesk_incident_ticket_established_v1) consumer_service=alert ;;
    flowdesk_ticket_notification_requested_v1|flowdesk_workflow_notification_requested_v1) consumer_service=notification ;;
    *) fail "Unknown business Topic $1" ;;
  esac
  group="${1}_${consumer_service}_cg"
  acceptor="${1}_${consumer_service}_dlq_acceptor_cg"
}

for topic in $bindings; do
  binding_groups "$topic"
  run_mqadmin "create business Topic $topic" updateTopic \
    -n "$nameserver" -b "$broker_address" -t "$topic" -r 4 -w 4
  for subscription_group in "$group" "$acceptor"; do
    run_mqadmin "create Group $subscription_group" updateSubGroup \
      -n "$nameserver" -b "$broker_address" -g "$subscription_group" -s true -o false -r 5
    for prefix in '%RETRY%' '%DLQ%'; do
      internal_topic="$prefix$subscription_group"
      run_mqadmin "create internal Topic $internal_topic" updateTopic \
        -n "$nameserver" -b "$broker_address" -t "$internal_topic" -r 1 -w 1
    done
  done
done

run_mqadmin "read Topic list" topicList -n "$nameserver"
topics="$mqadmin_output"
for topic in $bindings; do
  binding_groups "$topic"
  printf '%s\n' "$topics" | grep -Fxq "$topic" || fail "Missing business Topic $topic"
  for subscription_group in "$group" "$acceptor"; do
    for prefix in '%RETRY%' '%DLQ%'; do
      internal_topic="$prefix$subscription_group"
      printf '%s\n' "$topics" | grep -Fxq "$internal_topic" || fail "Missing internal Topic $internal_topic"
    done
    run_mqadmin "read Group $subscription_group" getConsumerConfig -n "$nameserver" -g "$subscription_group"
    printf '%s\n' "$mqadmin_output" | grep -Fq "$subscription_group" || fail "Missing Group $subscription_group"
    printf '%s\n' "$mqadmin_output" | grep -Eq 'retryMaxTimes[^0-9]*5' || fail "Group $subscription_group retryMaxTimes must be 5"
    printf '%s\n' "$mqadmin_output" | grep -Eq 'consumeMessageOrderly[^a-zA-Z]*(false|FALSE)' || fail "Group $subscription_group must be unordered"
  done
  run_mqadmin "read business Topic route $topic" topicRoute -n "$nameserver" -t "$topic" -l
  printf '%s\n' "$mqadmin_output" | awk -v cluster="$cluster" -v broker="$broker_name" \
    '$1 == cluster && $2 == broker && $(NF - 2) == 4 && $(NF - 1) == 4 { found = 1 } END { exit !found }' ||
    fail "Business Topic $topic must have 4 read and 4 write queues"
  printf '%s\n' "FLOWDESK_ROCKETMQ_BINDING_PROVISIONED topic=$topic group=$group dlqAcceptorGroup=$acceptor"
done

printf '%s\n' "FLOWDESK_ROCKETMQ_TOPOLOGY_OK broker=$broker_address messageProbes=none"
