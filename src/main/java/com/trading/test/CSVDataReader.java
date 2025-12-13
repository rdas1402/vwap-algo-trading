package com.trading.test;

import com.trading.strategy.CandleData;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class CSVDataReader {

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public static List<CandleData> readNifty50CSV(String filePath) {
        List<CandleData> candles = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String line;
            boolean isHeader = true;

            while ((line = br.readLine()) != null) {
                // Skip empty lines
                if (line.trim().isEmpty()) {
                    continue;
                }

                if (isHeader) {
                    // Skip header line
                    isHeader = false;
                    continue;
                }

                CandleData candle = parseCandleLine(line);
                if (candle != null) {
                    candles.add(candle);
                }
            }

            System.out.println("✅ Successfully loaded " + candles.size() + " candles from CSV");

        } catch (IOException e) {
            System.err.println("❌ Error reading CSV file: " + e.getMessage());
        }

        return candles;
    }

    private static CandleData parseCandleLine(String line) {
        try {
            // Split by comma
            String[] parts = line.split(",");

            if (parts.length < 8) {
                System.out.println("⚠️ Skipping invalid line (not enough columns): " + line);
                return null;
            }

            // Parse timestamp
            String dateTimeStr = parts[0].trim();
            Date timestamp = DATE_FORMAT.parse(dateTimeStr);

            // Parse OHLC prices
            double open = Double.parseDouble(parts[1].trim());
            double high = Double.parseDouble(parts[2].trim());
            double low = Double.parseDouble(parts[3].trim());
            double close = Double.parseDouble(parts[4].trim());

            // Skip change percentage (parts[5])

            // Parse VWAP
            double vwap = Double.parseDouble(parts[6].trim());

            // Parse volume
            long volume = Long.parseLong(parts[7].trim());

            return new CandleData("NSE:NIFTY 50", open, high, low, close, volume, timestamp, vwap);

        } catch (Exception e) {
            System.err.println("❌ Error parsing line: " + line + " - " + e.getMessage());
            return null;
        }
    }

    public static void printCandleData(List<CandleData> candles) {
        System.out.println("\n📋 Loaded Candle Data:");
        System.out.println("=".repeat(120));
        System.out.printf("%-20s %-10s %-10s %-10s %-10s %-10s %-12s %-15s%n",
                "Timestamp", "Open", "High", "Low", "Close", "Change", "VWAP", "Volume");
        System.out.println("-".repeat(120));

        for (CandleData candle : candles) {
            System.out.printf("%-20s %-10.2f %-10.2f %-10.2f %-10.2f %-10.2f %-12.2f %-,15d%n",
                    DATE_FORMAT.format(candle.getTimestamp()),
                    candle.getOpen(),
                    candle.getHigh(),
                    candle.getLow(),
                    candle.getClose(),
                    candle.getClose() - candle.getOpen(),
                    candle.getVWAP(),
                    candle.getVolume());
        }
        System.out.println("=".repeat(120));
    }
}