// VWAPStrategy.java
package com.trading.strategy;

import com.trading.config.AppConfig;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class VWAPStrategy implements TradingStrategy {

    private final Map<String, ReversalPatternData> reversalPatternData = new ConcurrentHashMap<>();

    private static class ReversalPatternData {
        final double breakoutLevel;
        final double stopLossLevel;
        final double targetLevel;
        ReversalPatternData(double breakoutLevel, double stopLossLevel, double targetLevel) {
            this.breakoutLevel = breakoutLevel;
            this.stopLossLevel = stopLossLevel;
            this.targetLevel = targetLevel;
        }
    }

    @Override
    public int getPriority() {
        return 4;
    }

    @Override
    public String getStrategyName() {
        return "Case 4: Original VWAP Options Strategy (Reversal & Crossover)";
    }

    @Override
    public Map<String, String> findInstruments(TradingStrategyEngine context) throws Exception, KiteException {
        Map<String, String> options = new HashMap<>();
        double targetPremium = AppConfig.getTargetPremium();
        double tolerance = AppConfig.getPremiumTolerance();
        double niftySpot = context.getNiftySpotPrice();
        System.out.println("📊 [Case 4] Nifty Spot Price: " + niftySpot);
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
            System.out.println("🔍 [Case 4] Analyzing with Real-time Candles for: " + instrument);
            RealTimeCandleBuilder candleBuilder = context.getRealTimeCandleBuilder();
            int completedCandleCount = candleBuilder.getCompletedCandleCount(instrument);
            if (completedCandleCount < 4) {  // need at least 4 completed candles for 3+1 pattern
                System.out.println("⏳ [Case 4] Need 4 completed candles, have " + completedCandleCount);
                return result;
            }

            // Use ONLY completed candles – most recent is index 1
            CandleData current = candleBuilder.getLastCompletedCandle(instrument);      // T (just closed)
            CandleData prev1    = candleBuilder.getPreviousCompletedCandle(instrument); // T-1
            CandleData prev2    = candleBuilder.getCompletedCandleAtIndex(instrument, 3); // T-2
            CandleData prev3    = candleBuilder.getCompletedCandleAtIndex(instrument, 4);

            if (current == null || prev1 == null || prev2 == null || prev3 == null) {
                System.out.println("❌ [Case 4] Missing completed candle data");
                return result;
            }

            System.out.println("❌❌❌❌❌❌ current  O:" + current.getOpen() + " H:" + current.getHigh() + " L:" + current.getLow() +
                    " C:" + current.getClose() + " VWAP:" + String.format("%.2f", current.getVWAP()));
            System.out.println("❌❌❌❌❌❌ prev1  O:" + prev1.getOpen() + " H:" + prev1.getHigh() + " L:" + prev1.getLow() +
                    " C:" + prev1.getClose() + " VWAP:" + String.format("%.2f", prev1.getVWAP()));
            System.out.println("❌❌❌❌❌❌ prev2  O:" + prev2.getOpen() + " H:" + prev2.getHigh() + " L:" + prev2.getLow() +
                    " C:" + prev2.getClose() + " VWAP:" + String.format("%.2f", prev2.getVWAP()));
            System.out.println("❌❌❌❌❌❌ prev3  O:" + prev3.getOpen() + " H:" + prev3.getHigh() + " L:" + prev3.getLow() +
                    " C:" + prev3.getClose() + " VWAP:" + String.format("%.2f", prev3.getVWAP()));

            boolean vwapReversal = checkVWAPReversalPattern(instrument, current, prev1, prev2, context);
            if (vwapReversal) {
                System.out.println("🎯 [Case 4] VWAP REVERSAL PATTERN DETECTED for " + instrument);
                result.put("signal", true);
                result.put("pattern", "reversal");
                ReversalPatternData setup = reversalPatternData.get(instrument);
                if (setup != null) {
                    result.put("entryPrice", setup.breakoutLevel);
                    result.put("stopLoss", setup.stopLossLevel);
                    result.put("target", setup.targetLevel);
                }
                return result;
            }

            boolean originalCrossover = checkOriginalVWAPCrossover(instrument, current, prev1, prev2, prev3, context);
            if (originalCrossover) {
                System.out.println("🎯 [Case 4] VWAP CROSSOVER DETECTED for " + instrument);
                result.put("signal", true);
                result.put("pattern", "crossover");
                ReversalPatternData setup = reversalPatternData.get(instrument);
                if (setup != null) {
                    result.put("entryPrice", setup.breakoutLevel);
                    result.put("stopLoss", setup.stopLossLevel);
                    result.put("target", setup.targetLevel);
                }
                return result;
            }

            System.out.println("📊 [Case 4] No trading signal detected for " + instrument);

        } catch (Exception e) {
            System.err.println("❌ [Case 4] Error analyzing " + instrument + ": " + e.getMessage());
        }
        return result;
    }

    @Override
    public void executeBuySignal(String instrument, Map<String, Object> signalDetails, TradingStrategyEngine context) {
        String patternType = (String) signalDetails.get("pattern");
        double entryPrice = getDouble(signalDetails, "entryPrice", 0);
        double stopLoss = getDouble(signalDetails, "stopLoss", 0);
        double target = getDouble(signalDetails, "target", 0);

        if (entryPrice > 0 && stopLoss > 0 && target > 0) {
            context.executeStructuredBuySignal(instrument, patternType, entryPrice, stopLoss, target);
        } else {
            context.executeBuySignal(instrument, patternType);
        }
    }

    @Override
    public boolean shouldSkipInstrument(String instrument, TradingStrategyEngine context) {
        return context.shouldSkipInstrument(instrument);
    }

    private double getDouble(Map<String, Object> values, String key, double fallback) {
        Object value = values.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return fallback;
    }

    private boolean isCandleEmpty(CandleData candle) {
        if (candle == null) return true;
        double open = candle.getOpen(), close = candle.getClose();
        if (open == 0 && close == 0) return true;
        double range = candle.getHigh() - candle.getLow();
        return range < 0.01 && Math.abs(open - close) < 0.01;
    }

    private boolean checkVWAPReversalPattern(String instrument, CandleData currentCandle,
                                             CandleData lastCompletedCandle, CandleData previousCandle,
                                             TradingStrategyEngine context) {
        try {
            double currentClose = currentCandle.getClose();
            double currentVWAP = currentCandle.getVWAP();
            double currentLow = currentCandle.getLow();
            double currentHigh = currentCandle.getHigh();

            double lastClose = lastCompletedCandle.getClose();
            double lastVWAP = lastCompletedCandle.getVWAP();
            double lastLow = lastCompletedCandle.getLow();
            double lastHigh = lastCompletedCandle.getHigh();

            double prevClose = previousCandle.getClose();
            double prevVWAP = previousCandle.getVWAP();
            double prevLow = previousCandle.getLow();
            double prevHigh = previousCandle.getHigh();

            boolean anyCandleNearVWAP = (prevLow <= prevVWAP) || (lastLow <= lastVWAP) || (currentLow <= currentVWAP);
            if (!anyCandleNearVWAP) return false;

            boolean pattern1Detected = (prevClose > prevVWAP) && (lastClose > lastVWAP) && (currentClose > currentVWAP);
            double prevDistancePercent = (prevClose - prevVWAP) / prevVWAP * 100;
            double lastDistancePercent = (lastClose - lastVWAP) / lastVWAP * 100;
            double currentDistancePercent = (currentClose - currentVWAP) / currentVWAP * 100;

            boolean prevBelowWithin2Percent = (prevClose < prevVWAP) && (Math.abs(prevDistancePercent) <= 2.0);
            boolean lastBelowWithin2Percent = (lastClose < lastVWAP) && (Math.abs(lastDistancePercent) <= 2.0);
            boolean currentBelowWithin2Percent = (currentClose < currentVWAP) && (Math.abs(currentDistancePercent) <= 2.0);

            int belowWithin2PercentCount = (prevBelowWithin2Percent ? 1 : 0) + (lastBelowWithin2Percent ? 1 : 0) + (currentBelowWithin2Percent ? 1 : 0);
            boolean thirdCandleAboveVWAP = (!prevBelowWithin2Percent && prevClose > prevVWAP) ||
                    (!lastBelowWithin2Percent && lastClose > lastVWAP) ||
                    (!currentBelowWithin2Percent && currentClose > currentVWAP);
            boolean pattern2Detected = (belowWithin2PercentCount == 1 || belowWithin2PercentCount == 2) && thirdCandleAboveVWAP && prevDistancePercent >= -2.0;

            if (!pattern1Detected && !pattern2Detected) return false;

            double highestHigh = Math.max(prevHigh, Math.max(lastHigh, currentHigh));
            double lowestLow = Math.min(prevLow, Math.min(lastLow, currentLow));
            double setupRange = Math.max(0.05, highestHigh - lowestLow);
            double stopBuffer = Math.max(0.25, setupRange * 0.10);
            double stopLossLevel = Math.max(0.05, lowestLow - stopBuffer);
            double targetLevel = highestHigh + ((highestHigh - stopLossLevel) * 2.0);

            reversalPatternData.put(instrument, new ReversalPatternData(highestHigh, stopLossLevel, targetLevel));

            if (currentClose >= highestHigh) {
                return true;
            } else {
                if (!context.isPatternBuyTimeAllowed("reversal")) {
                    System.out.println("⏸️ Reversal trading allowed only from 10:00 – monitor not started");
                    return false;
                }
                context.startReversalBreakoutMonitor(instrument, highestHigh, stopLossLevel, targetLevel);
                return false;
            }
        } catch (Exception e) {
            System.err.println("❌ Error checking VWAP reversal pattern: " + e.getMessage());
            return false;
        }
    }

    private boolean checkOriginalVWAPCrossover(String instrument, CandleData currentCandle,
                                               CandleData lastCompletedCandle, CandleData previousCandle,
                                               CandleData twoCandlesBack, TradingStrategyEngine context) {
        try {
            double currentClose = currentCandle.getClose();
            double currentVWAP = currentCandle.getVWAP();
            double currentHigh = currentCandle.getHigh();
            double currentLow = currentCandle.getLow();

            double lastClose = lastCompletedCandle.getClose();
            double lastVWAP = lastCompletedCandle.getVWAP();
            double lastLow = lastCompletedCandle.getLow();

            double previousClose = previousCandle.getClose();
            double previousVWAP = previousCandle.getVWAP();
            double previousLow = previousCandle.getLow();

            boolean previousBelowVWAP = previousClose < previousVWAP;
            boolean lastBelowVWAP = lastClose < lastVWAP;
            boolean currentAboveVWAP = currentClose > currentVWAP;

            boolean twoBackBelowVWAP = false;
            if (twoCandlesBack != null) {
                twoBackBelowVWAP = twoCandlesBack.getClose() < twoCandlesBack.getVWAP();
            }

            boolean crossoverDetected = false;
            if (twoBackBelowVWAP && previousBelowVWAP && lastBelowVWAP && currentAboveVWAP) {
                crossoverDetected = true;
                System.out.println("   📈 Crossover Pattern B detected (3 below, 1 above) - STRONGER");
            }

            if (crossoverDetected) {
                double breakoutLevel = currentHigh + 1.0;
                double twoBackLow = twoCandlesBack != null ? twoCandlesBack.getLow() : currentLow;
                double lowestLow = Math.min(Math.min(previousLow, lastLow), Math.min(currentLow, twoBackLow));
                double setupRange = Math.max(0.05, breakoutLevel - lowestLow);
                double stopBuffer = Math.max(0.25, setupRange * 0.10);
                double stopLossLevel = Math.max(0.05, lowestLow - stopBuffer);
                double targetLevel = breakoutLevel + ((breakoutLevel - stopLossLevel) * 2.0);

                reversalPatternData.put(instrument, new ReversalPatternData(breakoutLevel, stopLossLevel, targetLevel));
                System.out.println("   🎯 VWAP Crossover Confirmed!");
                System.out.println("      - Previous Close: " + previousClose + " < VWAP: " + previousVWAP);
                System.out.println("      - Last Close: " + lastClose + " < VWAP: " + lastVWAP);
                System.out.println("      - Current Close: " + currentClose + " > VWAP: " + currentVWAP);
                System.out.println("      - Breakout Level: " + breakoutLevel);
                if (!context.isPatternBuyTimeAllowed("crossover")) {
                    System.out.println("⏸️ Crossover trading allowed only from 09:45 – monitor not started");
                    return false;
                }
                context.startCrossoverBreakoutMonitor(instrument, breakoutLevel, stopLossLevel, targetLevel);
                return false;
            }
            return false;
        } catch (Exception e) {
            System.err.println("❌ Error in VWAP crossover check: " + e.getMessage());
            return false;
        }
    }
}
