#!/usr/bin/env bash
set -euo pipefail

: "${AZURE_LOCATION:=centralindia}"
: "${AZURE_RESOURCE_GROUP:=vwap-trading-rg}"
: "${AZURE_VM_NAME:=vwap-trading-vm}"
: "${AZURE_ADMIN_USER:=azureuser}"
: "${AZURE_VM_SIZE:=Standard_D4s_v5}"
: "${AZURE_KEY_VAULT_NAME:=}"

if [[ -z "${AZURE_KEY_VAULT_NAME}" ]]; then
  echo "AZURE_KEY_VAULT_NAME must be set" >&2
  exit 1
fi

az group create \
  --name "${AZURE_RESOURCE_GROUP}" \
  --location "${AZURE_LOCATION}"

az vm create \
  --resource-group "${AZURE_RESOURCE_GROUP}" \
  --name "${AZURE_VM_NAME}" \
  --image Ubuntu2204 \
  --admin-username "${AZURE_ADMIN_USER}" \
  --generate-ssh-keys \
  --size "${AZURE_VM_SIZE}" \
  --public-ip-sku Standard

az vm identity assign \
  --resource-group "${AZURE_RESOURCE_GROUP}" \
  --name "${AZURE_VM_NAME}"

PRINCIPAL_ID="$(
  az vm show \
    --resource-group "${AZURE_RESOURCE_GROUP}" \
    --name "${AZURE_VM_NAME}" \
    --query identity.principalId \
    -o tsv
)"

KEY_VAULT_ID="$(
  az keyvault show \
    --name "${AZURE_KEY_VAULT_NAME}" \
    --query id \
    -o tsv
)"

az role assignment create \
  --assignee-object-id "${PRINCIPAL_ID}" \
  --assignee-principal-type ServicePrincipal \
  --role "Key Vault Secrets User" \
  --scope "${KEY_VAULT_ID}"

PUBLIC_IP="$(
  az vm show \
    --resource-group "${AZURE_RESOURCE_GROUP}" \
    --name "${AZURE_VM_NAME}" \
    -d \
    --query publicIps \
    -o tsv
)"

echo "VM created: ${AZURE_VM_NAME}"
echo "SSH user: ${AZURE_ADMIN_USER}"
echo "Public IP: ${PUBLIC_IP}"
