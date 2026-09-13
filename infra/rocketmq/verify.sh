#!/usr/bin/env sh
set -eu

configure_admin_credentials() {
  [ "${FLOWDESK_ROCKETMQ_ACL_ENABLED:-false}" = "true" ] || return 0
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
}

nameserver="${FLOWDESK_ROCKETMQ_VERIFY_NAME_SERVER:-rocketmq-nameserver:9876}"
cluster="FlowDeskLocalCluster"
broker_name="flowdesk-broker"
broker_host="${FLOWDESK_ROCKETMQ_VERIFY_BROKER_HOST:-127.0.0.1}"
broker_vip_port="${FLOWDESK_ROCKETMQ_BROKER_VIP_PUBLISH_PORT:-10909}"
broker_port="${FLOWDESK_ROCKETMQ_BROKER_PUBLISH_PORT:-10911}"
broker_ha_port="${FLOWDESK_ROCKETMQ_BROKER_HA_PUBLISH_PORT:-10912}"
broker_address="${FLOWDESK_ROCKETMQ_VERIFY_BROKER_ADDRESS:-$broker_host:$broker_port}"
container_topic="flowdesk_infra_probe"
container_group="flowdesk_infra_probe_group"
host_topic="flowdesk_host_smoke"
host_group="flowdesk_host_smoke_group"
business_topic="flowdesk_workflow_start_v1"
business_group="flowdesk_workflow_start_v1_workflow_cg"
dlq_acceptor_group="flowdesk_workflow_start_v1_workflow_dlq_acceptor_cg"
business_retry_topic="%RETRY%$business_group"
business_dlq_topic="%DLQ%$business_group"
acceptor_retry_topic="%RETRY%$dlq_acceptor_group"
acceptor_dlq_topic="%DLQ%$dlq_acceptor_group"
instance_topic="flowdesk_workflow_instance_established_v1"
instance_group="flowdesk_workflow_instance_established_v1_ticket_cg"
instance_acceptor_group="flowdesk_workflow_instance_established_v1_ticket_dlq_acceptor_cg"
final_topic="flowdesk_workflow_approval_finalized_v1"
final_group="flowdesk_workflow_approval_finalized_v1_ticket_cg"
final_acceptor_group="flowdesk_workflow_approval_finalized_v1_ticket_dlq_acceptor_cg"
m7_create_topic="flowdesk_incident_ticket_create_requested_v1"
m7_create_group="flowdesk_incident_ticket_create_requested_v1_ticket_cg"
m7_create_acceptor_group="flowdesk_incident_ticket_create_requested_v1_ticket_dlq_acceptor_cg"
m7_established_topic="flowdesk_incident_ticket_established_v1"
m7_established_group="flowdesk_incident_ticket_established_v1_alert_cg"
m7_established_acceptor_group="flowdesk_incident_ticket_established_v1_alert_dlq_acceptor_cg"
m7_recovered_topic="flowdesk_incident_recovered_v1"
m7_recovered_group="flowdesk_incident_recovered_v1_ticket_cg"
m7_recovered_acceptor_group="flowdesk_incident_recovered_v1_ticket_dlq_acceptor_cg"
m8_ticket_notification_topic="flowdesk_ticket_notification_requested_v1"
m8_ticket_notification_group="flowdesk_ticket_notification_requested_v1_notification_cg"
m8_ticket_notification_acceptor_group="flowdesk_ticket_notification_requested_v1_notification_dlq_acceptor_cg"
m8_workflow_notification_topic="flowdesk_workflow_notification_requested_v1"
m8_workflow_notification_group="flowdesk_workflow_notification_requested_v1_notification_cg"
m8_workflow_notification_acceptor_group="flowdesk_workflow_notification_requested_v1_notification_dlq_acceptor_cg"
message_key="flowdesk-container-smoke-key"
message_body='{"eventType":"flowdesk.infrastructure.container-smoke","eventVersion":1,"payload":{"source":"broker-network"}}'

fail() {
  echo "FLOWDESK_ROCKETMQ_VERIFY_FAILED: $*" >&2
  exit 1
}

