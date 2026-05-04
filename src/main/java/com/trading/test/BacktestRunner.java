package com.trading.test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.*;
import java.util.*;

public class BacktestRunner {

    private static final SimpleDateFormat DATETIME_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private static final int LOT_SIZE = 75;

    // Track open position (only one at a time)
    private static boolean hasOpenPosition = false;
    private static Trade activeTrade = null;

    // Track pending setups by instrument
    private static final Map<String, PendingHammerSetup> pendingHammerSetups = new HashMap<>();

    public static void main(String[] args) {
        System.out.println("=".repeat(120));
        System.out.println("🔬 HAMMER REVERSAL BACKTEST - Using Your Actual Strategy Logic");
        System.out.println("=".repeat(120));

        // Load all candles
        String csvPath = "D:\\aPPLICATION_i_DEVELOP\\vwap-algo-trading\\logs\\11-04-2026.csv";
        List<Candle> allCandles = loadAllCandles(csvPath);

        if (allCandles.isEmpty()) {
            System.err.println("❌ No data loaded");
            return;
        }

        System.out.println("\n📊 Loaded " + allCandles.size() + " candles");
        System.out.println("   Instruments: " + getUniqueInstruments(allCandles));

        // Run backtest
        runSequentialBacktest(allCandles);
    }

    private static String getUniqueInstruments(List<Candle> candles) {
        Set<String> instruments = new HashSet<>();
        for (Candle c : candles) instruments.add(c.instrument);
        return String.join(", ", instruments);
    }

