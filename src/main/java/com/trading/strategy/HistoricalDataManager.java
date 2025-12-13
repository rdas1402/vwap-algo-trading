package com.trading.strategy;
import com.zerodhatech.kiteconnect.KiteConnect;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class HistoricalDataManager {
    private KiteConnect kiteConnect;
    private SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss");

    // Store historical data for ALL instruments (not just current cycle)
    private final Map<String, List<CandleData>> historicalDataMap;
    private final int maxDataPoints = 15; // Store last 15 candles (75 minutes)

    // Track when each instrument was last updated
    private final Map<String, Date> lastUpdateTimeMap;

    public HistoricalDataManager(KiteConnect kiteConnect) {
        this.kiteConnect = kiteConnect;
        this.historicalDataMap = new ConcurrentHashMap<>();
        this.lastUpdateTimeMap = new ConcurrentHashMap<>();
    }

    /**
     * Update historical data for an instrument
     */
    public void updateHistoricalData(String instrument, double currentPrice, double currentVWAP) {
        try {
            List<CandleData> candleDataList = historicalDataMap.computeIfAbsent(instrument, k -> new ArrayList<>());
            Date currentTime = new Date();

            // Get current 5-minute block
            Calendar cal = Calendar.getInstance();
            cal.setTime(currentTime);
            int currentMinute = cal.get(Calendar.MINUTE);
            int current5MinBlock = currentMinute / 5; // 0-11
            int currentHour = cal.get(Calendar.HOUR_OF_DAY);

            boolean shouldCreateNewCandle = false;

            if (candleDataList.isEmpty()) {
                // First candle
                shouldCreateNewCandle = true;
            } else {
                // Check last candle's time
                CandleData lastCandle = candleDataList.get(candleDataList.size() - 1);
                Calendar lastCandleCal = Calendar.getInstance();
                lastCandleCal.setTime(lastCandle.getTimestamp());

                int lastCandleMinute = lastCandleCal.get(Calendar.MINUTE);
                int lastCandle5MinBlock = lastCandleMinute / 5;
                int lastCandleHour = lastCandleCal.get(Calendar.HOUR_OF_DAY);

                // Create new candle if:
                // 1. Different hour, OR
                // 2. Different 5-minute block
                shouldCreateNewCandle = (currentHour != lastCandleHour) ||
                        (current5MinBlock != lastCandle5MinBlock);
            }

            if (shouldCreateNewCandle) {
                // Create new candle
                CandleData newCandle = new CandleData(
                        instrument, currentPrice, currentPrice, currentPrice, currentPrice,
                        0, currentTime, currentVWAP
                );
                candleDataList.add(newCandle);

                // Keep only last 15 candles
                if (candleDataList.size() > maxDataPoints) {
                    candleDataList.remove(0);
                }

                System.out.println("🕯️ NEW Candle at " + currentHour + ":" + currentMinute +
                        " (block " + current5MinBlock + ")");
            } else {
                // Update last candle
                CandleData lastCandle = candleDataList.get(candleDataList.size() - 1);
                double newHigh = Math.max(lastCandle.getHigh(), currentPrice);
                double newLow = Math.min(lastCandle.getLow(), currentPrice);

                CandleData updatedCandle = new CandleData(
                        instrument,
                        lastCandle.getOpen(),
                        newHigh,
                        newLow,
                        currentPrice,
                        lastCandle.getVolume(),
                        lastCandle.getTimestamp(), // Keep original time
                        currentVWAP
                );
                candleDataList.set(candleDataList.size() - 1, updatedCandle);

                System.out.println("📈 UPDATED Candle at " + currentHour + ":" + currentMinute);
            }

        } catch (Exception e) {
            System.err.println("❌ Error: " + e.getMessage());
        }
    }

    /**
     * Get previous candle data for an instrument
     */
    public CandleData getPreviousCandle(String instrument) {
        try {
            List<CandleData> candleDataList = historicalDataMap.get(instrument);
            if (candleDataList != null && candleDataList.size() >= 2) {
                return candleDataList.get(candleDataList.size() - 2); // Second last candle
            }
        } catch (Exception e) {
            System.err.println("❌ Error getting previous candle for " + instrument + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Get current candle data for an instrument
     */
    public CandleData getCurrentCandle(String instrument) {
        try {
            List<CandleData> candleDataList = historicalDataMap.get(instrument);
            if (candleDataList != null && !candleDataList.isEmpty()) {
                return candleDataList.get(candleDataList.size() - 1); // Last candle
            }
        } catch (Exception e) {
            System.err.println("❌ Error getting current candle for " + instrument + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Check if we have sufficient historical data for an instrument
     */
    public boolean hasSufficientData(String instrument) {
        List<CandleData> candleDataList = historicalDataMap.get(instrument);
        if (candleDataList == null || candleDataList.size() < 2) {
            System.out.println("📊 Historical Data Status for " + instrument + ": " +
                    (candleDataList == null ? "No data" : candleDataList.size() + " candles"));
            return false;
        }
        return true;
    }

    /**
     * Get number of candles available for an instrument
     */
    public int getCandleCount(String instrument) {
        List<CandleData> candleDataList = historicalDataMap.get(instrument);
        return candleDataList != null ? candleDataList.size() : 0;
    }

    /**
     * Print historical data for debugging
     */
    public void printHistoricalData(String instrument) {
        List<CandleData> candleDataList = historicalDataMap.get(instrument);
        if (candleDataList != null) {
            System.out.println("📊 Historical Data for " + instrument + " (" + candleDataList.size() + " candles):");
            for (int i = 0; i < candleDataList.size(); i++) {
                CandleData candle = candleDataList.get(i);
                System.out.println("   Candle " + i + ": Price=" + candle.getClose() +
                        ", VWAP=" + candle.getVWAP() +
                        ", Time=" + dateFormat.format(candle.getTimestamp()));
            }
        } else {
            System.out.println("📊 No historical data for " + instrument);
        }
    }
}