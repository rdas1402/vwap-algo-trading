#!/usr/bin/env bash
set -euo pipefail

APP_HOME="${APP_HOME:-/opt/vwap-algo-trading}"
HELPER_SOURCE="${1:-/tmp/AutoZerodhaLoginHelper.java}"
SCRIPT_SOURCE="${2:-/tmp/refresh_token_retry.sh}"

mkdir -p "${APP_HOME}/overrides/com/trading/util"
mkdir -p "${APP_HOME}/bin/backup"

if [[ -f "${APP_HOME}/bin/refresh_token_retry.sh" ]]; then
  cp "${APP_HOME}/bin/refresh_token_retry.sh" "${APP_HOME}/bin/backup/refresh_token_retry.sh.bak"
fi

install -m 755 "${SCRIPT_SOURCE}" "${APP_HOME}/bin/refresh_token_retry.sh"

javac \
  -cp "${APP_HOME}/current/vwap-algo-trading-1.0-SNAPSHOT.jar" \
  -d "${APP_HOME}/overrides" \
  "${HELPER_SOURCE}"

ls -l "${APP_HOME}/bin/refresh_token_retry.sh"
ls -l "${APP_HOME}/overrides/com/trading/util/AutoZerodhaLoginHelper.class"
