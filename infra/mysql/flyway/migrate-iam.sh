#!/usr/bin/env bash
set -Eeuo pipefail

# Resolve previously applied demo migrations when upgrading an existing demo
# database. A new or production database only discovers formal migrations.
export FLYWAY_LOCATIONS="filesystem:/flyway/sql"
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
  export FLYWAY_LOCATIONS="filesystem:/flyway/sql,filesystem:/flyway/demo"
fi
exec /flyway/flyway "$@"
