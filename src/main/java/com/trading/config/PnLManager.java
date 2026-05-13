package com.trading.config;

import java.util.*;

public class PnLManager {
    private static PnLManager instance;
    private double totalDailyPnL = 0.0;
    private boolean dailyLossLimitReached = false;
    private List<Map<String, Object>> tradeLog = new ArrayList<>();
    
    private PnLManager() {}
    
    public static synchronized PnLManager getInstance() {
        if (instance == null) {
            instance = new PnLManager();
        }
        return instance;
    }
    
    public double getTotalDailyPnL() {
        return totalDailyPnL;
    }
    
    public void setTotalDailyPnL(double pnl) {
        this.totalDailyPnL = pnl;
        checkDailyLossLimit();
    }
    
    public void addToDailyPnL(double amount) {
        this.totalDailyPnL += amount;
        checkDailyLossLimit();
    }
    
    public boolean isDailyLossLimitReached() {
        return dailyLossLimitReached;
    }
    
    private void checkDailyLossLimit() {
        double maxDailyLoss = AppConfig.getMaxDailyLoss();
        if (totalDailyPnL <= -maxDailyLoss) {
            dailyLossLimitReached = true;
            System.out.println("🚫 DAILY LOSS LIMIT REACHED!");
            System.out.println("   Total P&L: ₹" + totalDailyPnL);
            System.out.println("   Max Daily Loss: ₹" + maxDailyLoss);
        }
    }
    
    public void resetDailyPnL() {
        totalDailyPnL = 0.0;
        dailyLossLimitReached = false;
        System.out.println("🔄 Daily P&L Reset to ₹0.00");
    }

    public void addTradeLog(String instrument, double entry, double exit, double pnl,
                            String reason, String pattern, boolean simulated) {
        Map<String, Object> trade = new HashMap<>();
        trade.put("timestamp", new Date());
        trade.put("instrument", instrument);
        trade.put("entry", entry);
        trade.put("exit", exit);
        trade.put("pnl", pnl);
        trade.put("reason", reason);
        trade.put("pattern", pattern);
        trade.put("simulated", simulated);
        tradeLog.add(trade);
    }

    public void printEndOfDaySummary() {
        System.out.println("\n📊 END OF DAY TRADE SUMMARY");
        System.out.println("Total Trades: " + tradeLog.size());
        double totalPnl = tradeLog.stream().mapToDouble(t -> (double)t.get("pnl")).sum();
        System.out.println("Total P&L: ₹" + String.format("%.2f", totalPnl));
        System.out.println("Trade Details:");
        for (Map<String, Object> t : tradeLog) {
            System.out.printf("  %s %s %s | Entry %.2f Exit %.2f | P&L %.2f | %s%n",
                    t.get("timestamp"), t.get("instrument"), t.get("pattern"),
                    t.get("entry"), t.get("exit"), t.get("pnl"),
                    t.get("simulated") ? "SIM" : "REAL");
        }
    }
}