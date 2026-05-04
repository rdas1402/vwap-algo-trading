package com.trading.test;

import com.trading.strategy.CandleData;
import java.util.*;

public class MockRealTimeCandleBuilder {
    private final Map<String, List<BacktestCandleData>> candleHistory = new HashMap<>();
    private final Map<String, Integer> currentIndices = new HashMap<>();
    
    public MockRealTimeCandleBuilder(Map<String, List<BacktestCandleData>> candleDataMap) {
        for (Map.Entry<String, List<BacktestCandleData>> entry : candleDataMap.entrySet()) {
            candleHistory.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            currentIndices.put(entry.getKey(), 0);
        }
    }
    
    public void advanceToIndex(String instrument, int index) {
        currentIndices.put(instrument, index);
    }
    
    public CandleData getLastCompletedCandle(String instrument) {
        int idx = currentIndices.getOrDefault(instrument, 0);
        List<BacktestCandleData> history = candleHistory.get(instrument);
        if (idx > 0 && history != null && idx <= history.size()) {
            BacktestCandleData backtestCandle = history.get(idx - 1);
            return convertToCandleData(backtestCandle);
        }
        return null;
    }
    
    public CandleData getPreviousCompletedCandle(String instrument) {
        int idx = currentIndices.getOrDefault(instrument, 0);
        List<BacktestCandleData> history = candleHistory.get(instrument);
        if (idx >= 2 && history != null) {
            BacktestCandleData backtestCandle = history.get(idx - 2);
            return convertToCandleData(backtestCandle);
        }
        return null;
    }
    
    public CandleData getCurrentCandle(String instrument) {
        int idx = currentIndices.getOrDefault(instrument, 0);
        List<BacktestCandleData> history = candleHistory.get(instrument);
        if (idx < history.size()) {
            BacktestCandleData backtestCandle = history.get(idx);
            return convertToCandleData(backtestCandle);
        }
        return null;
    }
    
    public int getCompletedCandleCount(String instrument) {
        return currentIndices.getOrDefault(instrument, 0);
    }
    
    private CandleData convertToCandleData(BacktestCandleData backtestCandle) {
        return new CandleData(
            backtestCandle.getInstrument(),
            backtestCandle.getOpen(),
            backtestCandle.getHigh(),
            backtestCandle.getLow(),
            backtestCandle.getClose(),
            0, // volume
            backtestCandle.getTimestamp(),
            backtestCandle.getVWAP()
        );
    }
    
    public boolean isRunning() { return true; }
    public void addInstrument(String instrument) {}
}