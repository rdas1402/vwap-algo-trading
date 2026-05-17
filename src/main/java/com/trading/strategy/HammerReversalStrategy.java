// HammerReversalStrategy.java - FIXED
package com.trading.strategy;

import com.trading.config.AppConfig;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;

import java.util.*;

public class HammerReversalStrategy implements TradingStrategy {

    private static final int LOOKBACK_CANDLES = 5;
    private static final double MIN_DOWNTREND_PERCENT = 10;
    private static final double MAX_BODY_PERCENT = 1.0;
    private static final double MAX_CLOSE_CHANGE_PERCENT = 2.0;
    private static final int MIN_DOWNTREND_STEPS = 3;

    @Override
    public int getPriority() { return 2; }

    @Override
    public String getStrategyName() { return "Case 2: Hammer Reversal Strategy (Bottom Reversal after Downtrend)"; }

    @Override
    public Map<String, String> findInstruments(TradingStrategyEngine context) throws Exception, KiteException {
        Map<String, String> options = new HashMap<>();
        double targetPremium = AppConfig.getHammerTargetPremium();
        double tolerance = AppConfig.getPremiumTolerance();
        double niftySpot = context.getNiftySpotPrice();
        System.out.println("📊 [Case 2] Nifty Spot Price: " + niftySpot);
        System.out.println("   Target Premium (Hammer): " + targetPremium + " ±" + tolerance);
        String ceOption = context.findOptionNearPremium(niftySpot, true, targetPremium, tolerance);
        String peOption = context.findOptionNearPremium(niftySpot, false, targetPremium, tolerance);
        if (ceOption != null) options.put("CE", ceOption);
        if (peOption != null) options.put("PE", peOption);
        return options;
    }

