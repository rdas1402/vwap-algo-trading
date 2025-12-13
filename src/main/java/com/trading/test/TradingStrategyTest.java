//// TradingStrategyTest.java
//package com.trading.test;
//
//import com.trading.strategy.CandleData;
//import java.io.BufferedReader;
//import java.io.FileReader;
//import java.text.SimpleDateFormat;
//import java.util.*;
//
//public class TradingStrategyTest {
//
//    public static void main(String[] args) {
//        // Update this path to your CSV file location
//        String csvFilePath = "D:\\aPPLICATION_i_DEVELOP\\vwap-algo-trading\\2025-11-17.csv";
//
//        System.out.println("Loading CSV data with VWAP from: " + csvFilePath);
//
//        // Load candles and VWAP values separately
//        List<CandleData> candles = loadCandlesFromCSV(csvFilePath, "NSE:NIFTY 50");
//        List<Double> vwapValues = extractVWAPFromCSV(csvFilePath);
//
//        if (candles.isEmpty() || vwapValues.isEmpty()) {
//            System.out.println("No data loaded. Please check the CSV file path and format.");
//            return;
//        }
//
//        if (candles.size() != vwapValues.size()) {
//            System.out.println("Warning: Candle count (" + candles.size() +
//                    ") doesn't match VWAP count (" + vwapValues.size() + ")");
//            // Use the smaller size to avoid index issues
//            int minSize = Math.min(candles.size(), vwapValues.size());
//            candles = candles.subList(0, minSize);
//            vwapValues = vwapValues.subList(0, minSize);
//        }
//
//        // Create and run test strategy
//        TestTradingStrategy testStrategy = new TestTradingStrategy();
//        testStrategy.loadHistoricalData("NSE:NIFTY 50", candles, vwapValues);
//        testStrategy.runBacktest();
//    }
//
//    private static List<CandleData> loadCandlesFromCSV(String filePath, String symbol) {
//        List<CandleData> candles = new ArrayList<>();
//
//        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
//            String line;
//            boolean headerSkipped = false;
//            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
//
//            while ((line = br.readLine()) != null) {
//                if (!headerSkipped) {
//                    headerSkipped = true;
//                    continue; // Skip header row
//                }
//
//                String[] values = line.split(",");
//                if (values.length >= 7) {
//                    // Format: date,open,high,low,close,volume,vwap
//                    Date timestamp = dateFormat.parse(values[0]);
//                    double open = Double.parseDouble(values[1]);
//                    double high = Double.parseDouble(values[2]);
//                    double low = Double.parseDouble(values[3]);
//                    double close = Double.parseDouble(values[4]);
//                    long volume = Long.parseLong(values[5]);
//                    // VWAP at values[6] is handled separately
//
//                    CandleData candle = new CandleData(symbol, open, high, low, close, volume, timestamp);
//                    candles.add(candle);
//
//                }
//            }
//        } catch (Exception e) {
//            System.err.println("Error loading candles from CSV: " + e.getMessage());
//            e.printStackTrace();
//        }
//
//        System.out.println("Loaded " + candles.size() + " candles from CSV");
//        return candles;
//    }
//
//    private static List<Double> extractVWAPFromCSV(String filePath) {
//        List<Double> vwapValues = new ArrayList<>();
//
//        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
//            String line;
//            boolean headerSkipped = false;
//
//            while ((line = br.readLine()) != null) {
//                if (!headerSkipped) {
//                    headerSkipped = true;
//                    continue; // Skip header row: date,open,high,low,close,volume,vwap
//                }
//
//                String[] values = line.split(",");
//                if (values.length >= 7) {
//                    // VWAP is at index 6 (7th column)
//                    try {
//                        double vwap = Double.parseDouble(values[6].trim());
//                        vwapValues.add(vwap);
//                    } catch (NumberFormatException e) {
//                        System.err.println("Error parsing VWAP value: '" + values[6] + "' in line: " + line);
//                        vwapValues.add(0.0); // Add default value if parsing fails
//                    }
//                } else {
//                    System.err.println("Invalid line format, expected 7 columns: " + line);
//                }
//            }
//
//            System.out.println("Successfully extracted " + vwapValues.size() + " VWAP values from CSV");
//
//        } catch (Exception e) {
//            System.err.println("Error extracting VWAP from CSV: " + e.getMessage());
//            e.printStackTrace();
//        }
//
//        return vwapValues;
//    }
//
//    // Alternative: Single method that loads both candles and VWAP together
//    private static Map<String, Object> loadCandlesWithVWAP(String filePath, String symbol) {
//        List<CandleData> candles = new ArrayList<>();
//        List<Double> vwapValues = new ArrayList<>();
//
//        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
//            String line;
//            boolean headerSkipped = false;
//            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
//
//            while ((line = br.readLine()) != null) {
//                if (!headerSkipped) {
//                    headerSkipped = true;
//                    continue;
//                }
//
//                String[] values = line.split(",");
//                if (values.length >= 7) {
//                    // Parse candle data
//                    Date timestamp = dateFormat.parse(values[0]);
//                    double open = Double.parseDouble(values[1]);
//                    double high = Double.parseDouble(values[2]);
//                    double low = Double.parseDouble(values[3]);
//                    double close = Double.parseDouble(values[4]);
//                    long volume = Long.parseLong(values[5]);
//                    double vwap = Double.parseDouble(values[6]);
//
//                    CandleData candle = new CandleData(symbol, open, high, low, close, volume, timestamp);
//                    candles.add(candle);
//                    vwapValues.add(vwap);
//
//                    System.out.printf("Loaded: %s | O:%.2f H:%.2f L:%.2f C:%.2f VWAP:%.2f%n",
//                            timestamp, open, high, low, close, vwap);
//                }
//            }
//        } catch (Exception e) {
//            System.err.println("Error loading data from CSV: " + e.getMessage());
//            e.printStackTrace();
//        }
//
//        Map<String, Object> result = new HashMap<>();
//        result.put("candles", candles);
//        result.put("vwap", vwapValues);
//
//        System.out.println("Loaded " + candles.size() + " candles with VWAP data");
//        return result;
//    }
//}