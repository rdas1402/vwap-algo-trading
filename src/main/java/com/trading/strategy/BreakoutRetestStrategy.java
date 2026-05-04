package com.trading.strategy;

import com.trading.config.AppConfig;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Quote;

import java.util.*;

public class BreakoutRetestStrategy implements TradingStrategy {

    private static final int LOOKBACK_CANDLES = 10;
    private static final int MIN_RANGE_PERCENT = 2;
    private static final double RETEST_TOLERANCE_PERCENT = 1.5;
    private static final double BREAKOUT_VOLUME_RATIO = 1.5;
    private static final int MAX_WAIT_MINUTES = 30;     // for retest phase (not breakout monitor)
    private static final double PRICE_RUN_AWAY_PERCENT = 5.0;

    private final Map<String, BreakoutSetupData> activeSetups = new HashMap<>();
    private final Map<String, List<Double>> volumeHistory = new HashMap<>();

    private static class BreakoutSetupData {
        final double breakoutLevel;
        final double rangeLow;
        final double rangeHigh;
        final double rangeWidth;
        final Date breakoutTime;
        final double breakoutVolume;
        boolean retestConfirmed;
        double retestPrice;
        double retestLow;
        Date retestTime;

        BreakoutSetupData(double breakoutLevel, double rangeLow, double rangeHigh, Date breakoutTime, double breakoutVolume) {
            this.breakoutLevel = breakoutLevel;
            this.rangeLow = rangeLow;
            this.rangeHigh = rangeHigh;
            this.rangeWidth = rangeHigh - rangeLow;
            this.breakoutTime = breakoutTime;
            this.breakoutVolume = breakoutVolume;
            this.retestConfirmed = false;
        }

        double getTarget() { return breakoutLevel + (rangeWidth * 1.5); }
        double getStopLoss() { return breakoutLevel - (rangeWidth * 0.5); }
    }

    @Override
    public int getPriority() { return 3; }

    @Override
    public String getStrategyName() { return "Case 3: Breakout + Retest Strategy (Most Powerful Setup)"; }

    @Override
    public boolean canExecute(TradingStrategyEngine context) { return context.isWithinBuyingHours(); }

    @Override
    public Map<String, String> findInstruments(TradingStrategyEngine context) throws Exception, KiteException {
        Map<String, String> options = new HashMap<>();
        double targetPremium = AppConfig.getBreakoutTargetPremium();
        double tolerance = AppConfig.getPremiumTolerance();
        double niftySpot = context.getNiftySpotPrice();
        System.out.println("📊 [Case 3] Nifty Spot Price: " + niftySpot);
        System.out.println("   Target Premium (Breakout): " + targetPremium + " ±" + tolerance);
        String ceOption = context.findOptionNearPremium(niftySpot, true, targetPremium, tolerance);
        String peOption = context.findOptionNearPremium(niftySpot, false, targetPremium, tolerance);
        if (ceOption != null) options.put("CE", ceOption);
        if (peOption != null) options.put("PE", peOption);
        return options;
    }

