# Azure Deployment Runbook

This app is best deployed to an Azure Linux VM because it needs:

- Java 17
- Chrome + ChromeDriver
- a daily Selenium login flow to refresh the Zerodha token
- a trading process that stays alive during market hours

This Azure setup uses:

- Ubuntu VM
- `systemd` services and timers instead of cron
- Azure Key Vault for secrets
- one external config file at `~/vwap-algo-trading/config/application.properties`

## Target layout

```text
/opt/vwap-algo-trading/
  current/
    vwap-algo-trading-1.0-SNAPSHOT.jar
  config/
    application.properties
    vwap.env
  logs/
  screenshots/
  bin/
    refresh_token_retry.sh
    start_trading.sh
    fetch_keyvault_secrets.sh
```

## Recommended Azure resources

- 1 Ubuntu 22.04 LTS VM
- 1 Key Vault
- 1 Log Analytics workspace or Azure Monitor setup
- Optional static public IP if you need stable egress behavior

## VM prerequisites

Run:

```bash
sudo bash /opt/vwap-algo-trading/current/deployment/azure/bin/install_vm.sh
```

Or copy the script from this repo and run it from the VM.

## Identity and secrets

1. Create a Key Vault.
2. Enable a system-assigned managed identity on the VM.
3. Grant the VM identity `Key Vault Secrets User` on the vault.
4. Store these secrets in Key Vault:

- `zerodha-user-id`
- `zerodha-password`
- `zerodha-pin`
- `zerodha-totp-secret`

5. Store non-secret runtime values in `vwap.env`.

## App config

The app already prefers an external config file at:

```text
~/vwap-algo-trading/config/application.properties
```

For Azure, place it at:

```text
/opt/vwap-algo-trading/config/application.properties
```

and symlink:

```bash
mkdir -p ~/vwap-algo-trading/config
ln -sf /opt/vwap-algo-trading/config/application.properties ~/vwap-algo-trading/config/application.properties
```

## Install app files

Copy these files to the VM:

- the app jar to `/opt/vwap-algo-trading/current/`
- `deployment/azure/bin/*.sh` to `/opt/vwap-algo-trading/bin/`
- `deployment/azure/systemd/*` to `/etc/systemd/system/`
- `deployment/azure/env/vwap.env.example` to `/opt/vwap-algo-trading/config/vwap.env`

Make scripts executable:

```bash
sudo chmod +x /opt/vwap-algo-trading/bin/*.sh
```

## Configure environment

Edit:

```bash
sudo nano /opt/vwap-algo-trading/config/vwap.env
```

Set:

- `APP_HOME`
- `APP_USER`
- `APP_GROUP`
- `JAVA_BIN`
- `CHROMEDRIVER_PATH`
- `CHROME_BINARY`
- `KEY_VAULT_NAME`
- `AZURE_RESOURCE_GROUP`
- `AZURE_VM_NAME`
- `ZERODHA_API_KEY`
- `ZERODHA_API_SECRET`

## Install systemd units

Render and install the units with the correct VM user:

```bash
sudo /opt/vwap-algo-trading/bin/install_systemd_units.sh
```

## Configure external application.properties

Create:

```bash
sudo nano /opt/vwap-algo-trading/config/application.properties
```

This file should contain the non-secret app settings and the API key/secret if you are not injecting them another way. The access token is refreshed into this file by the login helper.

## Enable services

Reload and enable:

```bash
sudo systemctl daemon-reload
sudo systemctl enable vwap-refresh-token.timer
sudo systemctl enable vwap-start-trading.timer
sudo systemctl start vwap-refresh-token.timer
sudo systemctl start vwap-start-trading.timer
```

## Verify

```bash
systemctl list-timers --all | grep vwap
sudo systemctl status vwap-refresh-token.timer
sudo systemctl status vwap-start-trading.timer
journalctl -u vwap-refresh-token.service -n 100 --no-pager
journalctl -u vwap-start-trading.service -n 100 --no-pager
```

## Manual dry runs

Refresh token:

```bash
sudo systemctl start vwap-refresh-token.service
```

Start trading:

```bash
sudo systemctl start vwap-start-trading.service
```

## Important notes

- The scripts use `systemd` timers, not cron.
- Timers use the VM's local time. Set the VM timezone to `Asia/Kolkata`.
- The refresh script pulls secrets from Key Vault at runtime before launching the Java login helper.
- The start script prevents duplicate `TradingApplication` processes.
- Do not keep Zerodha credentials inside the shell scripts.
