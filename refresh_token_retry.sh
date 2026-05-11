#!/bin/bash
cd /home/ec2-user/vwap-algo-trading

LOG_FILE="/home/ec2-user/vwap-algo-trading/logs/token_refresh.log"
mkdir -p "$(dirname "$LOG_FILE")"

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" | tee -a "$LOG_FILE"
}

log "Starting token refresh (will retry every 5 min until success)"

MAX_RETRIES=75   # 75 * 5 min = 6.25 hours (until ~9:00 AM)
RETRY_COUNT=0

while [ $RETRY_COUNT -lt $MAX_RETRIES ]; do
    java -cp target/vwap-algo-trading-1.0-SNAPSHOT.jar com.trading.util.AutoZerodhaLoginHelper >> "$LOG_FILE" 2>&1
    if [ $? -eq 0 ]; then
        log "Token refresh successful"
        exit 0
    fi
    RETRY_COUNT=$((RETRY_COUNT + 1))
    log "Token refresh failed (attempt $RETRY_COUNT). Retrying in 5 minutes..."
    sleep 300
done

log "Token refresh failed after $MAX_RETRIES attempts"
exit 1