configure_admin_credentials

assert_tcp_port() {
  port="$1"
  if ! bash -c "exec 3<>/dev/tcp/127.0.0.1/$port; exec 3>&-; exec 3<&-"; then
    fail "Broker port 127.0.0.1:$port is not reachable from the Broker network namespace."
  fi
}

initialize_topic_and_group() {
  topic="$1"
  group="$2"
  read_queues="$3"
  write_queues="$4"
  retry_max_times="$5"
  sh mqadmin updateTopic \
    -n "$nameserver" -c "$cluster" -t "$topic" \
    -r "$read_queues" -w "$write_queues" >/dev/null
  sh mqadmin updateSubGroup \
    -n "$nameserver" -c "$cluster" -g "$group" \
    -s true -o false -r "$retry_max_times" >/dev/null
}

initialize_subscription_group() {
  group="$1"
  retry_max_times="$2"
  sh mqadmin updateSubGroup \
    -n "$nameserver" -c "$cluster" -g "$group" \
    -s true -o false -r "$retry_max_times" >/dev/null
}

initialize_internal_topic() {
  topic="$1"
  sh mqadmin updateTopic \
    -n "$nameserver" -c "$cluster" -t "$topic" \
    -r 1 -w 1 >/dev/null
}

initialize_frozen_binding() {
  frozen_topic="$1"
  frozen_group="$2"
  frozen_acceptor="$3"
  initialize_topic_and_group "$frozen_topic" "$frozen_group" 4 4 5
  initialize_subscription_group "$frozen_acceptor" 5
  initialize_internal_topic "%RETRY%$frozen_group"
  initialize_internal_topic "%DLQ%$frozen_group"
  initialize_internal_topic "%RETRY%$frozen_acceptor"
  initialize_internal_topic "%DLQ%$frozen_acceptor"
}

assert_subscription_group() {
  group="$1"
  group_config="$(sh mqadmin getConsumerConfig \
    -n "$nameserver" -g "$group")" || \
    fail "Consumer Group config is unavailable for $group."
  printf '%s\n' "$group_config" | grep -Fq "$group" || \
    fail "Consumer Group $group was not initialized."
  printf '%s\n' "$group_config" | grep -Eq 'retryMaxTimes[^0-9]*5' || \
    fail "Consumer Group $group retryMaxTimes is not 5."
  printf '%s\n' "$group_config" | grep -Eq 'consumeMessageOrderly[^a-zA-Z]*(false|FALSE)' || \
    fail "Consumer Group $group must remain unordered."
}

assert_frozen_binding() {
  frozen_topic="$1"
  frozen_group="$2"
  frozen_acceptor="$3"
  printf '%s\n' "$business_topics" | grep -Fxq "$frozen_topic" || \
    fail "Business Topic $frozen_topic was not initialized."
  for frozen_internal in "%RETRY%$frozen_group" "%DLQ%$frozen_group" \
      "%RETRY%$frozen_acceptor" "%DLQ%$frozen_acceptor"; do
    printf '%s\n' "$business_topics" | grep -Fxq "$frozen_internal" || \
      fail "Internal Topic $frozen_internal was not initialized."
  done
  frozen_route="$(sh mqadmin topicRoute -n "$nameserver" -t "$frozen_topic" -l)" || \
    fail "Business Topic route is unavailable for $frozen_topic."
  printf '%s\n' "$frozen_route" | awk \
    -v cluster="$cluster" -v broker="$broker_name" \
    '$1 == cluster && $2 == broker && $(NF - 2) == 4 && $(NF - 1) == 4 { found = 1 } END { exit !found }' || \
    fail "Business Topic $frozen_topic does not expose 4 read and 4 write queues."
  assert_subscription_group "$frozen_group"
  assert_subscription_group "$frozen_acceptor"
}

cluster_output="$(sh mqadmin clusterList -n "$nameserver")" || fail "NameServer query failed at $nameserver."
printf '%s\n' "$cluster_output" | grep -Fq "$cluster" || fail "Cluster $cluster is not registered."
printf '%s\n' "$cluster_output" | grep -Fq "$broker_name" || fail "Broker $broker_name is not registered."
printf '%s\n' "$cluster_output" | grep -Fq "$broker_address" || \
  fail "NameServer did not advertise the host-reachable Broker address $broker_address."

