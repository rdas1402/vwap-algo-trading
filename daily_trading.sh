#!/bin/bash
export PATH=/usr/local/bin:$PATH

cd /home/ec2-user/vwap-algo-trading   # change to your actual path

LOG_FILE="/var/log/trading/daily_trading.log"
mkdir -p /var/log/trading

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" | tee -a "$LOG_FILE"
}

# Load secrets from environment (or from AWS Secrets Manager)
# Make sure these are set in the crontab environment.
# For security, you can also fetch from AWS Secrets Manager here.

log "Starting daily trading scheduler"

ATTEMPT=1
MAX_ATTEMPTS=2
LOGIN_OK=0

while [ $ATTEMPT -le $MAX_ATTEMPTS ]; do
    log "Login attempt $ATTEMPT"
    java -cp target/vwap-algo-trading-1.0.jar com.trading.util.AutomatedZerodhaLogin
    if [ $? -eq 0 ]; then
        LOGIN_OK=1
        log "Login successful"
        break
    else
        log "Login failed, attempt $ATTEMPT"
        if [ $ATTEMPT -eq 1 ]; then
            log "Retrying at 8:50 AM"
            sleep 300   # 5 minutes
        fi
    fi
    ((ATTEMPT++))
done

if [ $LOGIN_OK -eq 0 ]; then
    log "CRITICAL: Login failed after $MAX_ATTEMPTS attempts. Exiting."
    exit 1
fi

# Wait until 9:15 AM
current_epoch=$(date +%s)
target_epoch=$(date -d "09:15" +%s)
if [ $current_epoch -lt $target_epoch ]; then
    sleep_duration=$((target_epoch - current_epoch))
    log "Waiting $sleep_duration seconds until 9:15 AM"
    sleep $sleep_duration
fi

# Verify Nifty spot before launching
for i in {1..3}; do
    log "Checking Nifty spot (attempt $i)"
    java -cp target/vwap-algo-trading-1.0.jar com.trading.util.SpotChecker
    if [ $? -eq 0 ]; then
        log "Nifty spot available. Starting trading application."
        java -cp target/vwap-algo-trading-1.0.jar com.trading.TradingApplication >> "$LOG_FILE" 2>&1 &
        exit 0
    fi
    sleep 10
done

log "ERROR: Could not fetch Nifty spot after login. Trading engine not started."
exit 1