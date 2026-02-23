// PositionManager.java
package com.trading.strategy;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PositionManager {
    private static final Map<String, Position> positionCache = new ConcurrentHashMap<>();
    
    public static void cachePosition(Position position) {
        positionCache.put(position.getTradingSymbol(), position);
        System.out.println("✅ Position cached: " + position.getTradingSymbol() + 
                         " | Entry: " + position.getEntryPrice() + 
                         " | SL: " + position.getStopLoss() + 
                         " | Target: " + position.getTarget() +
                         " | VWAP: " + position.getVwap());
    }
    
    public static void removeCachedPosition(String tradingSymbol) {
        positionCache.remove(tradingSymbol);
        System.out.println("🗑️ Position removed from cache: " + tradingSymbol);
    }
    
    public static Map<String, Position> getAllCachedPositions() {
        return new HashMap<>(positionCache);
    }
    
    public static boolean hasCachedPosition(String tradingSymbol) {
        return positionCache.containsKey(tradingSymbol);
    }
    
    public static void clearAllPositions() {
        positionCache.clear();
        System.out.println("🗑️ All positions cleared from cache");
    }
}