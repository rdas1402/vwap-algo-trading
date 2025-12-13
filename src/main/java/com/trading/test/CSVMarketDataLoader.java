// CSVMarketDataLoader.java
package com.trading.test;

import com.trading.strategy.CandleData;

import java.io.BufferedReader;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.*;

public class CSVMarketDataLoader {

    public static List<CandleData> loadCandleDataFromCSV(String filePath, String symbol) {
        List<CandleData> candles = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String line;
            boolean headerSkipped = false;
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

            while ((line = br.readLine()) != null) {
                if (!headerSkipped) {
                    headerSkipped = true;
                    continue; // Skip header row
                }

                String[] values = line.split(",");
                if (values.length >= 7) {
                    // Updated CSV format: date,open,high,low,close,volume,vwap
                    // OR: date,open,high,low,close,vwap (if volume is not available)
                    Date timestamp = dateFormat.parse(values[0]);
                    double open = Double.parseDouble(values[1]);
                    double high = Double.parseDouble(values[2]);
                    double low = Double.parseDouble(values[3]);
                    double close = Double.parseDouble(values[4]);

                    // Handle both formats: with volume and without volume
                    double vwap;
                    if (values.length >= 7) {
                        // Format: date,open,high,low,close,volume,vwap
                        vwap = Double.parseDouble(values[6]);
                    } else {
                        // Format: date,open,high,low,close,vwap
                        vwap = Double.parseDouble(values[5]);
                    }

                    // Use volume if available, otherwise use 0
                    long volume = (values.length >= 7 && !values[5].isEmpty()) ? Long.parseLong(values[5]) : 0;

                    CandleData candle = new CandleData(symbol, open, high, low, close, volume, timestamp);
                    // Store VWAP in the candle object (we'll extend CandleData or use a map)
                    candle.setVWAP(vwap); // You'll need to add this method to CandleData

                    candles.add(candle);

                    System.out.printf("Loaded: %s - O:%.2f H:%.2f L:%.2f C:%.2f VWAP:%.2f%n",
                            timestamp, open, high, low, close, vwap);
                }
            }
        } catch (Exception e) {
            System.err.println("Error loading CSV data: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("Total candles loaded: " + candles.size());
        return candles;
    }

    // Alternative method that returns both candles and VWAP values
    public static Map<String, List<CandleData>> loadCandleDataWithVWAP(String filePath, String symbol) {
        List<CandleData> candles = new ArrayList<>();
        List<Double> vwapValues = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String line;
            boolean headerSkipped = false;
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

            while ((line = br.readLine()) != null) {
                if (!headerSkipped) {
                    headerSkipped = true;
                    continue;
                }

                String[] values = line.split(",");
                if (values.length >= 6) {
                    Date timestamp = dateFormat.parse(values[0]);
                    double open = Double.parseDouble(values[1]);
                    double high = Double.parseDouble(values[2]);
                    double low = Double.parseDouble(values[3]);
                    double close = Double.parseDouble(values[4]);
                    double vwap = Double.parseDouble(values[5]); // VWAP is now at position 5

                    long volume = 0; // Since volume is replaced by VWAP

                    CandleData candle = new CandleData(symbol, open, high, low, close, volume, timestamp);
                    candles.add(candle);
                    vwapValues.add(vwap);

                    System.out.printf("Loaded: %s - O:%.2f H:%.2f L:%.2f C:%.2f VWAP:%.2f%n",
                            timestamp, open, high, low, close, vwap);
                }
            }
        } catch (Exception e) {
            System.err.println("Error loading CSV data: " + e.getMessage());
            e.printStackTrace();
        }

        Map<String, List<CandleData>> result = new HashMap<>();
        result.put("candles", candles);

        // Store VWAP values separately since CandleData doesn't have VWAP field
        List<CandleData> vwapCandles = new ArrayList<>();
        for (int i = 0; i < candles.size(); i++) {
            vwapCandles.add(candles.get(i));
        }
        result.put("vwap_data", vwapCandles);

        System.out.println("Total candles loaded with VWAP: " + candles.size());
        return result;
    }
}