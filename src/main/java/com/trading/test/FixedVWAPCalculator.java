//package com.trading.strategy;
//
//import java.util.*;
//
//public class FixedVWAPCalculator {
//    private Map<String, List<CandleData>> candleDataMap;
//    private final int vwapPeriod;
//    private Map<String, Double> cumulativeTPVMap;
//    private Map<String, Double> cumulativeVolumeMap;
//
//    public FixedVWAPCalculator(int period) {
//        this.vwapPeriod = period;
//        this.candleDataMap = new HashMap<>();
//        this.cumulativeTPVMap = new HashMap<>();
//        this.cumulativeVolumeMap = new HashMap<>();
//    }
//
//    /**
//     * Method 1: Standard VWAP (High + Low + Close) / 3
//     */
//    public double calculateStandardVWAP(String symbol) {
//        List<CandleData> candles = candleDataMap.get(symbol);
//        if (candles == null || candles.size() < vwapPeriod) {
//            return 0.0;
//        }
//
//        double cumulativeTPV = 0.0;
//        double cumulativeVolume = 0.0;
//
//        for (CandleData candle : candles) {
//            double typicalPrice = (candle.getHigh() + candle.getLow() + candle.getClose()) / 3;
//            cumulativeTPV += typicalPrice * candle.getVolume();
//            cumulativeVolume += candle.getVolume();
//        }
//
//        return cumulativeVolume > 0 ? cumulativeTPV / cumulativeVolume : 0.0;
//    }
//
//    /**
//     * Method 2: Alternative VWAP (High + Low + 2*Close) / 4
//     */
//    public double calculateAlternativeVWAP(String symbol) {
//        List<CandleData> candles = candleDataMap.get(symbol);
//        if (candles == null || candles.size() < vwapPeriod) {
//            return 0.0;
//        }
//
//        double cumulativeTPV = 0.0;
//        double cumulativeVolume = 0.0;
//
//        for (CandleData candle : candles) {
//            double typicalPrice = (candle.getHigh() + candle.getLow() + 2 * candle.getClose()) / 4;
//            cumulativeTPV += typicalPrice * candle.getVolume();
//            cumulativeVolume += candle.getVolume();
//        }
//
//        return cumulativeVolume > 0 ? cumulativeTPV / cumulativeVolume : 0.0;
//    }
//
//    /**
//     * Method 3: Session VWAP (from first candle of the day)
//     */
//    public double calculateSessionVWAP(String symbol) {
//        List<CandleData> candles = candleDataMap.get(symbol);
//        if (candles == null || candles.isEmpty()) {
//            return 0.0;
//        }
//
//        // Reset cumulative values for new session
//        if (!cumulativeTPVMap.containsKey(symbol)) {
//            cumulativeTPVMap.put(symbol, 0.0);
//            cumulativeVolumeMap.put(symbol, 0.0);
//        }
//
//        // Get the latest candle
//        CandleData latestCandle = candles.get(candles.size() - 1);
//
//        // Update cumulative values
//        double typicalPrice = (latestCandle.getHigh() + latestCandle.getLow() + latestCandle.getClose()) / 3;
//        double currentTPV = typicalPrice * latestCandle.getVolume();
//
//        double newCumulativeTPV = cumulativeTPVMap.get(symbol) + currentTPV;
//        double newCumulativeVolume = cumulativeVolumeMap.get(symbol) + latestCandle.getVolume();
//
//        cumulativeTPVMap.put(symbol, newCumulativeTPV);
//        cumulativeVolumeMap.put(symbol, newCumulativeVolume);
//
//        return newCumulativeVolume > 0 ? newCumulativeTPV / newCumulativeVolume : 0.0;
//    }
//
//    /**
//     * Method 4: Incremental VWAP (correct cumulative calculation)
//     */
//    public double calculateIncrementalVWAP(String symbol) {
//        List<CandleData> candles = candleDataMap.get(symbol);
//        if (candles == null || candles.size() < vwapPeriod) {
//            return 0.0;
//        }
//
//        // Take only the last vwapPeriod candles
//        int startIndex = Math.max(0, candles.size() - vwapPeriod);
//        List<CandleData> recentCandles = candles.subList(startIndex, candles.size());
//
//        double cumulativeTPV = 0.0;
//        double cumulativeVolume = 0.0;
//
//        for (CandleData candle : recentCandles) {
//            // Try different typical price formulas
//            double typicalPrice1 = (candle.getHigh() + candle.getLow() + candle.getClose()) / 3;
//            double typicalPrice2 = (candle.getHigh() + candle.getLow() + 2 * candle.getClose()) / 4;
//            double typicalPrice3 = (candle.getHigh() + candle.getLow() + candle.getOpen() + candle.getClose()) / 4;
//
//            // Use the first method for calculation
//            cumulativeTPV += typicalPrice1 * candle.getVolume();
//            cumulativeVolume += candle.getVolume();
//        }
//
//        return cumulativeVolume > 0 ? cumulativeTPV / cumulativeVolume : 0.0;
//    }
//
//    public void addCandleData(CandleData candle) {
//        String symbol = candle.getSymbol();
//        if (!candleDataMap.containsKey(symbol)) {
//            candleDataMap.put(symbol, new ArrayList<>());
//        }
//
//        List<CandleData> candles = candleDataMap.get(symbol);
//        candles.add(candle);
//
//        // Keep only the required number of candles for VWAP calculation
//        if (candles.size() > vwapPeriod * 2) { // Keep extra for analysis
//            candles.remove(0);
//        }
//    }
//
//    /**
//     * Diagnostic method to compare different VWAP calculations
//     */
//    public Map<String, Double> compareVWAPMethods(String symbol, double actualVWAP) {
//        Map<String, Double> results = new HashMap<>();
//
//        double method1 = calculateStandardVWAP(symbol);
//        double method2 = calculateAlternativeVWAP(symbol);
//        double method3 = calculateSessionVWAP(symbol);
//        double method4 = calculateIncrementalVWAP(symbol);
//
//        results.put("Standard_VWAP", method1);
//        results.put("Alternative_VWAP", method2);
//        results.put("Session_VWAP", method3);
//        results.put("Incremental_VWAP", method4);
//        results.put("Actual_VWAP", actualVWAP);
//
//        // Calculate differences
//        results.put("Diff_Standard", Math.abs(method1 - actualVWAP));
//        results.put("Diff_Alternative", Math.abs(method2 - actualVWAP));
//        results.put("Diff_Session", Math.abs(method3 - actualVWAP));
//        results.put("Diff_Incremental", Math.abs(method4 - actualVWAP));
//
//        return results;
//    }
//}