package com.trading.strategy;

import com.trading.config.AppConfig;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Quote;

import java.text.SimpleDateFormat;
import java.util.*;

public class BatchVWAPAnalyzer {
    private final KiteConnect kiteConnect;
    private final HistoricalDataManager historicalDataManager;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss");

    // Updated constructor to accept HistoricalDataManager
    public BatchVWAPAnalyzer(KiteConnect kiteConnect, HistoricalDataManager historicalDataManager) {
        this.kiteConnect = kiteConnect;
        this.historicalDataManager = historicalDataManager;
    }

    /**
     * Analyze VWAP crossover for given instruments in batch mode
     */
    public Map<String, Boolean> analyzeVWAPCrossovers(List<String> instruments) {
        Map<String, Boolean> crossoverResults = new HashMap<>();

        try {
            System.out.println("🔍 Analyzing VWAP crossovers for " + instruments.size() + " instruments...");

            // STEP 2: Now update the specific instruments we're analyzing
            String[] instrumentsArray = instruments.toArray(new String[0]);
            Map<String, Quote> quotes = kiteConnect.getQuote(instrumentsArray);

            // STEP 3: Check for VWAP crossovers
            for (String instrument : instruments) {
                Quote quote = quotes.get(instrument);
                if (quote != null && quote.ohlc != null) {
                    boolean crossover = checkVWAPCrossover(instrument, quote);
                    crossoverResults.put(instrument, crossover);
                }
            }

        } catch (Exception | KiteException e) {
            System.err.println("❌ Error analyzing VWAP crossovers: " + e.getMessage());
        }

        return crossoverResults;
    }

    /**
     * PROPER VWAP CROSSOVER LOGIC
     */
    private boolean checkVWAPCrossover(String instrument, Quote quote) {
        try {
            // 1. Check buying hours
            if (!isWithinBuyingHours()) {
                return false;
            }

            double currentPrice = quote.lastPrice;
            double currentVWAP = quote.averagePrice;

            // Skip if invalid data
            if (currentPrice <= 0 || currentVWAP <= 0) {
                return false;
            }

            // Check if we have sufficient historical data
            if (!historicalDataManager.hasSufficientData(instrument)) {
                int candleCount = historicalDataManager.getCandleCount(instrument);
                System.out.println("⏳ Waiting for more data for " + instrument +
                        " (currently " + candleCount + " candles, need at least 2)");
                return false;
            }

            // Get previous candle data
            CandleData previousCandle = historicalDataManager.getPreviousCandle(instrument);
            CandleData currentCandle = historicalDataManager.getCurrentCandle(instrument);

            if (previousCandle == null || currentCandle == null) {
                return false;
            }

            double previousPrice = previousCandle.getClose();
            double previousVWAP = previousCandle.getVWAP();

            // DEBUG: Print historical data for analysis
            System.out.println("🔍 VWAP Crossover Analysis for " + instrument + ":");
            System.out.println("   Previous Candle - Price: " + previousPrice + ", VWAP: " + previousVWAP);
            System.out.println("   Current Candle  - Price: " + currentPrice + ", VWAP: " + currentVWAP);

            // PROPER CROSSOVER LOGIC:
            boolean previousWasBelowVWAP = previousPrice < previousVWAP;
            boolean currentCrossedAboveVWAP = currentPrice > currentVWAP;

            boolean crossoverDetected = previousWasBelowVWAP && currentCrossedAboveVWAP;

            if (crossoverDetected) {
                // Calculate price movement percentage
                double priceDifference = Math.abs(currentPrice - previousPrice);
                double priceDifferencePercent = (priceDifference / previousPrice) * 100;

                // NEW CONDITION: Check if price movement is less than 3%
                if (priceDifferencePercent < 3.0) {
                    System.out.println("⏸️  VWAP Crossover detected but price movement too small for " + instrument);
                    System.out.println("   Previous Price: " + previousPrice);
                    System.out.println("   Current Price: " + currentPrice);
                    System.out.println("   Price Movement: " + String.format("%.2f", priceDifferencePercent) + "% < 3%");
                    System.out.println("   ❌ Skipping buy signal due to insufficient price movement");
                    return false;
                }

                // Check if difference between current price and current VWAP is more than 10%
                double priceVWAPDifference = Math.abs(currentPrice - currentVWAP);
                double priceVWAPPercentageDifference = (priceVWAPDifference / currentVWAP) * 100;

                if (priceVWAPPercentageDifference > 10.0) {
                    System.out.println("⏸️  VWAP Crossover detected but Price-VWAP difference too high for " + instrument +
                            ": " + String.format("%.2f", priceVWAPPercentageDifference) + "% > 10%");
                    System.out.println("   Current Price: " + currentPrice + " | Current VWAP: " + currentVWAP);
                    System.out.println("   ❌ Skipping buy signal due to excessive price deviation from VWAP");
                    return false;
                }

                System.out.println("🎯 VWAP CROSSOVER DETECTED for " + instrument);
                System.out.println("   Previous: Price(" + previousPrice + ") < VWAP(" + previousVWAP + ")");
                System.out.println("   Current:  Price(" + currentPrice + ") > VWAP(" + currentVWAP + ")");
                System.out.println("   Price Movement: " + String.format("%.2f", priceDifferencePercent) + "% ✓");
                System.out.println("   Price-VWAP Difference: " + String.format("%.2f", priceVWAPPercentageDifference) + "% ✓");
                System.out.println("   Time: " + dateFormat.format(new Date()));

                // Print full historical data for confirmation
                historicalDataManager.printHistoricalData(instrument);

                return true; // Crossover detected and passed all checks
            } else {
                // Debug why crossover wasn't detected
                System.out.println("📊 No crossover for " + instrument);
                System.out.println("   Previous was below VWAP: " + previousWasBelowVWAP +
                        " (" + previousPrice + " < " + previousVWAP + ")");
                System.out.println("   Current crossed above VWAP: " + currentCrossedAboveVWAP +
                        " (" + currentPrice + " > " + currentVWAP + ")");
            }

        } catch (Exception e) {
            System.err.println("❌ Error checking VWAP crossover for " + instrument + ": " + e.getMessage());
            e.printStackTrace();
        }

        return false;
    }

