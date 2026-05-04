package com.trading.test;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

class BacktestSignal {
    private String instrument;
    private String patternType;
    private double entryPrice;
    private double stopLoss;
    private double target;
    private Date timestamp;

    public BacktestSignal(String instrument, String patternType, double entryPrice,
                          double stopLoss, double target, Date timestamp) {
        this.instrument = instrument;
        this.patternType = patternType;
        this.entryPrice = entryPrice;
        this.stopLoss = stopLoss;
        this.target = target;
        this.timestamp = timestamp;
    }

    // Getters
    public String getInstrument() { return instrument; }
    public String getPatternType() { return patternType; }
    public double getEntryPrice() { return entryPrice; }
    public double getStopLoss() { return stopLoss; }
    public double getTarget() { return target; }
    public Date getTimestamp() { return timestamp; }
}