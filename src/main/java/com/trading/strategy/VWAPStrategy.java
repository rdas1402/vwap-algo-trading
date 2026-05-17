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
        ReversalPatternData(double breakoutLevel, double stopLossLevel) {
            this.breakoutLevel = breakoutLevel;
            this.stopLossLevel = stopLossLevel;
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
            if (completedCandleCount < 3) {
                System.out.println("⏳ [Case 4] Waiting for more candle data for " + instrument + " (have " + completedCandleCount + ", need 3)");
                return result;
            }

            CandleData currentCandle = candleBuilder.getCurrentCandle(instrument);
            CandleData lastCompletedCandle = candleBuilder.getLastCompletedCandle(instrument);
            CandleData previousCandle = candleBuilder.getPreviousCompletedCandle(instrument);
            CandleData twoCandlesBack = candleBuilder.getCompletedCandleAtIndex(instrument, 3);

            boolean isCurrentCandleEmpty = isCandleEmpty(currentCandle);
            if (isCurrentCandleEmpty && lastCompletedCandle != null && completedCandleCount >= 2) {
                currentCandle = lastCompletedCandle;
                lastCompletedCandle = previousCandle;
                previousCandle = twoCandlesBack;
                if (completedCandleCount >= 4) twoCandlesBack = candleBuilder.getCompletedCandleAtIndex(instrument, 4);
            }

            if (currentCandle == null || lastCompletedCandle == null || previousCandle == null) {
                System.out.println("❌ [Case 4] Missing required candle data for " + instrument);
                return result;
            }

            boolean vwapReversal = checkVWAPReversalPattern(instrument, currentCandle, lastCompletedCandle, previousCandle, context);
            if (vwapReversal) {
                System.out.println("🎯 [Case 4] VWAP REVERSAL PATTERN DETECTED for " + instrument);
                result.put("signal", true);
                result.put("pattern", "reversal");
                return result;
            }

            boolean originalCrossover = checkOriginalVWAPCrossover(instrument, currentCandle, lastCompletedCandle, previousCandle, twoCandlesBack, context);
            if (originalCrossover) {
                System.out.println("🎯 [Case 4] VWAP CROSSOVER DETECTED for " + instrument);
                result.put("signal", true);
                result.put("pattern", "crossover");
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
        context.executeBuySignal(instrument, patternType);
    }

    @Override
    public boolean shouldSkipInstrument(String instrument, TradingStrategyEngine context) {
        return context.shouldSkipInstrument(instrument);
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
            double lowToCurrentPercent = Math.abs(currentClose - lowestLow) / currentClose * 100;
            double stopLossLevel = (lowToCurrentPercent > 20.0) ? currentClose * 0.90 : lowestLow;

            reversalPatternData.put(instrument, new ReversalPatternData(highestHigh, stopLossLevel));

            if (currentClose >= highestHigh) {
                return true;
            } else {
                if (!context.isPatternBuyTimeAllowed("reversal")) {
                    System.out.println("⏸️ Reversal trading allowed only from 10:00 – monitor not started");
                    return false;
                }
                context.startReversalBreakoutMonitor(instrument, highestHigh, stopLossLevel);
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

            double lastClose = lastCompletedCandle.getClose();
            double lastVWAP = lastCompletedCandle.getVWAP();

            double previousClose = previousCandle.getClose();
            double previousVWAP = previousCandle.getVWAP();

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
                System.out.println("   🎯 VWAP Crossover Confirmed!");
                System.out.println("      - Previous Close: " + previousClose + " < VWAP: " + previousVWAP);
                System.out.println("      - Last Close: " + lastClose + " < VWAP: " + lastVWAP);
                System.out.println("      - Current Close: " + currentClose + " > VWAP: " + currentVWAP);
                System.out.println("      - Breakout Level: " + breakoutLevel);
                if (!context.isPatternBuyTimeAllowed("crossover")) {
                    System.out.println("⏸️ Crossover trading allowed only from 09:45 – monitor not started");
                    return false;
                }
                context.startCrossoverBreakoutMonitor(instrument, breakoutLevel, lastCompletedCandle.getHigh(), currentClose);
                return false;
            }
            return false;
        } catch (Exception e) {
            System.err.println("❌ Error in VWAP crossover check: " + e.getMessage());
            return false;
        }
    }
}