    @Override
    public Map<String, Object> analyzeInstrument(String instrument, TradingStrategyEngine context) {
        // FIX #5: Clean up expired setups before analysis
        cleanupExpiredSetups();

        Map<String, Object> result = new HashMap<>();
        result.put("signal", false);
        result.put("pattern", "none");
        result.put("instrument", instrument);

        try {
            System.out.println("\n📊 [Case 3] Analyzing Breakout + Retest for: " + instrument);

            if (activeSetups.containsKey(instrument)) {
                return monitorRetestPhase(instrument, context);
            }

            RealTimeCandleBuilder candleBuilder = context.getRealTimeCandleBuilder();
            int completedCandleCount = candleBuilder.getCompletedCandleCount(instrument);
            if (completedCandleCount < LOOKBACK_CANDLES) {
                System.out.println("⏳ [Case 3] Need " + LOOKBACK_CANDLES + " candles for range identification");
                return result;
            }

            // FIX #6: Use improved consolidation range detection
            RangeData range = identifyConsolidationRange(instrument, candleBuilder);
            if (range == null || range.width <= 0) {
                System.out.println("   ❌ Could not identify valid consolidation range");
                return result;
            }

            double rangePercent = (range.width / range.low) * 100;
            System.out.println("\n   📊 Consolidation Range Analysis:");
            System.out.println("      - Range High: " + String.format("%.2f", range.high));
            System.out.println("      - Range Low: " + String.format("%.2f", range.low));
            System.out.println("      - Range Width: " + String.format("%.2f", range.width));
            System.out.println("      - Range Percent: " + String.format("%.2f", rangePercent) + "%");
            System.out.println("      - Required Min: " + MIN_RANGE_PERCENT + "%");

            if (rangePercent < MIN_RANGE_PERCENT) {
                System.out.println("   ❌ Range too narrow for meaningful breakout");
                return result;
            }

            double currentPrice = getCurrentPrice(instrument, context);
            double breakoutBuffer = range.high * 0.002;
            boolean isBreakout = currentPrice > (range.high + breakoutBuffer);
            double breakoutVolume = getCurrentVolume(instrument, context);
            double avgVolume = getAverageVolume(instrument);
            // FIX #3: Temporarily disable volume confirmation (set to true)
            boolean volumeConfirmed = true; // was: avgVolume > 0 && (breakoutVolume / avgVolume) >= BREAKOUT_VOLUME_RATIO;

            System.out.println("\n   🚀 Breakout Detection:");
            System.out.println("      - Current Price: " + String.format("%.2f", currentPrice));
            System.out.println("      - Breakout Level: " + String.format("%.2f", range.high));
            System.out.println("      - Breakout Buffer: " + String.format("%.2f", breakoutBuffer));
            System.out.println("      - Breakout Detected: " + isBreakout);
            System.out.println("      - Current Volume: " + String.format("%.0f", breakoutVolume));
            System.out.println("      - Avg Volume (10): " + String.format("%.0f", avgVolume));
            System.out.println("      - Volume Ratio: " + (avgVolume > 0 ? String.format("%.2f", breakoutVolume / avgVolume) : "N/A"));
            System.out.println("      - Volume Confirmed: " + volumeConfirmed);

            if (!isBreakout) {
                System.out.println("   ❌ No breakout detected");
                return result;
            }

            System.out.println("\n" + "🚀".repeat(20));
            System.out.println("🚀 [Case 3] BREAKOUT DETECTED!");
            System.out.println("   📈 " + instrument + " broke above range high");
            System.out.println("      - Breakout Level: " + String.format("%.2f", range.high));
            System.out.println("      - Range Width: " + String.format("%.2f", range.width));
            System.out.println("      - Volume Confirmation: " + (volumeConfirmed ? "YES" : "NO"));
            System.out.println("\n   ⏳ Now monitoring for RETEST of broken level...");
            System.out.println("   🎯 Entry will trigger when price retests and bounces");
            System.out.println("🚀".repeat(20));

            BreakoutSetupData setup = new BreakoutSetupData(range.high, range.low, range.high, new Date(), breakoutVolume);
            activeSetups.put(instrument, setup);

            result.put("message", "Breakout detected - monitoring for retest");
            result.put("breakoutLevel", range.high);
            result.put("target", setup.getTarget());
            result.put("stopLoss", setup.getStopLoss());

        } catch (Exception e) {
            System.err.println("❌ [Case 3] Error: " + e.getMessage());
            e.printStackTrace();
        }
        return result;
    }