    // Helper method for buying hours check
    private boolean isWithinBuyingHours() {
        try {
            Calendar cal = Calendar.getInstance();
            int hour = cal.get(Calendar.HOUR_OF_DAY);
            int minute = cal.get(Calendar.MINUTE);
            int currentTimeInMinutes = hour * 60 + minute;

            // Get buying hours from AppConfig (from properties file)
            String buyingStartTime = AppConfig.getBuyingStartTime();  // "09:45"
            String buyingEndTime = AppConfig.getBuyingEndTime();      // "15:15"

            // Parse the time strings from properties
            int startBuyTime = parseTimeToMinutes(buyingStartTime);
            int endBuyTime = parseTimeToMinutes(buyingEndTime);

            boolean isWithin = currentTimeInMinutes >= startBuyTime &&
                    currentTimeInMinutes <= endBuyTime;

            if (!isWithin) {
                String currentTimeStr = hour + ":" + String.format("%02d", minute);
                if (currentTimeInMinutes < startBuyTime) {
                    System.out.println("⏸️ Too early for buying: " + currentTimeStr +
                            " (Buying starts at " + buyingStartTime + ")");
                } else {
                    System.out.println("⏸️ Too late for buying: " + currentTimeStr +
                            " (Buying ends at " + buyingEndTime + ")");
                }
            } else {
                System.out.println("✅ Within buying hours: " + hour + ":" +
                        String.format("%02d", minute));
            }

            return isWithin;

        } catch (Exception e) {
            System.err.println("❌ Error checking buying hours: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    // Helper method to parse time string "HH:MM" to minutes
    private int parseTimeToMinutes(String timeString) {
        try {
            if (timeString != null && timeString.contains(":")) {
                String[] parts = timeString.split(":");
                if (parts.length == 2) {
                    int hours = Integer.parseInt(parts[0].trim());
                    int minutes = Integer.parseInt(parts[1].trim());
                    return hours * 60 + minutes;
                }
            }
            // Default fallback values if parsing fails
            System.err.println("⚠️ Could not parse time: " + timeString + ", using default");
            return timeString.contains("09:45") ? (9 * 60 + 45) : (15 * 60 + 15);
        } catch (NumberFormatException e) {
            System.err.println("❌ Error parsing time string: " + timeString);
            return 0;
        }
    }
}