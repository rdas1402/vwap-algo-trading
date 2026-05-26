#!/usr/bin/env bash
set -euo pipefail

ENV_FILE="${ENV_FILE:-/opt/vwap-algo-trading/config/vwap.env}"
APP_CONFIG="${APP_CONFIG:-/opt/vwap-algo-trading/config/application.properties}"

set -a
source "${ENV_FILE}"
set +a

LOG_FILE="${APP_HOME}/logs/trading_start.log"
JAR_PATH="${APP_HOME}/current/${JAR_NAME}"
MAIN_CLASS="com.trading.TradingApplication"

mkdir -p "$(dirname "${LOG_FILE}")"
mkdir -p "${HOME}/vwap-algo-trading/config"
ln -sf "${APP_CONFIG}" "${HOME}/vwap-algo-trading/config/application.properties"

log() {
  echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" | tee -a "${LOG_FILE}"
}

is_trading_running() {
  ps -ef | grep "[j]ava .*${MAIN_CLASS}" >/dev/null 2>&1
}

if is_trading_running; then
  log "Trading application already running. Skipping duplicate start."
  exit 0
fi

cd "${APP_HOME}"
log "Starting trading application"
log "Trading application started successfully"
exec "${JAVA_BIN}" -cp "${JAR_PATH}" "${MAIN_CLASS}" >> "${LOG_FILE}" 2>&1
