package com.trading.config;

public class PnLManager {
    private static PnLManager instance;
    private double totalDailyPnL = 0.0;
    private boolean dailyLossLimitReached = false;
    
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
}