    @Override
    public Map<String, Object> analyzeInstrument(String instrument, TradingStrategyEngine context) {
        Map<String, Object> result = new HashMap<>();
        result.put("signal", false);
        result.put("pattern", "none");
        result.put("instrument", instrument);

        try {
            System.out.println("\n🔨 [Case 2] Analyzing Hammer Reversal for: " + instrument);

            RealTimeCandleBuilder candleBuilder = context.getRealTimeCandleBuilder();
            int completedCandleCount = candleBuilder.getCompletedCandleCount(instrument);
            if (completedCandleCount < LOOKBACK_CANDLES) {
                System.out.println("⏳ [Case 2] Need " + LOOKBACK_CANDLES + " candles (have " + completedCandleCount + ")");
                return result;
            }

            CandleData hammerCandle = candleBuilder.getLastCompletedCandle(instrument);
            if (hammerCandle == null) return result;

            // STEP 1: First verify that the candle is a valid hammer pattern
            boolean isValidHammer = isValidHammerPattern(hammerCandle);
            System.out.println("\n   🔨 Hammer Pattern Analysis:");
            System.out.println("      - Open: " + String.format("%.2f", hammerCandle.getOpen()));
            System.out.println("      - High: " + String.format("%.2f", hammerCandle.getHigh()));
            System.out.println("      - Low: " + String.format("%.2f", hammerCandle.getLow()));
            System.out.println("      - Close: " + String.format("%.2f", hammerCandle.getClose()));
            System.out.println("      - Valid Hammer: " + isValidHammer);

            if (!isValidHammer) {
                System.out.println("   ❌ Not a valid hammer pattern");
                return result;
            }

            // STEP 2: Check downtrend conditions (steps and magnitude)
            boolean isDowntrend = checkDowntrendRelaxed(instrument, candleBuilder, hammerCandle);
            double downtrendPercent = calculateDowntrendPercentFixed(instrument, candleBuilder, hammerCandle);

            System.out.println("\n   📉 Downtrend Analysis (Fixed):");
            System.out.println("      - Downtrend detected: " + isDowntrend);
            System.out.println("      - Downtrend magnitude: " + String.format("%.2f", downtrendPercent) + "%");
            System.out.println("      - Required: > " + MIN_DOWNTREND_PERCENT + "%");

            if (!isDowntrend || downtrendPercent < MIN_DOWNTREND_PERCENT) {
                System.out.println("   ❌ No significant downtrend before hammer");
                return result;
            }

            // STEP 3: Ensure hammer is at the bottom (no lower low in preceding candles)
            boolean isAtBottom = isHammerAtBottomRelaxed(instrument, candleBuilder, hammerCandle);
            System.out.println("\n   📍 Position Analysis:");
            System.out.println("      - Hammer at bottom of downtrend: " + isAtBottom);
            if (!isAtBottom) {
                System.out.println("   ❌ Hammer not at the bottom of downtrend");
                return result;
            }

            // All conditions satisfied
            System.out.println("\n" + "🔨".repeat(20));
            System.out.println("🔨 [Case 2] VALID HAMMER REVERSAL DETECTED!");
            System.out.println("   ✅ Valid hammer pattern");
            System.out.println("   ✅ Downtrend confirmed: " + String.format("%.2f", downtrendPercent) + "% decline");
            System.out.println("   ✅ Hammer at lowest point of downtrend");
            System.out.println("\n   📊 Hammer Details:");
            System.out.println("      - Hammer Low (SL Level): " + String.format("%.2f", hammerCandle.getLow()));
            System.out.println("      - Hammer High (Breakout): " + String.format("%.2f", hammerCandle.getHigh()));
            System.out.println("      - Hammer Close: " + String.format("%.2f", hammerCandle.getClose()));
            System.out.println("\n   ⏳ Waiting for breakout...");
            System.out.println("      👉 Entry: Break of hammer high (" + String.format("%.2f", hammerCandle.getHigh()) + ")");
            System.out.println("      🛑 Stop Loss: Below hammer low (" + String.format("%.2f", hammerCandle.getLow()) + ")");
            System.out.println("      🎯 Target: 2 × Stop Loss distance");
            System.out.println("🔨".repeat(20));

            double riskAmount = (hammerCandle.getHigh() - hammerCandle.getLow()) * 0.75;
            double target = hammerCandle.getHigh() + ((hammerCandle.getHigh() - hammerCandle.getLow()) * 2);

            if (!context.isPatternBuyTimeAllowed("hammer")) {
                System.out.println("⏸️ Hammer trading allowed only from 09:45 – monitor not started");
                return result;
            }
            context.startHammerBreakoutMonitor(instrument, hammerCandle.getHigh(), riskAmount, target);

            result.put("message", "Hammer detected – monitoring breakout (5 min, 3s checks)");
            result.put("entryPrice", hammerCandle.getHigh());
            result.put("stopLoss", hammerCandle.getLow());
            result.put("target", target);

        } catch (Exception e) {
            System.err.println("❌ [Case 2] Error: " + e.getMessage());
            e.printStackTrace();
        }
        return result;
    }

    // ---------- Helper methods ----------

