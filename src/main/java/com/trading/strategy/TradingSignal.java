// TradingSignal.java
package com.trading.strategy;

import java.util.Date;

public class TradingSignal {
    private String tradingSymbol;
    private SignalType signalType;
    private double price;
    private Date timestamp;
    
    public TradingSignal(String tradingSymbol, SignalType signalType, double price) {
        this.tradingSymbol = tradingSymbol;
        this.signalType = signalType;
        this.price = price;
        this.timestamp = new Date();
    }
    
    // Getters and setters
    public String getTradingSymbol() { return tradingSymbol; }
    public SignalType getSignalType() { return signalType; }
    public double getPrice() { return price; }
    public Date getTimestamp() { return timestamp; }
}