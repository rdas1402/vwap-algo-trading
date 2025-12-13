// CandleData.java
package com.trading.strategy;

import java.util.Date;

public class CandleData {
    private String symbol;
    private double open;
    private double high;
    private double low;
    private double close;
    private long volume; // Changed from double to long
    private Date timestamp;
    private double vwap;

    public CandleData(String symbol, double open, double high, double low, double close, long volume, Date timestamp, double vwap) {
        this.symbol = symbol;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
        this.timestamp = timestamp;
        this.vwap = vwap;
    }

    public CandleData(String symbol, double open, double high, double low, double close, long volume, Date timestamp) {
        this.symbol = symbol;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
        this.timestamp = timestamp;
    }

    // Getters and setters
    public String getSymbol() { return symbol; }
    public double getOpen() { return open; }
    public double getHigh() { return high; }
    public double getLow() { return low; }
    public double getClose() { return close; }
    public long getVolume() { return volume; } // Updated return type
    public Date getTimestamp() { return timestamp; }
    public double getVWAP() { return vwap; }
    public void setVWAP(double vwap) { this.vwap = vwap; }
}