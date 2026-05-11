#!/bin/bash
cd /home/ec2-user/vwap-algo-trading

LOG_FILE="/home/ec2-user/vwap-algo-trading/logs/trading_start.log"
mkdir -p "$(dirname "$LOG_FILE")"

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" | tee -a "$LOG_FILE"
}

log "Starting trading application"
java -cp target/vwap-algo-trading-1.0-SNAPSHOT.jar com.trading.TradingApplication >> "$LOG_FILE" 2>&1 &