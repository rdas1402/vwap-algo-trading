package com.trading.strategy;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Centralized candle history manager that all strategies can access.
 * History is updated whenever candles are finalized, for ALL instruments.
 * FIX #10: Thread-safe with CopyOnWriteArrayList and defensive copies.
 */
public class CandleHistoryManager {

    private static final int MAX_HISTORY_SIZE = 50;
    private final Map<String, List<CandleData>> history = new ConcurrentHashMap<>();
    private static CandleHistoryManager instance;

    private CandleHistoryManager() {
        // Private constructor for singleton
    }

    public static synchronized CandleHistoryManager getInstance() {
        if (instance == null) {
            instance = new CandleHistoryManager();
        }
        return instance;
    }

    /**
     * Add a finalized candle to history for an instrument
     * Thread-safe using CopyOnWriteArrayList
     */
    public void addCandle(String instrument, CandleData candle) {
        if (instrument == null || candle == null) return;

        List<CandleData> list = history.computeIfAbsent(instrument, k -> new CopyOnWriteArrayList<>());
        list.add(candle);
        // Keep only last MAX_HISTORY_SIZE candles
        while (list.size() > MAX_HISTORY_SIZE) {
            list.remove(0);
        }
    }

    /**
     * Add multiple candles at once (for pre-loading)
     */
    public void addCandles(String instrument, List<CandleData> candles) {
        if (instrument == null || candles == null || candles.isEmpty()) return;

        List<CandleData> list = history.computeIfAbsent(instrument, k -> new CopyOnWriteArrayList<>());
        list.addAll(candles);
        // Keep only last MAX_HISTORY_SIZE candles
        while (list.size() > MAX_HISTORY_SIZE) {
            list.remove(0);
        }
    }

    /**
     * Get complete history for an instrument (defensive copy)
     */
    public List<CandleData> getHistory(String instrument) {
        List<CandleData> list = history.get(instrument);
        if (list == null) return new ArrayList<>();
        // Return a copy to avoid concurrent modification during iteration
        return new ArrayList<>(list);
    }

    /**
     * Get candle at specific index from end (1 = most recent, 2 = second most recent, etc.)
     */
    public CandleData getCandleAtIndex(String instrument, int indexFromEnd) {
        List<CandleData> list = history.get(instrument);
        if (list != null && list.size() >= indexFromEnd) {
            return list.get(list.size() - indexFromEnd);
        }
        return null;
    }

    /**
     * Get number of candles in history for an instrument
     */
    public int getHistorySize(String instrument) {
        List<CandleData> list = history.get(instrument);
        return list != null ? list.size() : 0;
    }

    /**
     * Check if we have sufficient history (at least required candles)
     */
    public boolean hasSufficientHistory(String instrument, int requiredCandles) {
        return getHistorySize(instrument) >= requiredCandles;
    }

    /**
     * Clear history for an instrument (useful for testing or reset)
     */
    public void clearHistory(String instrument) {
        history.remove(instrument);
    }

    /**
     * Clear all history
     */
    public void clearAllHistory() {
        history.clear();
    }
}