    /**
     * Checks that the last (LOOKBACK_CANDLES - 1) candles show a downtrend
     * using relaxed rules (price down OR small body OR small close change).
     */
    private boolean checkDowntrendRelaxed(String instrument, RealTimeCandleBuilder candleBuilder, CandleData hammerCandle) {
        List<CandleData> precedingCandles = new ArrayList<>();
        int historySize = CandleHistoryManager.getInstance().getHistorySize(instrument);
        System.out.println("      - History size for " + instrument + ": " + historySize + " candles");

        int index = 1;
        while (precedingCandles.size() < LOOKBACK_CANDLES - 1 && index <= 10) {
            CandleData candle = getCandleAtIndex(instrument, candleBuilder, index);
            if (candle != null && candle.getTimestamp().before(hammerCandle.getTimestamp())) {
                precedingCandles.add(candle);
            }
            index++;
        }
        if (precedingCandles.size() < LOOKBACK_CANDLES - 1) {
            System.out.println("      - Not enough preceding candles: " + precedingCandles.size() + "/" + (LOOKBACK_CANDLES - 1));
            return false;
        }
        // Reverse to chronological order (oldest first)
        Collections.reverse(precedingCandles);

        List<CandleData> allCandles = new ArrayList<>(precedingCandles);
        allCandles.add(hammerCandle);
        int stepsQualified = 0;
        int totalSteps = allCandles.size() - 1;
        for (int i = 1; i < allCandles.size(); i++) {
            CandleData prev = allCandles.get(i - 1);
            CandleData curr = allCandles.get(i);
            double prevClose = prev.getClose();
            double currClose = curr.getClose();
            double bodySize = Math.abs(curr.getClose() - curr.getOpen());
            double bodyPercent = (bodySize / currClose) * 100;
            double closeChangePercent = ((currClose - prevClose) / prevClose) * 100;
            boolean isDownStep = false;
            String reason = "";
            if (currClose < prevClose) {
                isDownStep = true;
                reason = "price down " + String.format("%.2f", Math.abs(closeChangePercent)) + "%";
            } else if (Math.abs(closeChangePercent) <= MAX_CLOSE_CHANGE_PERCENT) {
                isDownStep = true;
                reason = "close change " + String.format("%.2f", closeChangePercent) + "% (within ±2%)";
            } else if (bodyPercent <= MAX_BODY_PERCENT) {
                isDownStep = true;
                reason = "small body " + String.format("%.2f", bodyPercent) + "% (≤1%)";
            }
            if (isDownStep) {
                stepsQualified++;
                System.out.println("      - Step " + i + " (" + formatTime(prev) + " → " + formatTime(curr) + "): QUALIFIED (" + reason + ")");
            } else {
                System.out.println("      - Step " + i + " (" + formatTime(prev) + " → " + formatTime(curr) + "): NOT qualified (close change " +
                        String.format("%.2f", closeChangePercent) + "%, body " + String.format("%.2f", bodyPercent) + "%)");
            }
        }
        boolean isDowntrend = stepsQualified >= MIN_DOWNTREND_STEPS;
        System.out.println("      - Steps qualified: " + stepsQualified + "/" + totalSteps + " (need ≥ " + MIN_DOWNTREND_STEPS + ")");
        return isDowntrend;
    }

    /**
     * FIXED: Calculates downtrend magnitude using the HIGHEST HIGH in the lookback window
     * (including the hammer candle) and the hammer's LOW as the bottom.
     * This matches the example: highest high from 11:40 to 12:05, lowest low = hammer low.
     */
    private double calculateDowntrendPercentFixed(String instrument, RealTimeCandleBuilder candleBuilder, CandleData hammerCandle) {
        // Get up to LOOKBACK_CANDLES-1 preceding candles (the 4 candles before the hammer)
        List<CandleData> precedingCandles = new ArrayList<>();
        int index = 1;
        while (precedingCandles.size() < LOOKBACK_CANDLES - 1 && index <= 10) {
            CandleData candle = getCandleAtIndex(instrument, candleBuilder, index);
            if (candle != null && candle.getTimestamp().before(hammerCandle.getTimestamp())) {
                precedingCandles.add(candle);
            }
            index++;
        }
        // Highest high in the entire lookback (preceding candles + hammer)
        double highestHigh = hammerCandle.getHigh();
        for (CandleData candle : precedingCandles) {
            highestHigh = Math.max(highestHigh, candle.getHigh());
        }
        // Only the hammer's low is used as the bottom (not the min of preceding lows)
        double hammerLow = hammerCandle.getLow();
        if (highestHigh <= 0) return 0;
        return ((highestHigh - hammerLow) / highestHigh) * 100;
    }

    private double getDowntrendStartPriceRelaxed(String instrument, RealTimeCandleBuilder candleBuilder, CandleData hammerCandle) {
        List<CandleData> precedingCandles = new ArrayList<>();
        int index = 1;
        while (precedingCandles.size() < LOOKBACK_CANDLES - 1 && index <= 10) {
            CandleData candle = getCandleAtIndex(instrument, candleBuilder, index);
            if (candle != null && candle.getTimestamp().before(hammerCandle.getTimestamp())) {
                precedingCandles.add(candle);
            }
            index++;
        }
        double highestHigh = 0;
        for (CandleData candle : precedingCandles) highestHigh = Math.max(highestHigh, candle.getHigh());
        return highestHigh;
    }

