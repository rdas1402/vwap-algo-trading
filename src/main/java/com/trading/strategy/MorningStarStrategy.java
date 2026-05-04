// MorningStarStrategy.java
package com.trading.strategy;

import com.trading.config.AppConfig;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;

import java.util.*;

/**
 * Case 6: Morning Star Pattern Strategy (Bottom Reversal)
 * 
 * Pattern: 3-candle reversal at bottom of downtrend
 *   Candle 1: Long bearish (close < open, body > 0.5% of price)
 *   Candle 2: Small body (doji / spinning top) – can be bullish or bearish
 *   Candle 3: Long bullish (close > open, closes at least 50% into Candle 1's body)
 * 
 * Entry: Break of Candle 3's high (or immediate if price already above)
 * Stop Loss: Below low of Candle 3 (or below Candle 2 low, whichever is lower)
 * Target: Entry + 2 × (Entry - Stop Loss)  [1:2 Risk-Reward]
 */
public class MorningStarStrategy implements TradingStrategy {

    private static final int LOOKBACK_CANDLES = 5;          // Need at least 3 for pattern + context
    private static final double MIN_DOWNTREND_PERCENT = 10;   // Minimum 10% decline for downtrend
    private static final double MIN_BODY_RATIO = 1.5;        // Candle 1 body >= 1.5x Candle 3 body? Actually we use absolute size
    private static final double MIN_BODY_PERCENT = 0.5;      // Minimum body size as % of price (e.g., 0.5%)
    private static final double STAR_MAX_BODY_PERCENT = 0.3; // Star candle body max 0.3% of price
    private static final double THIRD_CANDLE_ENGULF_RATIO = 0.5; // Candle 3 closes at least 50% into Candle 1's body

    @Override
    public int getPriority() {
        return 6; // After Hammer (2), Engulfing (5), etc.
    }

    @Override
    public String getStrategyName() {
        return "Case 6: Morning Star Pattern Strategy (Bottom Reversal)";
    }

    @Override
    public boolean canExecute(TradingStrategyEngine context) {
        return context.isWithinBuyingHours();
    }

