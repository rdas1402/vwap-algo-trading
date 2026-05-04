package com.trading.test;

import java.util.Date;

public class BacktestPosition {
    private String instrument;
    private double entryPrice;
    private double stopLoss;
    private double target;
    private String patternType;
    private Date entryTime;

    public BacktestPosition(String instrument, double entryPrice, double stopLoss, 
                            double target, String patternType, Date entryTime) {
        this.instrument = instrument;
        this.entryPrice = entryPrice;
        this.stopLoss = stopLoss;
        this.target = target;
        this.patternType = patternType;
        this.entryTime = entryTime;
    }

    public String getInstrument() { return instrument; }
    public double getEntryPrice() { return entryPrice; }
    public double getStopLoss() { return stopLoss; }
    public double getTarget() { return target; }
    public String getPatternType() { return patternType; }
    public Date getEntryTime() { return entryTime; }
}