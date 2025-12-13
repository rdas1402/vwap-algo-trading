// Position.java - Update the existing class
package com.trading.strategy;

import java.util.Date;

public class Position {
    private String tradingSymbol;
    private String orderId;
    private double entryPrice;
    private int quantity;
    private SignalType signalType;
    private double stopLoss;
    private double target;
    private double vwap; // Add this field
    private Date entryTime; // Add this field
    private String patternType;

    // Getters and setters
    public String getTradingSymbol() { return tradingSymbol; }
    public void setTradingSymbol(String tradingSymbol) { this.tradingSymbol = tradingSymbol; }

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }

    public double getEntryPrice() { return entryPrice; }
    public void setEntryPrice(double entryPrice) { this.entryPrice = entryPrice; }

    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }

    public SignalType getSignalType() { return signalType; }
    public void setSignalType(SignalType signalType) { this.signalType = signalType; }

    public double getStopLoss() { return stopLoss; }
    public void setStopLoss(double stopLoss) { this.stopLoss = stopLoss; }

    public double getTarget() { return target; }
    public void setTarget(double target) { this.target = target; }

    // Add getters and setters for new fields
    public double getVwap() { return vwap; }
    public void setVwap(double vwap) { this.vwap = vwap; }

    public Date getEntryTime() { return entryTime; }
    public void setEntryTime(Date entryTime) { this.entryTime = entryTime; }

    public String getPatternType() {
        return patternType;
    }

    public void setPatternType(String patternType) {
        this.patternType = patternType;
    }
}