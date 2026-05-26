#!/usr/bin/env bash
set -euo pipefail

export DEBIAN_FRONTEND=noninteractive

sudo apt-get update
sudo apt-get install -y \
  openjdk-17-jre-headless \
  unzip \
  curl \
  jq \
  ca-certificates \
  gnupg \
  lsb-release \
  xvfb

if ! command -v az >/dev/null 2>&1; then
  curl -sL https://aka.ms/InstallAzureCLIDeb | sudo bash
fi

if ! command -v google-chrome >/dev/null 2>&1; then
  curl -fsSL https://dl.google.com/linux/linux_signing_key.pub | sudo gpg --dearmor -o /usr/share/keyrings/google-chrome.gpg
  echo "deb [arch=amd64 signed-by=/usr/share/keyrings/google-chrome.gpg] http://dl.google.com/linux/chrome/deb/ stable main" | sudo tee /etc/apt/sources.list.d/google-chrome.list >/dev/null
  sudo apt-get update
  sudo apt-get install -y google-chrome-stable
fi

if ! command -v chromedriver >/dev/null 2>&1; then
  CHROME_MAJOR="$(google-chrome --version | awk '{print $3}' | cut -d. -f1)"
  DRIVER_VERSION="$(curl -fsSL "https://googlechromelabs.github.io/chrome-for-testing/LATEST_RELEASE_${CHROME_MAJOR}")"
  TMP_DIR="$(mktemp -d)"
  curl -fsSL "https://storage.googleapis.com/chrome-for-testing-public/${DRIVER_VERSION}/linux64/chromedriver-linux64.zip" -o "${TMP_DIR}/chromedriver.zip"
  unzip -q "${TMP_DIR}/chromedriver.zip" -d "${TMP_DIR}"
  sudo install -m 0755 "${TMP_DIR}/chromedriver-linux64/chromedriver" /usr/bin/chromedriver
  rm -rf "${TMP_DIR}"
fi

sudo timedatectl set-timezone Asia/Kolkata

sudo mkdir -p /opt/vwap-algo-trading/current
sudo mkdir -p /opt/vwap-algo-trading/config
sudo mkdir -p /opt/vwap-algo-trading/logs
sudo mkdir -p /opt/vwap-algo-trading/screenshots
sudo mkdir -p /opt/vwap-algo-trading/bin

echo "VM prerequisites installed successfully."
