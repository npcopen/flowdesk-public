#!/usr/bin/env sh
set -eu

probe_key=flowdesk:m2:probe
probe_value=FLOWDESK_REDIS_PROBE

export REDISCLI_AUTH="$FLOWDESK_REDIS_PASSWORD"
redis-cli -h redis SET "$probe_key" "$probe_value" EX 60 >/dev/null
actual_value="$(redis-cli -h redis --raw GET "$probe_key")"
redis-cli -h redis DEL "$probe_key" >/dev/null

if [ "$actual_value" != "$probe_value" ]; then
  echo "Redis round-trip returned an unexpected value" >&2
  exit 1
fi

echo FLOWDESK_REDIS_ROUND_TRIP_OK
