#!/usr/bin/env bash
set -euo pipefail

ENV_FILE="${ENV_FILE:-/opt/vwap-algo-trading/config/vwap.env}"
APP_CONFIG="${APP_CONFIG:-/opt/vwap-algo-trading/config/application.properties}"

set -a
source "${ENV_FILE}"
set +a

source "${APP_HOME}/bin/fetch_keyvault_secrets.sh"

LOG_FILE="${APP_HOME}/logs/token_refresh.log"
MAIN_CLASS="com.trading.util.AutoZerodhaLoginHelper"
JAR_PATH="${APP_HOME}/current/${JAR_NAME}"
OVERRIDE_CLASSES_DIR="${APP_HOME}/overrides"
LOCK_FILE="/tmp/vwap_refresh_token.lock"

mkdir -p "$(dirname "${LOG_FILE}")"
mkdir -p "${APP_HOME}/screenshots"
mkdir -p "${OVERRIDE_CLASSES_DIR}"
mkdir -p "${HOME}/vwap-algo-trading/config"
ln -sf "${APP_CONFIG}" "${HOME}/vwap-algo-trading/config/application.properties"

exec 9>"${LOCK_FILE}"
if ! flock -n 9; then
  echo "[$(date '+%Y-%m-%d %H:%M:%S')] Token refresh is already running" >> "${LOG_FILE}"
  exit 0
fi

log() {
  echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" | tee -a "${LOG_FILE}"
}

validate_saved_token() {
  local api_key
  local access_token
  local status
  local body

  api_key="$(grep '^zerodha.api.key=' "${APP_CONFIG}" | tail -n 1 | cut -d= -f2- | tr -d '\r' | xargs)"
  access_token="$(grep '^zerodha.access.token=' "${APP_CONFIG}" | tail -n 1 | cut -d= -f2- | tr -d '\r' | xargs)"

  if [[ -z "${api_key}" || -z "${access_token}" ]]; then
    log "Token validation failed - API key or access token missing in config"
    return 1
  fi

  body="$(mktemp)"
  status="$(curl -sS --max-time 20 -o "${body}" -w "%{http_code}" \
    "https://api.kite.trade/user/profile" \
    -H "X-Kite-Version: 3" \
    -H "Authorization: token ${api_key}:${access_token}" || true)"

  if [[ "${status}" == "200" ]]; then
    rm -f "${body}"
    return 0
  fi

  log "Token validation failed with HTTP ${status}: $(tr '\n' ' ' < "${body}")"
  rm -f "${body}"
  return 1
}

config_mtime() {
  if [[ -f "${APP_CONFIG}" ]]; then
    stat -c %Y "${APP_CONFIG}"
  else
    echo 0
  fi
}

current_run_saved_token() {
  local start_line="$1"
  tail -n +"$((start_line + 1))" "${LOG_FILE}" | grep -q "Token saved. Trading will start at scheduled time."
}

export PATH
PATH="/usr/local/bin:/usr/bin:/bin:${PATH}"
export CHROME_BIN="${CHROME_BINARY}"
export WEBDRIVER_CHROME_DRIVER="${CHROMEDRIVER_PATH}"
JAVA_CP="${OVERRIDE_CLASSES_DIR}:${JAR_PATH}"

cd "${APP_HOME}"

log "Starting token refresh with ${TOKEN_REFRESH_MAX_RETRIES} max retries"

attempt=1
while [[ "${attempt}" -le "${TOKEN_REFRESH_MAX_RETRIES}" ]]; do
  log "Attempt ${attempt}/${TOKEN_REFRESH_MAX_RETRIES}"
  log_start_line=0
  if [[ -f "${LOG_FILE}" ]]; then
    log_start_line="$(wc -l < "${LOG_FILE}")"
  fi
  config_mtime_before="$(config_mtime)"

  run_started_at="$(date +%s)"
  if timeout 120s "${JAVA_BIN}" -cp "${JAVA_CP}" "${MAIN_CLASS}" test >> "${LOG_FILE}" 2>&1; then
    config_mtime_after="$(config_mtime)"
    if current_run_saved_token "${log_start_line}" && [[ "${config_mtime_after}" -gt "${config_mtime_before}" ]] && validate_saved_token; then
      log "Token refresh succeeded"
      exit 0
    fi
  fi

  config_mtime_after="$(config_mtime)"
  if current_run_saved_token "${log_start_line}" && [[ "${config_mtime_after}" -gt "${config_mtime_before}" ]]; then
    pkill -f "${MAIN_CLASS}" >/dev/null 2>&1 || true
    if validate_saved_token; then
      log "Token refresh succeeded"
      exit 0
    fi
  fi

  log "Token refresh failed on attempt ${attempt} after $(( $(date +%s) - run_started_at ))s"
  if [[ "${attempt}" -lt "${TOKEN_REFRESH_MAX_RETRIES}" ]]; then
    sleep "${TOKEN_REFRESH_RETRY_INTERVAL}"
  fi
  attempt=$((attempt + 1))
done

log "Token refresh failed after all retries"
exit 1
