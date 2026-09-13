#!/usr/bin/env bash
set -Eeuo pipefail

formal_locations="filesystem:/flyway/sql"
demo_locations="filesystem:/flyway/sql,filesystem:/flyway/demo"

# A persisted local demo schema contains VyyyyMMdd.9xx history. Include that
# location only when such a row already exists; production schemas never
# discover demo migrations.
export FLYWAY_LOCATIONS="$formal_locations"
info_json="$(
  /flyway/flyway \
    -connectRetries=30 \
    -validateMigrationNaming=true \
    -cleanDisabled=true \
    -outputType=json \
    info
)"

if printf '%s\n' "$info_json" \
    | grep -Eq '"version"[[:space:]]*:[[:space:]]*"[0-9]{8}\.9[0-9]{2}"'; then
  export FLYWAY_LOCATIONS="$demo_locations"
fi

exec /flyway/flyway "$@"
