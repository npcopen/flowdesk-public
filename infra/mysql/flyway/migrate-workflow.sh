#!/usr/bin/env bash
set -Eeuo pipefail

formal_locations="filesystem:/flyway/sql"
demo_locations="filesystem:/flyway/sql,filesystem:/flyway/demo"

# Production and other non-demo schemas must never discover demo migrations.
# A persisted demo schema already contains VyyyyMMdd.9xx rows in Flyway history;
# only then include the demo location so strict validation can resolve those rows.
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