    @Override
    public Map<String, String> findInstruments(TradingStrategyEngine context) throws Exception, KiteException {
        Map<String, String> options = new HashMap<>();
        double targetPremium = AppConfig.getTargetPremium(); // Use same config as others
        double tolerance = AppConfig.getPremiumTolerance();
        double niftySpot = context.getNiftySpotPrice();
        System.out.println("📊 [Case 6] Nifty Spot Price: " + niftySpot);
        System.out.println("   Target Premium: " + targetPremium + " ±" + tolerance);
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
            System.out.println("\n⭐ [Case 6] Analyzing Morning Star Pattern for: " + instrument);

            RealTimeCandleBuilder candleBuilder = context.getRealTimeCandleBuilder();
            int completedCount = candleBuilder.getCompletedCandleCount(instrument);
            if (completedCount < LOOKBACK_CANDLES) {
                System.out.println("⏳ [Case 6] Need " + LOOKBACK_CANDLES + " candles (have " + completedCount + ")");
                return result;
            }

            // Get last 3 completed candles (most recent first? careful: index 1 = last completed)
            CandleData candle3 = candleBuilder.getLastCompletedCandle(instrument);      // 3rd candle (bullish)
            CandleData candle2 = candleBuilder.getPreviousCompletedCandle(instrument); // 2nd candle (star)
            CandleData candle1 = getCandleAtIndex(instrument, candleBuilder, 3);       // 1st candle (bearish)

            if (candle1 == null || candle2 == null || candle3 == null) {
                System.out.println("❌ [Case 6] Missing candle data");
                return result;
            }

            // Verify chronological order: candle1 (oldest), candle2, candle3 (newest)
            if (!(candle1.getTimestamp().before(candle2.getTimestamp()) &&
                  candle2.getTimestamp().before(candle3.getTimestamp()))) {
                System.out.println("❌ [Case 6] Candles not in correct chronological order");
                return result;
            }

            // ========== CONDITION 1: Downtrend before pattern ==========
            boolean downtrend = checkDowntrendBeforePattern(instrument, candle1);
            double downtrendPercent = calculateDowntrendPercent(instrument, candle1);

            System.out.println("\n   📉 Downtrend Analysis:");
            System.out.println("      - Downtrend detected: " + downtrend);
            System.out.println("      - Downtrend magnitude: " + String.format("%.2f", downtrendPercent) + "%");
            System.out.println("      - Required: > " + MIN_DOWNTREND_PERCENT + "%");

            if (!downtrend || downtrendPercent < MIN_DOWNTREND_PERCENT) {
                System.out.println("   ❌ No significant downtrend before pattern");
                return result;
            }

            // ========== CONDITION 2: Morning Star Pattern ==========
            boolean isMorningStar = isMorningStarPattern(candle1, candle2, candle3);

            System.out.println("\n   ⭐ Morning Star Pattern Analysis:");
            System.out.println("      Candle 1 (Bearish): O=" + String.format("%.2f", candle1.getOpen()) +
                               " C=" + String.format("%.2f", candle1.getClose()) +
                               " Body=" + String.format("%.2f", Math.abs(candle1.getClose() - candle1.getOpen())));
            System.out.println("      Candle 2 (Star):    O=" + String.format("%.2f", candle2.getOpen()) +
                               " C=" + String.format("%.2f", candle2.getClose()) +
                               " Body=" + String.format("%.2f", Math.abs(candle2.getClose() - candle2.getOpen())));
            System.out.println("      Candle 3 (Bullish): O=" + String.format("%.2f", candle3.getOpen()) +
                               " C=" + String.format("%.2f", candle3.getClose()) +
                               " Body=" + String.format("%.2f", Math.abs(candle3.getClose() - candle3.getOpen())));
            System.out.println("      - Is Morning Star: " + isMorningStar);

            if (!isMorningStar) {
                System.out.println("   ❌ Not a valid Morning Star pattern");
                return result;
            }

            // ========== CONDITION 3: Pattern at bottom (optional but good) ==========
            boolean atBottom = isPatternAtBottom(instrument, candleBuilder, candle3);
            System.out.println("\n   📍 Position Analysis:");
            System.out.println("      - Pattern at recent bottom: " + atBottom);

            // ========== Setup Trade ==========
            double entryPrice = candle3.getHigh(); // Break of third candle's high
            double stopLoss = Math.min(candle3.getLow(), candle2.getLow()); // Below lowest of star or third candle
            double riskAmount = entryPrice - stopLoss;
            double target = entryPrice + (riskAmount * 2); // 1:2 Risk-Reward

            // If price already above entry, use current price as entry
            double currentPrice = getCurrentPrice(instrument, context);
            if (currentPrice > entryPrice) {
                entryPrice = currentPrice;
                riskAmount = entryPrice - stopLoss;
                target = entryPrice + (riskAmount * 2);
            }

            System.out.println("\n" + "⭐".repeat(20));
            System.out.println("⭐ [Case 6] MORNING STAR PATTERN DETECTED!");
            System.out.println("   ✅ Downtrend confirmed: " + String.format("%.2f", downtrendPercent) + "% decline");
            System.out.println("   ✅ Valid Morning Star pattern");
            if (atBottom) System.out.println("   ✅ Pattern at recent bottom");
            System.out.println("\n   📊 Pattern Details:");
            System.out.println("      - Bearish Candle High: " + String.format("%.2f", candle1.getHigh()));
            System.out.println("      - Star Candle High: " + String.format("%.2f", candle2.getHigh()));
            System.out.println("      - Bullish Candle High: " + String.format("%.2f", candle3.getHigh()));
            System.out.println("\n   🎯 Trade Setup:");
            System.out.println("      - 👉 Entry: " + String.format("%.2f", entryPrice) + " (Break of 3rd candle high)");
            System.out.println("      - 🛑 Stop Loss: " + String.format("%.2f", stopLoss) + " (Below pattern low)");
            System.out.println("      - 📊 Risk Amount: " + String.format("%.2f", riskAmount));
            System.out.println("      - 🎯 Target: " + String.format("%.2f", target) + " (1:2 Risk-Reward)");
            System.out.println("⭐".repeat(20));

            // Start breakout monitor (5 minutes, 3-second checks) – same as hammer
            context.startBreakoutMonitor(instrument, entryPrice, stopLoss, target, "morning_star", 3);

            result.put("message", "Morning Star detected – monitoring breakout (5 min, 3s checks)");
            result.put("entryPrice", entryPrice);
            result.put("stopLoss", stopLoss);
            result.put("target", target);

        } catch (Exception e) {
            System.err.println("❌ [Case 6] Error: " + e.getMessage());
            e.printStackTrace();
        }
        return result;
    }

    @Override
    public void executeBuySignal(String instrument, Map<String, Object> signalDetails, TradingStrategyEngine context) {
        double entryPrice = (double) signalDetails.get("entryPrice");
        double stopLoss = (double) signalDetails.get("stopLoss");
        double target = (double) signalDetails.get("target");

        System.out.println("\n" + "⭐".repeat(20));
        System.out.println("⭐ [Case 6] EXECUTING MORNING STAR BUY SIGNAL");
        System.out.println("   Strategy: Morning Star Reversal after Downtrend");
        System.out.println("   Entry: " + String.format("%.2f", entryPrice) + " (Break of 3rd candle high)");
        System.out.println("   Stop Loss: " + String.format("%.2f", stopLoss) + " (Below pattern low)");
        System.out.println("   Target: " + String.format("%.2f", target) + " (1:2 R:R)");
        System.out.println("⭐".repeat(20));

        context.executeMorningStarBuySignal(instrument, entryPrice, stopLoss, target);
    }

    @Override
    public boolean shouldSkipInstrument(String instrument, TradingStrategyEngine context) {
        return context.shouldSkipInstrument(instrument);
    }

    // ========== Helper Methods ==========

    private boolean checkDowntrendBeforePattern(String instrument, CandleData patternStartCandle) {
        List<CandleData> history = CandleHistoryManager.getInstance().getHistory(instrument);
        if (history == null || history.size() < 5) return false;

        // Look at 5 candles before the pattern start (excluding pattern candles)
        int startIndex = -1;
        for (int i = 0; i < history.size(); i++) {
            if (history.get(i).getTimestamp().equals(patternStartCandle.getTimestamp())) {
                startIndex = i;
                break;
            }
        }
        if (startIndex < 3) return false;

        // Check if price is in downtrend: each close lower than previous (or at least 3 of 4 steps)
        int declines = 0;
        for (int i = startIndex - 1; i > startIndex - 4 && i >= 0; i--) {
            if (history.get(i).getClose() < history.get(i + 1).getClose()) {
                declines++;
            }
        }
        return declines >= 2;
    }

    private double calculateDowntrendPercent(String instrument, CandleData patternStartCandle) {
        List<CandleData> history = CandleHistoryManager.getInstance().getHistory(instrument);
        if (history == null || history.size() < 3) return 0;

        int startIndex = -1;
        for (int i = 0; i < history.size(); i++) {
            if (history.get(i).getTimestamp().equals(patternStartCandle.getTimestamp())) {
                startIndex = i;
                break;
            }
        }
        if (startIndex < 2) return 0;

        double highestHigh = patternStartCandle.getHigh();
        double lowestLow = patternStartCandle.getLow();
        for (int i = Math.max(0, startIndex - 5); i <= startIndex; i++) {
            highestHigh = Math.max(highestHigh, history.get(i).getHigh());
            lowestLow = Math.min(lowestLow, history.get(i).getLow());
        }
        if (highestHigh <= 0) return 0;
        return ((highestHigh - lowestLow) / highestHigh) * 100;
    }

    private boolean isMorningStarPattern(CandleData candle1, CandleData candle2, CandleData candle3) {
        // Candle 1: Bearish
        boolean candle1Bearish = candle1.getClose() < candle1.getOpen();
        double candle1Body = Math.abs(candle1.getClose() - candle1.getOpen());
        double candle1BodyPercent = (candle1Body / candle1.getClose()) * 100;
        boolean candle1Significant = candle1BodyPercent >= MIN_BODY_PERCENT;

        // Candle 2: Small body (star)
        double candle2Body = Math.abs(candle2.getClose() - candle2.getOpen());
        double candle2BodyPercent = (candle2Body / candle2.getClose()) * 100;
        boolean starSmall = candle2BodyPercent <= STAR_MAX_BODY_PERCENT;

        // Optional: star should gap down? Not mandatory, but we can check lower high
        boolean starLowerHigh = candle2.getHigh() < candle1.getHigh();

        // Candle 3: Bullish
        boolean candle3Bullish = candle3.getClose() > candle3.getOpen();
        double candle3Body = Math.abs(candle3.getClose() - candle3.getOpen());
        double candle3BodyPercent = (candle3Body / candle3.getClose()) * 100;
        boolean candle3Significant = candle3BodyPercent >= MIN_BODY_PERCENT;

        // Candle 3 closes at least 50% into Candle 1's body
        double candle1Range = Math.abs(candle1.getClose() - candle1.getOpen());
        double candle1Low = Math.min(candle1.getClose(), candle1.getOpen());
        double candle1High = Math.max(candle1.getClose(), candle1.getOpen());
        double penetration = (candle3.getClose() - candle1Low) / candle1Range;
        boolean engulfsHalf = penetration >= THIRD_CANDLE_ENGULF_RATIO;

        System.out.println("      ⭐ Pattern Checks:");
        System.out.println("         - Candle1 Bearish & Significant: " + (candle1Bearish && candle1Significant));
        System.out.println("         - Candle2 Small body (star): " + starSmall);
        System.out.println("         - Candle3 Bullish & Significant: " + (candle3Bullish && candle3Significant));
        System.out.println("         - Candle3 closes > 50% into Candle1 body: " + engulfsHalf);

        return (candle1Bearish && candle1Significant) &&
               starSmall &&
               (candle3Bullish && candle3Significant) &&
               engulfsHalf;
    }

    private boolean isPatternAtBottom(String instrument, RealTimeCandleBuilder candleBuilder, CandleData patternCandle) {
        // Check if pattern candle's low is the lowest among last 10 candles
        double patternLow = patternCandle.getLow();
        for (int i = 1; i <= 10; i++) {
            CandleData candle = getCandleAtIndex(instrument, candleBuilder, i);
            if (candle != null && candle.getLow() < patternLow) {
                return false;
            }
        }
        return true;
    }

    private double getCurrentPrice(String instrument, TradingStrategyEngine context) {
        try {
            String[] instruments = {instrument};
            Map<String, com.zerodhatech.models.Quote> quotes = context.getKiteConnect().getQuote(instruments);
            com.zerodhatech.models.Quote quote = quotes.get(instrument);
            return quote != null ? quote.lastPrice : 0;
        } catch (Exception | KiteException e) {
            return 0;
        }
    }

    private CandleData getCandleAtIndex(String instrument, RealTimeCandleBuilder candleBuilder, int indexFromEnd) {
        CandleData candle = CandleHistoryManager.getInstance().getCandleAtIndex(instrument, indexFromEnd);
        if (candle != null) return candle;
        if (indexFromEnd == 1) return candleBuilder.getLastCompletedCandle(instrument);
        if (indexFromEnd == 2) return candleBuilder.getPreviousCompletedCandle(instrument);
        if (indexFromEnd == 3) return candleBuilder.getCompletedCandleAtIndex(instrument, 3);
        return null;
    }
}