    private boolean isHammerAtBottomRelaxed(String instrument, RealTimeCandleBuilder candleBuilder, CandleData hammerCandle) {
        List<CandleData> precedingCandles = new ArrayList<>();
        int index = 1;
        while (precedingCandles.size() < LOOKBACK_CANDLES - 1 && index <= 10) {
            CandleData candle = getCandleAtIndex(instrument, candleBuilder, index);
            if (candle != null && candle.getTimestamp().before(hammerCandle.getTimestamp())) {
                precedingCandles.add(candle);
            }
            index++;
        }
        double hammerLow = hammerCandle.getLow();
        for (CandleData candle : precedingCandles) {
            if (candle.getLow() < hammerLow) {
                System.out.println("      - Found lower low: " + String.format("%.2f", candle.getLow()) +
                        " < hammer low " + String.format("%.2f", hammerLow));
                return false;
            }
        }
        System.out.println("      - Hammer low is the lowest among " + (precedingCandles.size() + 1) + " candles");
        return true;
    }

    private String formatTime(CandleData candle) {
        if (candle == null || candle.getTimestamp() == null) return "?";
        return new java.text.SimpleDateFormat("HH:mm").format(candle.getTimestamp());
    }

    private boolean isValidHammerPattern(CandleData candle) {
        double bodySize = Math.abs(candle.getClose() - candle.getOpen());
        double lowerWick = Math.min(candle.getClose(), candle.getOpen()) - candle.getLow();
        double upperWick = candle.getHigh() - Math.max(candle.getClose(), candle.getOpen());
        double totalRange = candle.getHigh() - candle.getLow();
        if (totalRange <= 0.01) return false;
        double bodyPercent = bodySize / totalRange * 100;
        boolean smallBody = bodyPercent <= 33.0;
        boolean longLowerWick = lowerWick >= (2 * bodySize);
        double bodyTop = Math.max(candle.getClose(), candle.getOpen());
        boolean bodyInTopThird = bodyTop > candle.getLow() + (totalRange * 0.67);
        boolean smallUpperWick = upperWick <= bodySize;
        System.out.println("      🔨 Hammer Validation:");
        System.out.println("         - Body %: " + String.format("%.1f", bodyPercent) + "%");
        System.out.println("         - Lower wick/body ratio: " + String.format("%.1f", lowerWick/bodySize));
        System.out.println("         - Small body: " + smallBody);
        System.out.println("         - Long lower wick (2×): " + longLowerWick);
        System.out.println("         - Body in top 1/3: " + bodyInTopThird);
        return smallBody && longLowerWick && bodyInTopThird;
    }

    private CandleData getCandleAtIndex(String instrument, RealTimeCandleBuilder candleBuilder, int indexFromEnd) {
        CandleData candle = CandleHistoryManager.getInstance().getCandleAtIndex(instrument, indexFromEnd);
        if (candle != null) return candle;
        if (indexFromEnd == 1) return candleBuilder.getLastCompletedCandle(instrument);
        if (indexFromEnd == 2) return candleBuilder.getPreviousCompletedCandle(instrument);
        return null;
    }

    @Override
    public void executeBuySignal(String instrument, Map<String, Object> signalDetails, TradingStrategyEngine context) {
        double entryPrice = (double) signalDetails.get("entryPrice");
        double stopLoss = (double) signalDetails.get("stopLoss");
        double target = (double) signalDetails.get("target");
        System.out.println("\n" + "🔨".repeat(20));
        System.out.println("🔨 [Case 2] EXECUTING HAMMER REVERSAL BUY SIGNAL");
        System.out.println("   Strategy: Hammer Reversal after Downtrend");
        System.out.println("   Entry: " + entryPrice + " (Break of hammer high)");
        System.out.println("   Stop Loss: " + String.format("%.2f", stopLoss) + " (Below hammer low)");
        System.out.println("   Target: " + String.format("%.2f", target) + " (2 × SL distance)");
        System.out.println("   Risk-Reward: 1:2");
        System.out.println("🔨".repeat(20));
        context.executeHammerBuySignal(instrument, entryPrice, stopLoss, target);
    }

    @Override
    public boolean shouldSkipInstrument(String instrument, TradingStrategyEngine context) {
        return context.shouldSkipInstrument(instrument);
    }
}