broker_config="$(sh mqadmin getBrokerConfig -n "$nameserver" -b "$broker_address")" || \
  fail "Broker management connection failed at $broker_address."
printf '%s\n' "$broker_config" | awk -F '=' -v expected="$broker_host" '
  {
    key = $1
    value = $2
    gsub(/^[[:space:]]+|[[:space:]]+$/, "", key)
    gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
    if (key == "brokerIP1" && value == expected) found = 1
  }
  END { exit(found ? 0 : 1) }
' || fail "brokerIP1 is not pinned to $broker_host."
printf '%s\n' "$broker_config" | grep -Eq '^autoCreateTopicEnable[[:space:]]*=[[:space:]]*false[[:space:]]*$' || \
  fail "Topic auto-creation must remain disabled."
printf '%s\n' "$broker_config" | grep -Eq '^autoCreateSubscriptionGroup[[:space:]]*=[[:space:]]*false[[:space:]]*$' || \
  fail "Subscription-group auto-creation must remain disabled."

assert_tcp_port "$broker_vip_port"
assert_tcp_port "$broker_port"
assert_tcp_port "$broker_ha_port"

# The two probes and the first frozen business contract all use explicitly
# initialized Topics and Groups. No runtime path relies on Broker auto-create.
initialize_topic_and_group "$container_topic" "$container_group" 1 1 3
initialize_topic_and_group "$host_topic" "$host_group" 1 1 3
initialize_topic_and_group "$business_topic" "$business_group" 4 4 5
initialize_subscription_group "$dlq_acceptor_group" 5
initialize_internal_topic "$business_retry_topic"
initialize_internal_topic "$business_dlq_topic"
initialize_internal_topic "$acceptor_retry_topic"
initialize_internal_topic "$acceptor_dlq_topic"
initialize_frozen_binding "$instance_topic" "$instance_group" "$instance_acceptor_group"
initialize_frozen_binding "$final_topic" "$final_group" "$final_acceptor_group"
if [ "${FLOWDESK_ROCKETMQ_M7_ENABLED:-false}" = "true" ]; then
  initialize_frozen_binding "$m7_create_topic" "$m7_create_group" "$m7_create_acceptor_group"
  initialize_frozen_binding "$m7_established_topic" "$m7_established_group" "$m7_established_acceptor_group"
  initialize_frozen_binding "$m7_recovered_topic" "$m7_recovered_group" "$m7_recovered_acceptor_group"
fi
if [ "${FLOWDESK_ROCKETMQ_M8_ENABLED:-false}" = "true" ]; then
  initialize_frozen_binding "$m8_ticket_notification_topic" "$m8_ticket_notification_group" "$m8_ticket_notification_acceptor_group"
  initialize_frozen_binding "$m8_workflow_notification_topic" "$m8_workflow_notification_group" "$m8_workflow_notification_acceptor_group"
fi

business_topics="$(sh mqadmin topicList -n "$nameserver")" || \
  fail "Business Topic list is unavailable."
printf '%s\n' "$business_topics" | grep -Fxq "$business_topic" || \
  fail "Business Topic $business_topic was not initialized."
for internal_topic in "$business_retry_topic" "$business_dlq_topic" \
    "$acceptor_retry_topic" "$acceptor_dlq_topic"; do
  printf '%s\n' "$business_topics" | grep -Fxq "$internal_topic" || \
    fail "Internal Topic $internal_topic was not initialized."
done

business_route="$(sh mqadmin topicRoute -n "$nameserver" -t "$business_topic" -l)" || \
  fail "Business Topic route is unavailable for $business_topic."
printf '%s\n' "$business_route" | awk \
  -v cluster="$cluster" -v broker="$broker_name" \
  '$1 == cluster && $2 == broker && $(NF - 2) == 4 && $(NF - 1) == 4 { found = 1 } END { exit !found }' || \
  fail "Business Topic $business_topic does not expose 4 read and 4 write queues."

