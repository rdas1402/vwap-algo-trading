// CandleManager.java
package com.trading.strategy;

import java.util.*;

public class CandleManager {
    private final Map<String, List<CandleData>> candleDataMap;
    private final int candlePeriodSeconds;
    private final Map<String, Long> lastCandleUpdateTime;
    private final Map<String, Boolean> crossoverTriggered; // Track if crossover already triggered

    public CandleManager(int candlePeriodSeconds) {
        this.candleDataMap = new HashMap<>();
        this.candlePeriodSeconds = candlePeriodSeconds;
        this.lastCandleUpdateTime = new HashMap<>();
        this.crossoverTriggered = new HashMap<>();
    }

    public void addTick(String symbol, double price, long volume, Date timestamp) {
        List<CandleData> candles = candleDataMap.computeIfAbsent(symbol, k -> new ArrayList<>());

        long currentTime = timestamp.getTime();
        Long lastUpdate = lastCandleUpdateTime.get(symbol);

        // Reset crossover tracking when new candle starts
        if (lastUpdate == null || (currentTime - lastUpdate) >= (candlePeriodSeconds * 1000)) {
            crossoverTriggered.put(symbol, false); // Reset for new candle
        }

        if (lastUpdate == null || (currentTime - lastUpdate) >= (candlePeriodSeconds * 1000)) {
            // Create new candle
            CandleData newCandle = new CandleData(symbol, price, price, price, price, volume, timestamp);
            candles.add(newCandle);
            lastCandleUpdateTime.put(symbol, currentTime);

            // Keep only last 10 candles to prevent memory issues
            if (candles.size() > 10) {
                candles.remove(0);
            }
        } else {
            // Update current candle
            if (!candles.isEmpty()) {
                CandleData currentCandle = candles.get(candles.size() - 1);
                CandleData updatedCandle = updateCandle(currentCandle, price, volume, timestamp);
                candles.set(candles.size() - 1, updatedCandle);
            }
        }
    }

    private CandleData updateCandle(CandleData candle, double price, long volume, Date timestamp) {
        double newHigh = Math.max(candle.getHigh(), price);
        double newLow = Math.min(candle.getLow(), price);
        long newVolume = candle.getVolume() + volume;

        return new CandleData(
                candle.getSymbol(),
                candle.getOpen(),
                newHigh,
                newLow,
                price, // Close is latest price
                newVolume,
                timestamp
        );
    }

    public CandleData getLastCandle(String symbol) {
        List<CandleData> candles = candleDataMap.get(symbol);
        return (candles != null && !candles.isEmpty()) ? candles.get(candles.size() - 1) : null;
    }

    public CandleData getPreviousCandle(String symbol) {
        List<CandleData> candles = candleDataMap.get(symbol);
        return (candles != null && candles.size() >= 2) ? candles.get(candles.size() - 2) : null;
    }

    public boolean hasCrossover(String symbol, double currentVWAP) {
        // Check if crossover already triggered for this candle
        if (crossoverTriggered.getOrDefault(symbol, false)) {
            return false;
        }

        CandleData previousCandle = getPreviousCandle(symbol);
        CandleData currentCandle = getLastCandle(symbol);

        if (previousCandle == null || currentCandle == null) {
            return false;
        }

        // Ensure we have at least 2 complete candles
        long currentTime = System.currentTimeMillis();
        Long lastUpdate = lastCandleUpdateTime.get(symbol);
        if (lastUpdate == null || (currentTime - lastUpdate) < (candlePeriodSeconds * 1000 * 0.8)) {
            return false; // Current candle is not complete enough
        }

        // Check if previous candle was below VWAP and current candle closed above VWAP
        boolean previousBelow = previousCandle.getClose() < currentVWAP;
        boolean currentAbove = currentCandle.getClose() > currentVWAP;

        boolean crossover = previousBelow && currentAbove;

        if (crossover) {
            crossoverTriggered.put(symbol, true); // Mark as triggered
        }

        return crossover;
    }

    public void clearOldData() {
        // Clear data for symbols that are no longer being tracked
        candleDataMap.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        crossoverTriggered.clear();
    }

    public void resetCrossover(String symbol) {
        crossoverTriggered.put(symbol, false);
    }
}