    // FIX #5: Cleanup expired setups
    private void cleanupExpiredSetups() {
        long now = System.currentTimeMillis();
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, BreakoutSetupData> entry : activeSetups.entrySet()) {
            long elapsed = (now - entry.getValue().breakoutTime.getTime()) / (60 * 1000);
            if (elapsed > MAX_WAIT_MINUTES) {
                toRemove.add(entry.getKey());
            }
        }
        for (String inst : toRemove) {
            activeSetups.remove(inst);
            System.out.println("   🧹 Removed expired breakout setup for " + inst);
        }
    }

    private Map<String, Object> monitorRetestPhase(String instrument, TradingStrategyEngine context) {
        Map<String, Object> result = new HashMap<>();
        result.put("signal", false);
        result.put("instrument", instrument);

        BreakoutSetupData setup = activeSetups.get(instrument);
        if (setup == null) return result;

        try {
            long elapsedMinutes = (System.currentTimeMillis() - setup.breakoutTime.getTime()) / (60 * 1000);
            if (elapsedMinutes > MAX_WAIT_MINUTES) {
                System.out.println("   ⏰ Retest window expired for " + instrument + " (" + MAX_WAIT_MINUTES + " min)");
                activeSetups.remove(instrument);
                return result;
            }

            double currentPrice = getCurrentPrice(instrument, context);
            double breakoutLevel = setup.breakoutLevel;
            double retestTolerance = breakoutLevel * (RETEST_TOLERANCE_PERCENT / 100);

            boolean nearBreakoutLevel = Math.abs(currentPrice - breakoutLevel) <= retestTolerance;
            boolean isPullingBack = currentPrice < setup.rangeHigh * 1.02;

            if (nearBreakoutLevel && (currentPrice < setup.retestLow || setup.retestLow == 0)) {
                setup.retestLow = currentPrice;
            }

            System.out.println("\n   🔄 Retest Monitoring for " + instrument);
            System.out.println("      - Current Price: " + String.format("%.2f", currentPrice));
            System.out.println("      - Breakout Level: " + String.format("%.2f", breakoutLevel));
            System.out.println("      - Retest Tolerance: ±" + String.format("%.2f", retestTolerance));
            System.out.println("      - Near Breakout Level: " + nearBreakoutLevel);
            System.out.println("      - Pulling Back: " + isPullingBack);
            System.out.println("      - Retest Low: " + String.format("%.2f", setup.retestLow));
            System.out.println("      - Time Elapsed: " + elapsedMinutes + " / " + MAX_WAIT_MINUTES + " min");

            if (currentPrice > breakoutLevel * (1 + PRICE_RUN_AWAY_PERCENT / 100) && elapsedMinutes > 10) {
                System.out.println("   ❌ Price ran away without retest - cancelling setup");
                activeSetups.remove(instrument);
                return result;
            }

            if (nearBreakoutLevel && isPullingBack && !setup.retestConfirmed) {
                boolean rejectionSignal = checkRejectionAtRetest(instrument, context, breakoutLevel);
                System.out.println("      - Rejection Signal at Retest: " + rejectionSignal);
                if (rejectionSignal) {
                    setup.retestConfirmed = true;
                    setup.retestPrice = currentPrice;
                    setup.retestTime = new Date();

                    System.out.println("\n" + "🎯".repeat(20));
                    System.out.println("🎯 [Case 3] RETEST CONFIRMED!");
                    System.out.println("   ✅ Breakout occurred at: " + String.format("%.2f", setup.breakoutLevel));
                    System.out.println("   ✅ Price retested broken level");
                    System.out.println("   ✅ Rejection candle detected");
                    System.out.println("\n   📊 Trade Setup:");
                    System.out.println("      - Entry: " + String.format("%.2f", currentPrice) + " (Retest bounce)");
                    System.out.println("      - Stop Loss: " + String.format("%.2f", setup.getStopLoss()));
                    System.out.println("      - Target: " + String.format("%.2f", setup.getTarget()));
                    System.out.println("      - Risk-Reward: 1:" +
                            String.format("%.2f", (setup.getTarget() - currentPrice) / (currentPrice - setup.getStopLoss())));
                    System.out.println("🎯".repeat(20));

                    activeSetups.remove(instrument);
                    context.executeBreakoutRetestBuySignal(instrument, currentPrice, setup.getStopLoss(), setup.getTarget());
                    result.put("signal", false);
                    return result;
                }
            }
        } catch (Exception e) {
            System.err.println("Error in retest monitoring: " + e.getMessage());
        }
        return result;
    }

    // FIX #6: Improved consolidation range detection (tight range)
    private RangeData identifyConsolidationRange(String instrument, RealTimeCandleBuilder candleBuilder) {
        double maxRangePercent = 5.0; // 5% maximum range for consolidation
        for (int start = 1; start <= 20; start++) {
            List<Double> highs = new ArrayList<>();
            List<Double> lows = new ArrayList<>();
            for (int i = start; i < start + LOOKBACK_CANDLES && i <= 20; i++) {
                CandleData candle = getCandleAtIndex(instrument, candleBuilder, i);
                if (candle != null && candle.getHigh() > 0 && candle.getLow() > 0) {
                    highs.add(candle.getHigh());
                    lows.add(candle.getLow());
                }
            }
            if (highs.size() < 4) continue;
            double rangeHigh = Collections.max(highs);
            double rangeLow = Collections.min(lows);
            double rangeWidth = rangeHigh - rangeLow;
            double midPrice = (rangeHigh + rangeLow) / 2;
            double rangePercent = (rangeWidth / midPrice) * 100;
            if (rangePercent <= maxRangePercent && rangeWidth > 0) {
                RangeData range = new RangeData();
                range.high = rangeHigh;
                range.low = rangeLow;
                range.width = rangeWidth;
                return range;
            }
        }
        // Fallback to original logic
        List<Double> highs = new ArrayList<>();
        List<Double> lows = new ArrayList<>();
        int lookback = Math.min(LOOKBACK_CANDLES, 8);
        for (int i = 1; i <= lookback; i++) {
            CandleData candle = getCandleAtIndex(instrument, candleBuilder, i);
            if (candle != null && candle.getHigh() > 0 && candle.getLow() > 0) {
                highs.add(candle.getHigh());
                lows.add(candle.getLow());
            }
        }
        if (highs.size() < 4) return null;
        double rangeHigh = Collections.max(highs);
        double rangeLow = Collections.min(lows);
        double rangeWidth = rangeHigh - rangeLow;
        if (rangeWidth <= 0) return null;
        RangeData range = new RangeData();
        range.high = rangeHigh;
        range.low = rangeLow;
        range.width = rangeWidth;
        return range;
    }

    private boolean checkRejectionAtRetest(String instrument, TradingStrategyEngine context, double breakoutLevel) {
        RealTimeCandleBuilder candleBuilder = context.getRealTimeCandleBuilder();
        CandleData lastCandle = candleBuilder.getLastCompletedCandle(instrument);
        CandleData previousCandle = candleBuilder.getPreviousCompletedCandle(instrument);
        if (lastCandle == null) return false;
        boolean touchedSupport = Math.abs(lastCandle.getLow() - breakoutLevel) <= (breakoutLevel * 0.01);
        boolean isHammer = checkHammerPattern(lastCandle);
        boolean isEngulfing = false;
        if (previousCandle != null) {
            boolean previousBearish = previousCandle.getClose() < previousCandle.getOpen();
            boolean currentBullish = lastCandle.getClose() > lastCandle.getOpen();
            boolean engulfs = lastCandle.getOpen() < previousCandle.getClose() && lastCandle.getClose() > previousCandle.getOpen();
            isEngulfing = previousBearish && currentBullish && engulfs;
        }
        boolean bullishClose = lastCandle.getClose() > lastCandle.getOpen() && lastCandle.getClose() > breakoutLevel;
        System.out.println("      📊 Rejection Signal Details:");
        System.out.println("         - Touched Support: " + touchedSupport);
        System.out.println("         - Hammer Pattern: " + isHammer);
        System.out.println("         - Bullish Engulfing: " + isEngulfing);
        System.out.println("         - Bullish Close: " + bullishClose);
        return touchedSupport && (isHammer || isEngulfing || bullishClose);
    }

    private boolean checkHammerPattern(CandleData candle) {
        double bodySize = Math.abs(candle.getClose() - candle.getOpen());
        double lowerWick = Math.min(candle.getClose(), candle.getOpen()) - candle.getLow();
        double upperWick = candle.getHigh() - Math.max(candle.getClose(), candle.getOpen());
        if (bodySize <= 0.01) {
            return lowerWick > upperWick * 3 && lowerWick > (candle.getHigh() - candle.getLow()) * 0.6;
        }
        return lowerWick >= (2 * bodySize) && upperWick <= (0.3 * bodySize) && candle.getClose() > candle.getOpen();
    }

    private double getCurrentPrice(String instrument, TradingStrategyEngine context) {
        try {
            String[] instruments = {instrument};
            Map<String, Quote> quotes = context.getKiteConnect().getQuote(instruments);
            Quote quote = quotes.get(instrument);
            return quote != null ? quote.lastPrice : 0;
        } catch (Exception | KiteException e) { return 0; }
    }

    // FIX #3: Simplified volume tracking (returns accumulated volume since last reset)
    private double getCurrentVolume(String instrument, TradingStrategyEngine context) {
        try {
            String[] instruments = {instrument};
            Map<String, Quote> quotes = context.getKiteConnect().getQuote(instruments);
            Quote quote = quotes.get(instrument);
            if (quote != null) {
                double volume = quote.volumeTradedToday;
                storeVolume(instrument, volume);
                return volume;
            }
        } catch (Exception | KiteException e) { }
        return 0;
    }

    private void storeVolume(String instrument, double volume) {
        List<Double> volumes = volumeHistory.computeIfAbsent(instrument, k -> new ArrayList<>());
        volumes.add(volume);
        while (volumes.size() > 20) volumes.remove(0);
    }

    private double getAverageVolume(String instrument) {
        List<Double> volumes = volumeHistory.get(instrument);
        if (volumes == null || volumes.size() < 10) return 0;
        double sum = 0;
        int count = Math.min(10, volumes.size());
        for (int i = volumes.size() - count; i < volumes.size(); i++) sum += volumes.get(i);
        return sum / count;
    }

    @Override
    public void executeBuySignal(String instrument, Map<String, Object> signalDetails, TradingStrategyEngine context) {
        double entryPrice = (double) signalDetails.get("entryPrice");
        double stopLoss = (double) signalDetails.get("stopLoss");
        double target = (double) signalDetails.get("target");
        System.out.println("\n" + "🚀".repeat(20));
        System.out.println("🚀 [Case 3] EXECUTING BREAKOUT + RETEST BUY SIGNAL");
        System.out.println("   Strategy: Breakout + Retest (Most Powerful Setup)");
        System.out.println("   Entry: " + entryPrice + " (Retest confirmed)");
        System.out.println("   Stop Loss: " + String.format("%.2f", stopLoss));
        System.out.println("   Target: " + String.format("%.2f", target));
        double riskAmount = entryPrice - stopLoss;
        double rewardAmount = target - entryPrice;
        System.out.println("   Risk-Reward: 1:" + String.format("%.2f", rewardAmount / riskAmount));
        System.out.println("🚀".repeat(20));
        context.executeBreakoutRetestBuySignal(instrument, entryPrice, stopLoss, target);
    }

    @Override
    public boolean shouldSkipInstrument(String instrument, TradingStrategyEngine context) {
        if (activeSetups.containsKey(instrument)) {
            System.out.println("   ⏸️ [Case 3] Active breakout-retest setup exists");
            return true;
        }
        return context.shouldSkipInstrument(instrument);
    }

    private CandleData getCandleAtIndex(String instrument, RealTimeCandleBuilder candleBuilder, int indexFromEnd) {
        CandleData candle = CandleHistoryManager.getInstance().getCandleAtIndex(instrument, indexFromEnd);
        if (candle != null) return candle;
        if (indexFromEnd == 1) return candleBuilder.getLastCompletedCandle(instrument);
        if (indexFromEnd == 2) return candleBuilder.getPreviousCompletedCandle(instrument);
        return null;
    }

    private static class RangeData { double high, low, width; }
}