#!/usr/bin/env bash
set -euo pipefail

ENV_FILE="${ENV_FILE:-/opt/vwap-algo-trading/config/vwap.env}"

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "Environment file not found: ${ENV_FILE}" >&2
  exit 1
fi

set -a
source "${ENV_FILE}"
set +a

if [[ -z "${KEY_VAULT_NAME:-}" ]]; then
  echo "KEY_VAULT_NAME is not set" >&2
  exit 1
fi

if ! az account show >/dev/null 2>&1; then
  az login --identity >/dev/null
fi

export ZERODHA_USER_ID
ZERODHA_USER_ID="$(az keyvault secret show --vault-name "${KEY_VAULT_NAME}" --name zerodha-user-id --query value -o tsv)"

export ZERODHA_PASSWORD
ZERODHA_PASSWORD="$(az keyvault secret show --vault-name "${KEY_VAULT_NAME}" --name zerodha-password --query value -o tsv)"

export ZERODHA_PIN
ZERODHA_PIN="$(az keyvault secret show --vault-name "${KEY_VAULT_NAME}" --name zerodha-pin --query value -o tsv)"

export ZERODHA_TOTP_SECRET
ZERODHA_TOTP_SECRET="$(az keyvault secret show --vault-name "${KEY_VAULT_NAME}" --name zerodha-totp-secret --query value -o tsv)"
