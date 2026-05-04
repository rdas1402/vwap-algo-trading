package com.trading.test;

import java.util.Date;

public class BacktestCandleData {
    private String instrument;
    private Date timestamp;
    private double open;
    private double high;
    private double low;
    private double close;
    private double vwap;
    private double range;
    private double closePercent;

    public BacktestCandleData(String instrument, Date timestamp, double open, double high, 
                              double low, double close, double vwap, double range, double closePercent) {
        this.instrument = instrument;
        this.timestamp = timestamp;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.vwap = vwap;
        this.range = range;
        this.closePercent = closePercent;
    }

    // Getters
    public String getInstrument() { return instrument; }
    public Date getTimestamp() { return timestamp; }
    public double getOpen() { return open; }
    public double getHigh() { return high; }
    public double getLow() { return low; }
    public double getClose() { return close; }
    public double getVWAP() { return vwap; }
    public double getRange() { return range; }
    public double getClosePercent() { return closePercent; }

    // For CandleData compatibility
    public long getVolume() { return 0; } // Volume not in CSV
    public String getSymbol() { return instrument; }
}