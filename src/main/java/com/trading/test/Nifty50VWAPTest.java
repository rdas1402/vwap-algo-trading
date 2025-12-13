//package com.trading.test;
//
//import com.trading.strategy.*;
//
//import java.text.SimpleDateFormat;
//import java.util.*;
//import java.io.*;
//
//public class Nifty50VWAPTest {
//
//    private static final String TEST_CSV_FILE = "D:\\aPPLICATION_i_DEVELOP\\vwap-algo-trading\\2025-11-24.csv";
//
//    public void testVWAPCalculationWithCSVData() {
//        System.out.println("🧪 Testing VWAP Calculation with CSV Data");
//        System.out.println("=".repeat(80));
//
//        // Read CSV data
//        List<CandleData> candles = CSVDataReader.readNifty50CSV(TEST_CSV_FILE);
//
//        if (candles.isEmpty()) {
//            System.out.println("❌ No data found in CSV file: " + TEST_CSV_FILE);
//            return;
//        }
//
//        // Print loaded data
//        CSVDataReader.printCandleData(candles);
//
//        // Initialize VWAP strategy
//        VWAPStrategy vwapStrategy = new VWAPStrategy(20); // 20-period VWAP
//
//        // Test 1: Feed data and calculate VWAP
//        System.out.println("\n📊 Test 1: VWAP Calculation Comparison");
//        System.out.println("-".repeat(100));
//        System.out.printf("%-20s %-10s %-12s %-12s %-10s %-10s%n",
//                "Timestamp", "Close", "Calc VWAP", "Actual VWAP", "Diff", "Status");
//        System.out.println("-".repeat(100));
//
//        for (int i = 0; i < candles.size(); i++) {
//            CandleData candle = candles.get(i);
//            vwapStrategy.addCandleData(candle);
//
//            double calculatedVWAP = vwapStrategy.calculateVWAP("NSE:NIFTY 50");
//            double actualVWAP = candle.getVWAP();
//            double difference = Math.abs(calculatedVWAP - actualVWAP);
//
//            String status = difference < 1.0 ? "✅" : "⚠️";
//
//            System.out.printf("%-20s %-10.2f %-12.2f %-12.2f %-10.4f %-10s%n",
//                    new SimpleDateFormat("MM-dd HH:mm").format(candle.getTimestamp()),
//                    candle.getClose(),
//                    calculatedVWAP,
//                    actualVWAP,
//                    difference,
//                    status);
//        }
//
//        // Test 2: Generate VWAP trading signals
//        System.out.println("\n📈 Test 2: VWAP Trading Signal Generation");
//        System.out.println("-".repeat(80));
//
//        int totalSignals = 0;
//        for (int i = 1; i < candles.size(); i++) {
//            CandleData currentCandle = candles.get(i);
//            CandleData previousCandle = candles.get(i - 1);
//
//            // Create mock market data
//            Map<String, Object> marketData = createMockMarketData(currentCandle);
//
//            List<TradingSignal> signals = vwapStrategy.generateVWAPSignals(marketData);
//
//            if (!signals.isEmpty()) {
//                totalSignals++;
//                System.out.printf("🔄 SIGNAL at %s:%n",
//                        new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(currentCandle.getTimestamp()));
//
//                for (TradingSignal signal : signals) {
//                    System.out.printf("   → %s %s @ %.2f (Close: %.2f)%n",
//                            signal.getSignalType(),
//                            signal.getTradingSymbol(),
//                            signal.getPrice(),
//                            currentCandle.getClose());
//                }
//
//                // Find options for the signal
//                double niftySpot = currentCandle.getClose();
//                Map<String, String> options = vwapStrategy.findOptionsNear100(niftySpot);
//                System.out.printf("   Options: CALL=%s, PUT=%s%n",
//                        options.get("CALL"), options.get("PUT"));
//            }
//        }
//
//        System.out.printf("%nTotal signals generated: %d out of %d candles%n",
//                totalSignals, candles.size() - 1);
//
//        // Test 3: VWAP Crossover Analysis
//        System.out.println("\n🔄 Test 3: Detailed VWAP Crossover Analysis");
//        System.out.println("-".repeat(120));
//        System.out.printf("%-20s %-10s %-12s %-15s %-15s %-10s%n",
//                "Timestamp", "Close", "VWAP", "Position", "Distance", "Crossover");
//        System.out.println("-".repeat(120));
//
//        analyzeVWAPCrossoversDetailed(candles, vwapStrategy);
//
//        // Test 4: Strategy Integration Test
//        System.out.println("\n🎯 Test 4: Strategy Integration Test");
//        System.out.println("-".repeat(80));
//        testStrategyIntegration(candles);
//
//        System.out.println("\n✅ All tests completed successfully!");
//    }
//
//    private Map<String, Object> createMockMarketData(CandleData candle) {
//        Map<String, Object> marketData = new HashMap<>();
//
//        // Create a mock Quote object structure
//        Map<String, Object> quoteData = new HashMap<>();
//        quoteData.put("open", candle.getOpen());
//        quoteData.put("high", candle.getHigh());
//        quoteData.put("low", candle.getLow());
//        quoteData.put("close", candle.getClose());
//        quoteData.put("lastPrice", candle.getClose());
//        quoteData.put("volumeTradedToday", candle.getVolume());
//
//        marketData.put("NSE:NIFTY 50", quoteData);
//        return marketData;
//    }
//
//    private void analyzeVWAPCrossoversDetailed(List<CandleData> candles, VWAPStrategy vwapStrategy) {
//        int aboveCrossovers = 0;
//        int belowCrossovers = 0;
//
//        for (int i = 1; i < candles.size(); i++) {
//            CandleData currentCandle = candles.get(i);
//            CandleData previousCandle = candles.get(i - 1);
//
//            double currentVWAP = vwapStrategy.calculateVWAP("NSE:NIFTY 50");
//
//            if (currentVWAP == 0) continue;
//
//            double distance = currentCandle.getClose() - currentVWAP;
//            double distancePercent = (distance / currentVWAP) * 100;
//
//            String position = distance > 0 ? "ABOVE" : "BELOW";
//            String crossover = "";
//
//            boolean crossedAbove = (previousCandle.getClose() <= currentVWAP) &&
//                    (currentCandle.getClose() > currentVWAP);
//            boolean crossedBelow = (previousCandle.getClose() >= currentVWAP) &&
//                    (currentCandle.getClose() < currentVWAP);
//
//            if (crossedAbove) {
//                crossover = "🟢 ABOVE";
//                aboveCrossovers++;
//            } else if (crossedBelow) {
//                crossover = "🔴 BELOW";
//                belowCrossovers++;
//            }
//
//            System.out.printf("%-20s %-10.2f %-12.2f %-15s %-15.4f %-10s%n",
//                    new SimpleDateFormat("MM-dd HH:mm").format(currentCandle.getTimestamp()),
//                    currentCandle.getClose(),
//                    currentVWAP,
//                    position,
//                    distancePercent,
//                    crossover);
//        }
//
//        System.out.println("-".repeat(120));
//        System.out.printf("Summary: Above Crossovers: %d | Below Crossovers: %d | Total: %d%n",
//                aboveCrossovers, belowCrossovers, aboveCrossovers + belowCrossovers);
//    }
//
//    private void testStrategyIntegration(List<CandleData> candles) {
//        // Simulate your trading strategy with historical data
//        ZerodhaTradingStrategy strategy = new ZerodhaTradingStrategy();
//
//        System.out.println("Simulating strategy execution with historical data...");
//
//        for (int i = 0; i < Math.min(candles.size(), 10); i++) { // Test with first 10 candles
//            CandleData candle = candles.get(i);
//
//            System.out.printf("⏰ %s - Processing candle: Close=%.2f%n",
//                    new SimpleDateFormat("HH:mm:ss").format(candle.getTimestamp()),
//                    candle.getClose());
//
//            // Test live VWAP data method
//            Map<String, Object> liveData = getLiveVWAPData(strategy, candle);
//
//            if (!liveData.containsKey("error")) {
//                System.out.printf("   📊 Live VWAP: %.2f | Distance: %.2f (%.3f%%) | Position: %s%n",
//                        liveData.get("vwap"),
//                        liveData.get("distance_from_vwap"),
//                        liveData.get("distance_percent"),
//                        liveData.get("position_relative_to_vwap"));
//            }
//
//            // Check if we have sufficient data for trading
//            if ((boolean) liveData.get("has_sufficient_data")) {
//                System.out.println("   ✅ Sufficient data for trading signals");
//            } else {
//                System.out.printf("   ⏳ Collecting data: %d/%d candles%n",
//                        liveData.get("candles_available"),
//                        liveData.get("vwap_period"));
//            }
//        }
//    }
//
//    private Map<String, Object> getLiveVWAPData(ZerodhaTradingStrategy strategy, CandleData candle) {
//        // This simulates the getLiveNifty50VWAPData method
//        Map<String, Object> result = new HashMap<>();
//
//        try {
//            // Simulate VWAP calculation
//            double vwap = candle.getVWAP(); // Using actual VWAP from CSV for testing
//            double distance = candle.getClose() - vwap;
//            double distancePercent = (distance / vwap) * 100;
//
//            result.put("symbol", "NSE:NIFTY 50");
//            result.put("timestamp", candle.getTimestamp());
//            result.put("current_price", candle.getClose());
//            result.put("vwap", vwap);
//            result.put("distance_from_vwap", distance);
//            result.put("distance_percent", distancePercent);
//            result.put("position_relative_to_vwap", distance > 0 ? "ABOVE" : "BELOW");
//            result.put("candles_available", 20); // Assuming we have enough data
//            result.put("vwap_period", 20);
//            result.put("has_sufficient_data", true);
//
//        } catch (Exception e) {
//            result.put("error", "Failed to get live VWAP data: " + e.getMessage());
//        }
//
//        return result;
//    }
//
//    public static void main(String[] args) {
//        Nifty50VWAPTest test = new Nifty50VWAPTest();
//
//        // First, create sample CSV data if it doesn't exist
////        createSampleCSVData();
//
//        // Run the tests
//        test.testVWAPCalculationWithCSVData();
//    }
//
//    private static void createSampleCSVData() {
//        File file = new File(TEST_CSV_FILE);
//        if (file.exists()) {
//            System.out.println("📁 CSV file already exists: " + TEST_CSV_FILE);
//            return;
//        }
//
//        // Create directory if it doesn't exist
//        file.getParentFile().mkdirs();
//
//        String csvContent =
//                "date,open,high,low,close,change,vwap,volume\n" +
//                        "2025-11-24 09:15:00,26133.35,26137.55,26082.30,26097.25,0.13,26105.70,13110000\n" +
//                        "2025-11-24 09:20:00,26096.95,26120.40,26091.15,26098.90,0.01,26104.99,6140000\n" +
//                        "2025-11-24 09:25:00,26099.20,26115.75,26085.50,26092.35,-0.03,26103.45,5820000\n" +
//                        "2025-11-24 09:30:00,26092.80,26108.90,26080.25,26095.60,0.01,26102.88,5980000\n" +
//                        "2025-11-24 09:35:00,26095.25,26125.30,26088.45,26112.75,0.07,26103.15,7120000\n" +
//                        "2025-11-24 09:40:00,26113.10,26128.45,26102.80,26105.90,-0.03,26102.95,6450000\n" +
//                        "2025-11-24 09:45:00,26106.25,26122.60,26098.35,26115.20,0.04,26103.25,5930000\n" +
//                        "2025-11-24 09:50:00,26115.65,26135.80,26108.90,26125.45,0.04,26104.10,6740000\n" +
//                        "2025-11-24 09:55:00,26125.90,26142.15,26118.30,26130.80,0.02,26105.25,5870000\n" +
//                        "2025-11-24 10:00:00,26131.25,26148.90,26122.45,26135.60,0.02,26106.75,6210000\n";
//
//        try (FileWriter writer = new FileWriter(file)) {
//            writer.write(csvContent);
//            System.out.println("✅ Sample CSV file created: " + TEST_CSV_FILE);
//        } catch (IOException e) {
//            System.err.println("❌ Error creating sample CSV: " + e.getMessage());
//        }
//    }
//}