    private static List<Candle> loadAllCandles(String filename) {
        List<Candle> candles = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(filename), StandardCharsets.UTF_8))) {

            String line;
            int lineNumber = 0;

            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.trim().isEmpty() || line.trim().equals("\"\"") || line.trim().equals("\"\"")) continue;

                if (lineNumber == 1 && line.startsWith("\uFEFF")) {
                    line = line.substring(1);
                }

                line = line.replaceAll("^\"|\"$", "");

                // Skip header
                if (lineNumber == 1 && (line.toLowerCase().contains("instrument") || line.toLowerCase().contains("date"))) {
                    continue;
                }

                String[] parts = line.split(",");
                if (parts.length < 9) continue;

                try {
                    Candle candle = new Candle();
                    candle.instrument = parts[0].trim();
                    String dateStr = parts[1].trim();
                    String timeStr = parts[2].trim();
                    candle.timestamp = parseTimestamp(dateStr, timeStr);
                    candle.open = parseDouble(parts[3]);
                    candle.high = parseDouble(parts[4]);
                    candle.low = parseDouble(parts[5]);
                    candle.close = parseDouble(parts[6]);
                    candle.vwap = parseDouble(parts[7]);
                    candle.range = parseDouble(parts[8]);
                    candle.closePercent = parts.length > 9 ? parseDouble(parts[9]) : 0;

                    candles.add(candle);

                } catch (Exception e) {
                    // Skip
                }
            }

            candles.sort(Comparator.comparing(c -> c.timestamp));

        } catch (IOException e) {
            e.printStackTrace();
        }

        return candles;
    }

    private static void runSequentialBacktest(List<Candle> allCandles) {
        System.out.println("\n" + "=".repeat(120));
        System.out.println("📈 RUNNING SEQUENTIAL BACKTEST");
        System.out.println("=".repeat(120));

        List<Trade> allTrades = new ArrayList<>();

        // Process each candle in chronological order
        for (int i = 10; i < allCandles.size(); i++) {
            Candle currentCandle = allCandles.get(i);
            String currentTime = formatDateTime(currentCandle.timestamp);

            // Get history (last 10 candles before current)
            List<Candle> history = allCandles.subList(Math.max(0, i - 10), i);

            // ===== STEP 1: Check if we have an open position =====
            if (hasOpenPosition && activeTrade != null) {
                boolean stopLossHit = currentCandle.low <= activeTrade.stopLoss;
                boolean targetHit = currentCandle.high >= activeTrade.target;

                if (stopLossHit || targetHit) {
                    double exitPrice = stopLossHit ? activeTrade.stopLoss : activeTrade.target;
                    double pnl = (exitPrice - activeTrade.entryPrice) * LOT_SIZE;

                    activeTrade.exitPrice = exitPrice;
                    activeTrade.exitTime = currentCandle.timestamp;
                    activeTrade.pnl = pnl;
                    activeTrade.exitReason = stopLossHit ? "STOP_LOSS" : "TARGET";

                    allTrades.add(activeTrade);

                    String exitColor = stopLossHit ? "🔴" : "🟢";
                    System.out.println("\n" + exitColor + " POSITION CLOSED at " + currentTime);
                    System.out.println("   Instrument: " + activeTrade.instrument);
                    System.out.println("   Entry: " + formatDateTime(activeTrade.entryTime) + " @ " + String.format("%.2f", activeTrade.entryPrice));
                    System.out.println("   Exit: " + formatDateTime(activeTrade.exitTime) + " @ " + String.format("%.2f", exitPrice));
                    System.out.println("   P&L: ₹" + String.format("%.2f", pnl));
                    System.out.println("   Reason: " + activeTrade.exitReason);

                    hasOpenPosition = false;
                    activeTrade = null;
                    pendingHammerSetups.clear(); // Clear all pending setups
                    continue;
                }
                continue;
            }

            // ===== STEP 2: Check pending hammer setups for breakout =====
            if (!pendingHammerSetups.isEmpty()) {
                for (Map.Entry<String, PendingHammerSetup> entry : new HashMap<>(pendingHammerSetups).entrySet()) {
                    String pendingInstrument = entry.getKey();
                    PendingHammerSetup setup = entry.getValue();

                    if (currentCandle.instrument.equals(pendingInstrument)) {
                        long elapsedMinutes = (currentCandle.timestamp.getTime() - setup.hammerTime.getTime()) / (60 * 1000);

                        if (elapsedMinutes > 30) {
                            System.out.println("\n   ⏰ Hammer setup expired for " + pendingInstrument + " at " + currentTime);
                            pendingHammerSetups.remove(pendingInstrument);
                        } else if (currentCandle.high > setup.hammerHigh) {
                            System.out.println("\n   ✅ HAMMER BREAKOUT CONFIRMED at " + currentTime);
                            System.out.println("      Instrument: " + pendingInstrument);
                            System.out.println("      Entry: " + String.format("%.2f", setup.hammerHigh));
                            System.out.println("      SL: " + String.format("%.2f", setup.hammerLow));
                            System.out.println("      Target: " + String.format("%.2f", setup.target));

                            activeTrade = new Trade();
                            activeTrade.strategy = "Hammer Reversal";
                            activeTrade.instrument = pendingInstrument;
                            activeTrade.entryPrice = setup.hammerHigh;
                            activeTrade.stopLoss = setup.hammerLow;
                            activeTrade.target = setup.target;
                            activeTrade.entryTime = currentCandle.timestamp;

                            hasOpenPosition = true;
                            pendingHammerSetups.remove(pendingInstrument);
                            break;
                        }
                    }
                }
                if (hasOpenPosition) continue;
            }

            // ===== STEP 3: Check for new hammer pattern (using your actual strategy logic) =====
            if (!hasOpenPosition && !pendingHammerSetups.containsKey(currentCandle.instrument)) {
                HammerSignal hammerSignal = checkHammerReversal(history, currentCandle);
                if (hammerSignal != null && hammerSignal.isValid) {
                    System.out.println("\n   🔨 VALID HAMMER DETECTED at " + currentTime);
                    System.out.println("      Instrument: " + currentCandle.instrument);
                    System.out.println("      Open: " + currentCandle.open + ", High: " + currentCandle.high);
                    System.out.println("      Low: " + currentCandle.low + ", Close: " + currentCandle.close);
                    System.out.println("      Lower Wick %: " + String.format("%.1f", hammerSignal.lowerWickPercent) + "%");
                    System.out.println("      Entry (Hammer High): " + String.format("%.2f", hammerSignal.entryPrice));
                    System.out.println("      Stop Loss (Hammer Low): " + String.format("%.2f", hammerSignal.stopLoss));
                    System.out.println("      Target: " + String.format("%.2f", hammerSignal.target));
                    System.out.println("      Waiting for next candle to break hammer high...");

                    PendingHammerSetup setup = new PendingHammerSetup();
                    setup.instrument = currentCandle.instrument;
                    setup.hammerHigh = hammerSignal.entryPrice;
                    setup.hammerLow = hammerSignal.stopLoss;
                    setup.target = hammerSignal.target;
                    setup.hammerTime = currentCandle.timestamp;

                    pendingHammerSetups.put(currentCandle.instrument, setup);
                }
            }
        }

        // Close any open position at end of day
        if (hasOpenPosition && activeTrade != null) {
            Candle lastCandle = allCandles.get(allCandles.size() - 1);
            double pnl = (lastCandle.close - activeTrade.entryPrice) * LOT_SIZE;
            activeTrade.exitPrice = lastCandle.close;
            activeTrade.exitTime = lastCandle.timestamp;
            activeTrade.pnl = pnl;
            activeTrade.exitReason = "END_OF_DAY";
            allTrades.add(activeTrade);

            System.out.println("\n   🔴 END OF DAY CLOSE at " + formatDateTime(lastCandle.timestamp));
            System.out.println("      Instrument: " + activeTrade.instrument);
            System.out.println("      P&L: ₹" + String.format("%.2f", pnl));
        }

        // Print results
        printResults(allTrades);
    }

    /**
     * Check for Hammer Reversal pattern using your actual strategy logic
     * from HammerReversalStrategy.java
     */
    private static HammerSignal checkHammerReversal(List<Candle> history, Candle current) {
        HammerSignal signal = new HammerSignal();
        signal.isValid = false;

        if (history.size() < 5) return signal;

        // ===== CONDITION 1: Check for Downtrend =====
        boolean isDowntrend = checkDowntrend(history);
        double downtrendPercent = calculateDowntrendPercent(history, current);

        if (!isDowntrend || downtrendPercent < 1.5) {
            return signal;
        }

        // ===== CONDITION 2: Check if it's a valid Hammer =====
        if (!isValidHammerPattern(current)) {
            return signal;
        }

        // ===== CONDITION 3: Hammer should be at or near bottom of downtrend =====
        // FIXED: Not requiring absolute bottom, just near the bottom
        boolean isNearBottom = isHammerNearBottom(history, current);
        if (!isNearBottom) {
            return signal;
        }

        // All conditions met
        signal.isValid = true;
        signal.entryPrice = current.high;
        signal.stopLoss = current.low;
        signal.target = current.high + (current.high - current.low) * 2;
        signal.lowerWickPercent = getLowerWickPercent(current);
        signal.downtrendPercent = downtrendPercent;

        return signal;
    }

    /**
     * Validates Hammer pattern using your actual HammerReversalStrategy.java logic
     */
    private static boolean isValidHammerPattern(Candle candle) {
        double totalRange = candle.high - candle.low;
        if (totalRange <= 0.01) return false;

        double bodySize = Math.abs(candle.close - candle.open);
        double lowerWick = Math.min(candle.close, candle.open) - candle.low;
        double upperWick = candle.high - Math.max(candle.close, candle.open);

        double lowerWickPercent = (lowerWick / totalRange) * 100;

        // From your code: Hammer if lower wick is > 40% of total range
        boolean isHammer = lowerWickPercent >= 40;

        // From your code: Body should be in upper half
        boolean bodyInUpperHalf = (candle.close > candle.open) ||
                (candle.close > candle.low + (totalRange * 0.6));

        return isHammer && bodyInUpperHalf;
    }

    private static double getLowerWickPercent(Candle candle) {
        double totalRange = candle.high - candle.low;
        if (totalRange <= 0) return 0;
        double lowerWick = Math.min(candle.close, candle.open) - candle.low;
        return (lowerWick / totalRange) * 100;
    }

    /**
     * Check downtrend - at least 3 of last 5 candles showing downward movement
     */
    private static boolean checkDowntrend(List<Candle> history) {
        if (history.size() < 5) return false;

        int downCount = 0;
        for (int i = history.size() - 5; i < history.size() - 1; i++) {
            if (i >= 0 && history.get(i).close > history.get(i + 1).close) {
                downCount++;
            }
        }
        return downCount >= 3;
    }

    /**
     * Calculate downtrend percentage from highest high to current low
     */
    private static double calculateDowntrendPercent(List<Candle> history, Candle current) {
        if (history.size() < 5) return 0;

        double highestHigh = 0;
        for (int i = history.size() - 5; i < history.size(); i++) {
            highestHigh = Math.max(highestHigh, history.get(i).high);
        }

        if (highestHigh <= 0) return 0;
        return ((highestHigh - current.low) / highestHigh) * 100;
    }

    /**
     * FIXED: Check if hammer is near bottom (not necessarily absolute bottom)
     * Within 2% of the lowest low in last 5 candles
     */
    private static boolean isHammerNearBottom(List<Candle> history, Candle current) {
        double lowestLow = current.low;

        for (int i = Math.max(0, history.size() - 5); i < history.size(); i++) {
            lowestLow = Math.min(lowestLow, history.get(i).low);
        }

        // Hammer low should be within 2% of the lowest low
        double tolerance = lowestLow * 0.02;
        return current.low <= lowestLow + tolerance;
    }

    private static void printResults(List<Trade> allTrades) {
        System.out.println("\n" + "=".repeat(120));
        System.out.println("📊 FINAL BACKTEST RESULTS");
        System.out.println("=".repeat(120));

        double totalPnL = allTrades.stream().mapToDouble(t -> t.pnl).sum();
        long winningTrades = allTrades.stream().filter(t -> t.pnl > 0).count();
        long losingTrades = allTrades.stream().filter(t -> t.pnl < 0).count();

        System.out.println("\n📈 OVERALL STATISTICS:");
        System.out.println("   ┌─────────────────────────────────────────────────────────────────────────┐");
        System.out.printf("   │ Total Trades:      %-70d │%n", allTrades.size());
        System.out.printf("   │ Winning Trades:    %-70d │%n", winningTrades);
        System.out.printf("   │ Losing Trades:     %-70d │%n", losingTrades);
        System.out.printf("   │ Win Rate:          %-70.1f%% │%n", winningTrades * 100.0 / Math.max(1, allTrades.size()));
        System.out.printf("   │ Total P&L:         ₹%-69.2f │%n", totalPnL);
        System.out.println("   └─────────────────────────────────────────────────────────────────────────┘");

        if (allTrades.isEmpty()) {
            System.out.println("\n⚠️ No trades executed. Check if hammer patterns were detected.");
            return;
        }

        System.out.println("\n📝 DETAILED TRADE LOG:");
        System.out.println("┌───┬─────────────────────────────────┬─────────────────────┬──────────────────────────┬──────────────────────────┬────────────┬────────────┬────────────┬────────────┬────────────┬───────────────┐");
        System.out.println("│ # │ Instrument                      │ Strategy            │ Buy Time                 │ Sell Time                 │ Entry Price│ System SL  │ System Tgt │ Actual Exit│ P&L        │ Exit Reason   │");
        System.out.println("├───┼─────────────────────────────────┼─────────────────────┼──────────────────────────┼──────────────────────────┼────────────┼────────────┼────────────┼────────────┼────────────┼───────────────┤");

        int tradeNum = 1;
        for (Trade trade : allTrades) {
            String instrument = trade.instrument.length() > 31 ?
                    trade.instrument.substring(0, 28) + "..." : trade.instrument;

            String buyTime = formatDateTime(trade.entryTime);
            String sellTime = formatDateTime(trade.exitTime);

            System.out.printf("│%2d │ %-31s │ %-19s │ %24s │ %24s │ %10.2f │ %10.2f │ %10.2f │ %10.2f │ ₹%8.2f │ %-13s │%n",
                    tradeNum++, instrument, trade.strategy, buyTime, sellTime,
                    trade.entryPrice, trade.stopLoss, trade.target, trade.exitPrice, trade.pnl, trade.exitReason);
        }
        System.out.println("└───┴─────────────────────────────────┴─────────────────────┴──────────────────────────┴──────────────────────────┴────────────┴────────────┴────────────┴────────────┴────────────┴───────────────┘");
    }

    private static Date parseTimestamp(String dateStr, String timeStr) {
        try {
            return DATETIME_FORMAT.parse(dateStr + " " + timeStr + ":00");
        } catch (ParseException e) {
            return new Date();
        }
    }

    private static double parseDouble(String value) {
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String formatDateTime(Date date) {
        return DATETIME_FORMAT.format(date);
    }

    // ===== DATA CLASSES =====
    static class Candle {
        String instrument;
        Date timestamp;
        double open, high, low, close, vwap, range, closePercent;
    }

    static class Trade {
        String strategy;
        String instrument;
        double entryPrice;
        double stopLoss;
        double target;
        double exitPrice;
        double pnl;
        Date entryTime;
        Date exitTime;
        String exitReason;
    }

    static class PendingHammerSetup {
        String instrument;
        double hammerHigh;
        double hammerLow;
        double target;
        Date hammerTime;
    }

    static class HammerSignal {
        boolean isValid;
        double entryPrice;
        double stopLoss;
        double target;
        double lowerWickPercent;
        double downtrendPercent;
    }
}