assert_subscription_group "$business_group"
assert_subscription_group "$dlq_acceptor_group"
assert_frozen_binding "$instance_topic" "$instance_group" "$instance_acceptor_group"
assert_frozen_binding "$final_topic" "$final_group" "$final_acceptor_group"
if [ "${FLOWDESK_ROCKETMQ_M7_ENABLED:-false}" = "true" ]; then
  assert_frozen_binding "$m7_create_topic" "$m7_create_group" "$m7_create_acceptor_group"
  assert_frozen_binding "$m7_established_topic" "$m7_established_group" "$m7_established_acceptor_group"
  assert_frozen_binding "$m7_recovered_topic" "$m7_recovered_group" "$m7_recovered_acceptor_group"
fi
if [ "${FLOWDESK_ROCKETMQ_M8_ENABLED:-false}" = "true" ]; then
  assert_frozen_binding "$m8_ticket_notification_topic" "$m8_ticket_notification_group" "$m8_ticket_notification_acceptor_group"
  assert_frozen_binding "$m8_workflow_notification_topic" "$m8_workflow_notification_group" "$m8_workflow_notification_acceptor_group"
fi

send_output="$(sh mqadmin sendMessage \
  -n "$nameserver" -t "$container_topic" -k "$message_key" -p "$message_body")"
printf '%s\n' "$send_output" | grep -q SEND_OK || fail "Container producer probe did not return SEND_OK."

consume_output="$(sh mqadmin consumeMessage \
  -n "$nameserver" -t "$container_topic" -g "$container_group" -c 1)"
printf '%s\n' "$consume_output" | grep -Fq "BODY: $message_body" || \
  fail "Container consumer probe did not receive the expected event envelope."

printf '%s\n' 'FLOWDESK_ROCKETMQ_NAMESERVER_OK'
printf '%s\n' "FLOWDESK_ROCKETMQ_BROKER_ADDRESS_OK $broker_address"
printf '%s\n' "FLOWDESK_ROCKETMQ_BROKER_PORTS_OK $broker_vip_port,$broker_port,$broker_ha_port"
printf '%s\n' 'FLOWDESK_ROCKETMQ_CONTROLLED_INITIALIZATION_OK'
printf '%s\n' "FLOWDESK_ROCKETMQ_M5_TOPOLOGY_OK topic=$business_topic group=$business_group dlqAcceptorGroup=$dlq_acceptor_group retryMaxTimes=5 orderly=false internalTopics=4"
printf '%s\n' "FLOWDESK_ROCKETMQ_M6_TOPOLOGY_OK topics=$instance_topic,$final_topic mainGroups=$instance_group,$final_group acceptorGroups=$instance_acceptor_group,$final_acceptor_group retryMaxTimes=5 orderly=false internalTopics=8"
if [ "${FLOWDESK_ROCKETMQ_M7_ENABLED:-false}" = "true" ]; then
  printf '%s\n' "FLOWDESK_ROCKETMQ_M7_TOPOLOGY_OK topics=$m7_create_topic,$m7_established_topic,$m7_recovered_topic mainGroups=$m7_create_group,$m7_established_group,$m7_recovered_group acceptorGroups=$m7_create_acceptor_group,$m7_established_acceptor_group,$m7_recovered_acceptor_group retryMaxTimes=5 orderly=false internalTopics=12"
fi
if [ "${FLOWDESK_ROCKETMQ_M8_ENABLED:-false}" = "true" ]; then
  printf '%s\n' "FLOWDESK_ROCKETMQ_M8_TOPOLOGY_OK topics=$m8_ticket_notification_topic,$m8_workflow_notification_topic mainGroups=$m8_ticket_notification_group,$m8_workflow_notification_group acceptorGroups=$m8_ticket_notification_acceptor_group,$m8_workflow_notification_acceptor_group retryMaxTimes=5 orderly=false internalTopics=8"
fi
printf '%s\n' 'FLOWDESK_ROCKETMQ_ROUND_TRIP_OK'
