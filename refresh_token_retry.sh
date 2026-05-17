#!/bin/bash
cd /home/ec2-user/vwap-algo-trading

# Configuration
MAX_RETRIES=12              # Retry up to 12 times (5 min * 12 = 1 hour)
RETRY_INTERVAL=300          # 5 minutes in seconds
LOG_FILE="/home/ec2-user/vwap-algo-trading/logs/token_refresh.log"
JAR_PATH="/home/ec2-user/vwap-algo-trading/target/vwap-algo-trading-1.0-SNAPSHOT.jar"
MAIN_CLASS="com.trading.util.AutoZerodhaLoginHelper"

# Ensure log directory exists
mkdir -p "$(dirname "$LOG_FILE")"

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" | tee -a "$LOG_FILE"
}

cd /home/ec2-user/vwap-algo-trading || exit 1

# Load environment variables (ensure they are set)
export ZERODHA_USER_ID="7019211971"
export ZERODHA_PASSWORD="Welcome@123"
export ZERODHA_PIN="625886"
export ZERODHA_TOTP_SECRET="CQIATWTCH6ZDPDX4ZEV53X343K7GGQON"

log "=== Starting token refresh (max retries: $MAX_RETRIES) ==="

attempt=1
while [ $attempt -le $MAX_RETRIES ]; do
    log "Attempt $attempt / $MAX_RETRIES"

    java -cp "$JAR_PATH" "$MAIN_CLASS" >> "$LOG_FILE" 2>&1

    if [ $? -eq 0 ]; then
        log "✅ Token refresh succeeded on attempt $attempt"
        exit 0
    fi

    log "❌ Token refresh failed (attempt $attempt)"

    if [ $attempt -lt $MAX_RETRIES ]; then
        log "Waiting $RETRY_INTERVAL seconds before next retry..."
        sleep $RETRY_INTERVAL
    fi

    ((attempt++))
done

log "⛔ Token refresh failed after $MAX_RETRIES attempts. Exiting."
exit 1