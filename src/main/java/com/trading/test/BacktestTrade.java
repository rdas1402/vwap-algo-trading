package com.trading.test;

import java.util.Date;

public class BacktestTrade {
    private String instrument;
    private double entryPrice;
    private double exitPrice;
    private double pnl;
    private String patternType;
    private Date entryTime;
    private Date exitTime;
    private String exitReason;

    public BacktestTrade(String instrument, double entryPrice, double exitPrice, double pnl,
                         String patternType, Date entryTime, Date exitTime, String exitReason) {
        this.instrument = instrument;
        this.entryPrice = entryPrice;
        this.exitPrice = exitPrice;
        this.pnl = pnl;
        this.patternType = patternType;
        this.entryTime = entryTime;
        this.exitTime = exitTime;
        this.exitReason = exitReason;
    }

    // Getters
    public String getInstrument() { return instrument; }
    public double getEntryPrice() { return entryPrice; }
    public double getExitPrice() { return exitPrice; }
    public double getPnl() { return pnl; }
    public String getPatternType() { return patternType; }
    public Date getEntryTime() { return entryTime; }
    public Date getExitTime() { return exitTime; }
    public String getExitReason() { return exitReason; }
}