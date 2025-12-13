//package com.trading.test;
//
//import com.trading.strategy.*;
//import com.trading.strategy.FixedVWAPCalculator;
//
//import java.text.SimpleDateFormat;
//import java.util.*;
//
//public class VWAPDiagnosticTest {
//
//    public void diagnoseVWAPDiscrepancy(List<CandleData> candles) {
//        System.out.println("🔍 VWAP Calculation Diagnosis");
//        System.out.println("=".repeat(100));
//
//        FixedVWAPCalculator vwapCalc = new FixedVWAPCalculator(50);
//
//        System.out.printf("%-20s %-10s %-12s %-12s %-12s %-12s %-12s %-10s%n",
//            "Timestamp", "Close", "Std VWAP", "Alt VWAP", "Sess VWAP", "Inc VWAP", "Actual", "Best Match");
//        System.out.println("-".repeat(100));
//
//        for (int i = 0; i < candles.size(); i++) {
//            CandleData candle = candles.get(i);
//            vwapCalc.addCandleData(candle);
//
//            if (i >= 0) { // Wait until we have enough data
//                Map<String, Double> comparisons = vwapCalc.compareVWAPMethods(
//                    "NSE:NIFTY 50", candle.getVWAP());
//
//                // Find the method with smallest difference
//                String bestMethod = findBestMatchingMethod(comparisons);
//
//                System.out.printf("%-20s %-10.2f %-12.2f %-12.2f %-12.2f %-12.2f %-12.2f %-10s%n",
//                    new SimpleDateFormat("MM-dd HH:mm").format(candle.getTimestamp()),
//                    candle.getClose(),
//                    comparisons.get("Standard_VWAP"),
//                    comparisons.get("Alternative_VWAP"),
//                    comparisons.get("Session_VWAP"),
//                    comparisons.get("Incremental_VWAP"),
//                    comparisons.get("Actual_VWAP"),
//                    bestMethod);
//            }
//        }
//
//        // Analyze which method works best overall
//        analyzeBestMethod(candles, vwapCalc);
//    }
//
//    private String findBestMatchingMethod(Map<String, Double> comparisons) {
//        double minDiff = Double.MAX_VALUE;
//        String bestMethod = "None";
//
//        for (String method : Arrays.asList("Diff_Standard", "Diff_Alternative", "Diff_Session", "Diff_Incremental")) {
//            double diff = comparisons.get(method);
//            if (diff < minDiff) {
//                minDiff = diff;
//                bestMethod = method.replace("Diff_", "");
//            }
//        }
//
//        return minDiff < 1.0 ? "✅ " + bestMethod : "⚠️ " + bestMethod;
//    }
//
//    private void analyzeBestMethod(List<CandleData> candles, FixedVWAPCalculator vwapCalc) {
//        System.out.println("\n📊 VWAP Method Analysis Summary");
//        System.out.println("-".repeat(80));
//
//        Map<String, Integer> methodWins = new HashMap<>();
//        methodWins.put("Standard", 0);
//        methodWins.put("Alternative", 0);
//        methodWins.put("Session", 0);
//        methodWins.put("Incremental", 0);
//
//        int totalComparisons = 0;
//
//        for (int i = 49; i < candles.size(); i++) {
//            CandleData candle = candles.get(i);
//            Map<String, Double> comparisons = vwapCalc.compareVWAPMethods("NSE:NIFTY 50", candle.getVWAP());
//
//            String bestMethod = findBestMatchingMethod(comparisons).replace("✅ ", "").replace("⚠️ ", "");
//            methodWins.put(bestMethod, methodWins.get(bestMethod) + 1);
//            totalComparisons++;
//        }
//
//        System.out.println("Method Performance (number of times each method was closest to actual VWAP):");
//        for (Map.Entry<String, Integer> entry : methodWins.entrySet()) {
//            double percentage = (entry.getValue() * 100.0) / totalComparisons;
//            System.out.printf("  %-15s: %3d times (%5.1f%%)%n",
//                entry.getKey(), entry.getValue(), percentage);
//        }
//
//        // Check volume patterns
//        analyzeVolumePatterns(candles);
//    }
//
//    private void analyzeVolumePatterns(List<CandleData> candles) {
//        System.out.println("\n📈 Volume Pattern Analysis");
//        System.out.println("-".repeat(80));
//
//        long totalVolume = 0;
//        for (CandleData candle : candles) {
//            totalVolume += candle.getVolume();
//        }
//
//        double avgVolume = totalVolume / (double) candles.size();
//        System.out.printf("Average Volume per 5-min candle: %,.0f%n", avgVolume);
//        System.out.printf("Total session volume: %,d%n", totalVolume);
//
//        // Check if volumes are cumulative or incremental
//        System.out.println("\nFirst 5 candles volume pattern:");
//        for (int i = 0; i < Math.min(5, candles.size()); i++) {
//            CandleData candle = candles.get(i);
//            System.out.printf("  %s: Volume=%,d%n",
//                new SimpleDateFormat("HH:mm").format(candle.getTimestamp()),
//                candle.getVolume());
//        }
//    }
//
//    public static void main(String[] args) {
//        VWAPDiagnosticTest diagnostic = new VWAPDiagnosticTest();
//
//        // Load your CSV data
//        List<CandleData> candles = CSVDataReader.readNifty50CSV("D:\\aPPLICATION_i_DEVELOP\\vwap-algo-trading\\2025-11-21.csv");
//
//        if (!candles.isEmpty()) {
//            diagnostic.diagnoseVWAPDiscrepancy(candles);
//        }
//    }
//}