package com.trading.test;

import com.trading.strategy.*;
import java.util.*;

public class BacktestContext {
    private final Map<String, List<BacktestCandleData>> candleDataMap;
    private final Map<String, BacktestCandleData> currentCandles;
    private final Map<String, Integer> candleIndices;
    private int currentIndex = 0;
    private List<BacktestCandleData> currentTimeSlice = new ArrayList<>();
    
    // Track positions for backtesting
    private final Map<String, BacktestPosition> openPositions = new HashMap<>();
    private final List<BacktestTrade> closedTrades = new ArrayList<>();
    private double totalPnL = 0;
    
    // Daily P&L tracking
    private final Map<String, Double> dailyPnL = new HashMap<>();
    private String currentDate = null;

    public BacktestContext(Map<String, List<BacktestCandleData>> candleDataMap) {
        this.candleDataMap = candleDataMap;
        this.currentCandles = new HashMap<>();
        this.candleIndices = new HashMap<>();
        
        // Initialize indices
        for (String instrument : candleDataMap.keySet()) {
            candleIndices.put(instrument, 0);
        }
    }
    
    public void setCurrentTimeSlice(List<BacktestCandleData> candles, int index, String date) {
        this.currentTimeSlice = candles;
        this.currentIndex = index;
        this.currentDate = date;
        
        // Update current candles
        currentCandles.clear();
        for (BacktestCandleData candle : candles) {
            currentCandles.put(candle.getInstrument(), candle);
        }
    }
    
    public BacktestCandleData getCurrentCandle(String instrument) {
        return currentCandles.get(instrument);
    }
    
    public BacktestCandleData getLastCompletedCandle(String instrument) {
        List<BacktestCandleData> candles = candleDataMap.get(instrument);
        int index = candleIndices.getOrDefault(instrument, 0);
        if (index > 0 && candles != null && index <= candles.size()) {
            return candles.get(index - 1);
        }
        return null;
    }
    
    public BacktestCandleData getPreviousCompletedCandle(String instrument) {
        List<BacktestCandleData> candles = candleDataMap.get(instrument);
        int index = candleIndices.getOrDefault(instrument, 0);
        if (index >= 2 && candles != null) {
            return candles.get(index - 2);
        }
        return null;
    }
    
    public BacktestCandleData getCandleAtIndex(String instrument, int indexFromEnd) {
        List<BacktestCandleData> candles = candleDataMap.get(instrument);
        int currentIdx = candleIndices.getOrDefault(instrument, 0);
        int targetIdx = currentIdx - indexFromEnd;
        if (targetIdx >= 0 && candles != null && targetIdx < candles.size()) {
            return candles.get(targetIdx);
        }
        return null;
    }
    
    public int getCompletedCandleCount(String instrument) {
        return candleIndices.getOrDefault(instrument, 0);
    }
    
    public void incrementCandleIndex(String instrument) {
        candleIndices.put(instrument, candleIndices.getOrDefault(instrument, 0) + 1);
    }
    
    public double getCurrentPrice(String instrument) {
        BacktestCandleData candle = currentCandles.get(instrument);
        return candle != null ? candle.getClose() : 0;
    }
    
    public double getCurrentVolume(String instrument) {
        // Volume not in CSV, return 0
        return 0;
    }
    
    public boolean isWithinBuyingHours() {
        // In backtest, always true for strategy analysis
        return true;
    }
    
    public boolean canPlaceBuyOrders() {
        // Check time in backtest
        if (currentTimeSlice.isEmpty()) return false;
        BacktestCandleData firstCandle = currentTimeSlice.get(0);
        if (firstCandle == null) return true;
        
        Calendar cal = Calendar.getInstance();
        cal.setTime(firstCandle.getTimestamp());
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        int minute = cal.get(Calendar.MINUTE);
        int timeInMinutes = hour * 60 + minute;
        
        int buyStart = 9 * 60 + 45;
        int buyEnd = 15 * 60 + 15;
        
        return timeInMinutes >= buyStart && timeInMinutes <= buyEnd;
    }
    
    public void addPosition(String instrument, double entryPrice, double stopLoss, double target, String patternType, Date entryTime) {
        BacktestPosition position = new BacktestPosition(instrument, entryPrice, stopLoss, target, patternType, entryTime);
        openPositions.put(instrument, position);
        System.out.println("   📈 POSITION OPENED: " + instrument + " @ " + entryPrice + " | SL: " + stopLoss + " | Target: " + target);
    }
    
    public void updatePositions(Date currentTime) {
        List<String> toClose = new ArrayList<>();
        
        for (Map.Entry<String, BacktestPosition> entry : openPositions.entrySet()) {
            String instrument = entry.getKey();
            BacktestPosition position = entry.getValue();
            BacktestCandleData currentCandle = currentCandles.get(instrument);
            
            if (currentCandle != null) {
                double currentPrice = currentCandle.getClose();
                
                // Check stop loss
                if (currentPrice <= position.getStopLoss()) {
                    double pnl = (currentPrice - position.getEntryPrice()) * 75; // Lot size
                    totalPnL += pnl;
                    closedTrades.add(new BacktestTrade(instrument, position.getEntryPrice(), currentPrice, 
                                                        pnl, position.getPatternType(), position.getEntryTime(), 
                                                        currentTime, "STOP_LOSS"));
                    toClose.add(instrument);
                    System.out.println("   🔴 STOP LOSS HIT: " + instrument + " | P&L: " + String.format("%.2f", pnl));
                }
                // Check target
                else if (currentPrice >= position.getTarget()) {
                    double pnl = (currentPrice - position.getEntryPrice()) * 75;
                    totalPnL += pnl;
                    closedTrades.add(new BacktestTrade(instrument, position.getEntryPrice(), currentPrice, 
                                                        pnl, position.getPatternType(), position.getEntryTime(), 
                                                        currentTime, "TARGET"));
                    toClose.add(instrument);
                    System.out.println("   🎯 TARGET HIT: " + instrument + " | P&L: " + String.format("%.2f", pnl));
                }
            }
        }
        
        for (String instrument : toClose) {
            openPositions.remove(instrument);
        }
    }
    
    public boolean hasOpenPosition(String instrument) {
        return openPositions.containsKey(instrument);
    }
    
    public boolean hasAnyOpenPosition() {
        return !openPositions.isEmpty();
    }
    
    public double getTotalPnL() {
        return totalPnL;
    }
    
    public List<BacktestTrade> getClosedTrades() {
        return closedTrades;
    }
    
    public Map<String, BacktestPosition> getOpenPositions() {
        return openPositions;
    }
    
    public void addToDailyPnL(double pnl) {
        if (currentDate != null) {
            dailyPnL.put(currentDate, dailyPnL.getOrDefault(currentDate, 0.0) + pnl);
        }
    }
    
    public Map<String, Double> getDailyPnL() {
        return dailyPnL;
    }
    
    public void resetForNewDay(String date) {
        currentDate = date;
        // Don't reset totalPnL, just track daily separately
    }
}