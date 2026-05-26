#!/usr/bin/env bash
set -euo pipefail

ENV_FILE="${ENV_FILE:-/opt/vwap-algo-trading/config/vwap.env}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEMPLATE_DIR="$(cd "${SCRIPT_DIR}/../systemd" && pwd)"

set -a
source "${ENV_FILE}"
set +a

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

for unit in vwap-refresh-token.service vwap-start-trading.service; do
  sed \
    -e "s|__APP_USER__|${APP_USER}|g" \
    -e "s|__APP_GROUP__|${APP_GROUP}|g" \
    "${TEMPLATE_DIR}/${unit}" > "${TMP_DIR}/${unit}"
done

cp "${TEMPLATE_DIR}/vwap-refresh-token.timer" "${TMP_DIR}/vwap-refresh-token.timer"
cp "${TEMPLATE_DIR}/vwap-start-trading.timer" "${TMP_DIR}/vwap-start-trading.timer"

sudo install -m 0644 "${TMP_DIR}/vwap-refresh-token.service" /etc/systemd/system/vwap-refresh-token.service
sudo install -m 0644 "${TMP_DIR}/vwap-start-trading.service" /etc/systemd/system/vwap-start-trading.service
sudo install -m 0644 "${TMP_DIR}/vwap-refresh-token.timer" /etc/systemd/system/vwap-refresh-token.timer
sudo install -m 0644 "${TMP_DIR}/vwap-start-trading.timer" /etc/systemd/system/vwap-start-trading.timer

sudo systemctl daemon-reload
echo "systemd units installed."
