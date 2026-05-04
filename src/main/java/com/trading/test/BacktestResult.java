package com.trading.test;

import java.util.*;

public class BacktestResult {
    private String date;
    private List<BacktestSignal> signals = new ArrayList<>();
    private List<BacktestExecutedTrade> trades = new ArrayList<>();
    private double totalPnL = 0;

    public BacktestResult(String date) {
        this.date = date;
    }

    public void addSignal(String instrument, String patternType, double entryPrice,
                          double stopLoss, double target, Date timestamp) {
        signals.add(new BacktestSignal(instrument, patternType, entryPrice, stopLoss, target, timestamp));
        System.out.println("      📊 SIGNAL: " + patternType + " on " + instrument +
                " @ " + entryPrice + " (SL: " + stopLoss + ", Target: " + target + ")");
    }

    public void addTrade(BacktestExecutedTrade trade) {
        trades.add(trade);
        totalPnL += trade.getPnl();
    }

    public String getDate() { return date; }
    public List<BacktestSignal> getSignals() { return signals; }
    public List<BacktestExecutedTrade> getTrades() { return trades; }
    public double getTotalPnL() { return totalPnL; }
}