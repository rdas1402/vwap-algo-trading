package com.trading.test;
import java.util.Date;

class BacktestExecutedTrade {
    private String instrument;
    private String patternType;
    private double entryPrice;
    private double exitPrice;
    private double pnl;
    private Date entryTime;
    private Date exitTime;
    private String exitReason;

    public BacktestExecutedTrade(String instrument, String patternType, double entryPrice,
                                 double exitPrice, double pnl, Date entryTime,
                                 Date exitTime, String exitReason) {
        this.instrument = instrument;
        this.patternType = patternType;
        this.entryPrice = entryPrice;
        this.exitPrice = exitPrice;
        this.pnl = pnl;
        this.entryTime = entryTime;
        this.exitTime = exitTime;
        this.exitReason = exitReason;
    }

    public double getPnl() { return pnl; }
}