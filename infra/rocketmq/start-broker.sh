#!/usr/bin/env sh
set -eu

template=/home/rocketmq/rocketmq-5.3.4/conf/flowdesk-broker.conf
runtime=/tmp/flowdesk-broker-runtime.conf
acl_enabled="${FLOWDESK_ROCKETMQ_ACL_ENABLED:-false}"
advertised_host="${FLOWDESK_ROCKETMQ_BROKER_ADVERTISED_HOST:-127.0.0.1}"
vip_port="${FLOWDESK_ROCKETMQ_BROKER_VIP_PUBLISH_PORT:-10909}"
listen_port="${FLOWDESK_ROCKETMQ_BROKER_PUBLISH_PORT:-10911}"
ha_port="${FLOWDESK_ROCKETMQ_BROKER_HA_PUBLISH_PORT:-10912}"

fail() {
  printf '%s\n' "FLOWDESK_ROCKETMQ_BROKER_CONFIG_FAILED: $*" >&2
  exit 1
}

validate_alphanumeric() {
  label="$1"
  value="$2"
  case "$value" in
    ''|*[!A-Za-z0-9]*) fail "$label must contain ASCII letters and digits only" ;;
  esac
}

case "$advertised_host" in
  ''|*[!A-Za-z0-9._-]*) fail "broker advertised host contains unsupported characters" ;;
esac

validate_port() {
  label="$1"
  value="$2"
  case "$value" in
    ''|*[!0-9]*) fail "$label must be a numeric TCP port" ;;
  esac
  [ "$value" -ge 1 ] && [ "$value" -le 65535 ] ||
    fail "$label must be between 1 and 65535"
}

validate_port "Broker VIP port" "$vip_port"
validate_port "Broker listen port" "$listen_port"
validate_port "Broker HA port" "$ha_port"
expected_vip_port=$((listen_port - 2))
[ "$expected_vip_port" -ge 1 ] && [ "$vip_port" -eq "$expected_vip_port" ] ||
  fail "Broker VIP port must equal listen port minus 2"
[ "$ha_port" -ne "$listen_port" ] || fail "Broker HA port must differ from listen port"

render_config() {
  sed -e "s/^brokerIP1=.*/brokerIP1=$advertised_host/" \
      -e "s/^brokerIP2=.*/brokerIP2=$advertised_host/" \
      -e "s/^listenPort=.*/listenPort=$listen_port/" \
      -e "s/^haListenPort=.*/haListenPort=$ha_port/" "$template"
}

umask 077
if [ "$acl_enabled" = "true" ]; then
  admin_key="${FLOWDESK_ROCKETMQ_ADMIN_ACCESS_KEY:-}"
  admin_secret="${FLOWDESK_ROCKETMQ_ADMIN_SECRET_KEY:-}"
  inner_key="${FLOWDESK_ROCKETMQ_INNER_ACCESS_KEY:-}"
  inner_secret="${FLOWDESK_ROCKETMQ_INNER_SECRET_KEY:-}"
  validate_alphanumeric "admin AccessKey" "$admin_key"
  validate_alphanumeric "admin SecretKey" "$admin_secret"
  validate_alphanumeric "inner AccessKey" "$inner_key"
  validate_alphanumeric "inner SecretKey" "$inner_secret"
  [ "$admin_key" = "flowdeskmqadmin" ] || fail "admin AccessKey is frozen"
  [ "$inner_key" = "flowdeskbrokerinner" ] || fail "inner AccessKey is frozen"
  [ "${#admin_secret}" -ge 16 ] || fail "admin SecretKey must contain at least 16 characters"
  [ "${#inner_secret}" -ge 16 ] || fail "inner SecretKey must contain at least 16 characters"
  [ "$admin_secret" != "$inner_secret" ] || fail "admin and inner SecretKeys must differ"

  {
    render_config
    printf '%s\n' \
      'authenticationEnabled=true' \
      'authenticationMetadataProvider=org.apache.rocketmq.auth.authentication.provider.LocalAuthenticationMetadataProvider' \
      'authenticationStrategy=org.apache.rocketmq.auth.authentication.strategy.StatefulAuthenticationStrategy' \
      'authorizationEnabled=true' \
      'authorizationMetadataProvider=org.apache.rocketmq.auth.authorization.provider.LocalAuthorizationMetadataProvider' \
      'authorizationStrategy=org.apache.rocketmq.auth.authorization.strategy.StatefulAuthorizationStrategy' \
      "initAuthenticationUser={\"username\":\"$admin_key\",\"password\":\"$admin_secret\"}" \
      "innerClientAuthenticationCredentials={\"accessKey\":\"$inner_key\",\"secretKey\":\"$inner_secret\"}"
  } > "$runtime"
else
  [ "$acl_enabled" = "false" ] || fail "FLOWDESK_ROCKETMQ_ACL_ENABLED must be true or false"
  render_config > "$runtime"
fi

exec sh mqbroker --enable-proxy -c "$runtime"
