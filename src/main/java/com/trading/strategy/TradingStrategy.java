// TradingStrategy.java
package com.trading.strategy;

import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;

import java.util.Map;

/**
 * Interface for all trading strategies
 * Each strategy implementation represents one "case"
 */
public interface TradingStrategy {
    
    /**
     * Get the priority order (1 = highest priority, 2 = second, etc.)
     */
    int getPriority();
    
    /**
     * Get strategy name for logging
     */
    String getStrategyName();
    
    /**
     * Find suitable instruments for this strategy
     * @return Map with "CE" and/or "PE" instrument symbols
     */
    Map<String, String> findInstruments(TradingStrategyEngine context) throws Exception, KiteException;
    
    /**
     * Analyze instrument for buy signal using this strategy
     * @return Map containing signal details (signal=true/false, pattern, entryPrice, stopLoss, target)
     */
    Map<String, Object> analyzeInstrument(String instrument, TradingStrategyEngine context);
    
    /**
     * Execute buy signal for this strategy
     */
    void executeBuySignal(String instrument, Map<String, Object> signalDetails, TradingStrategyEngine context);
    
    /**
     * Check if instrument should be skipped (already in position, being monitored, etc.)
     */
    boolean shouldSkipInstrument(String instrument, TradingStrategyEngine context);
}