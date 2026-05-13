// BullishEngulfingStrategy.java – Integrated with engine’s position management
package com.trading.strategy;

import com.trading.config.AppConfig;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Quote;

import java.util.*;

public class BullishEngulfingStrategy implements TradingStrategy {

    private static final int    DOWNTREND_LOOKBACK    = 3;
    private static final double MIN_DOWNTREND_PCT     = 1.0;
    private static final double MIN_ENGULF_RATIO      = 1.0;
    private static final double STOP_LOSS_FACTOR      = 0.5;

    private final Map<String, Long> lastCandleCloseTime = new HashMap<>();
    private final Map<String, Boolean> stopLossTriggeredThisCandle = new HashMap<>();

    @Override
    public int getPriority() { return 5; }

    @Override
    public String getStrategyName() { return "Case 5: Bullish Engulfing Reversal Strategy"; }

    @Override
    public boolean canExecute(TradingStrategyEngine context) { return context.isWithinBuyingHours(); }

    @Override
    public Map<String, String> findInstruments(TradingStrategyEngine context) throws Exception, KiteException {
        Map<String, String> options = new HashMap<>();
        double targetPremium = AppConfig.getTargetPremium();
        double tolerance = AppConfig.getPremiumTolerance();
        double niftySpot = context.getNiftySpotPrice();
        System.out.println("📊 [Case 5] Nifty Spot Price: " + niftySpot);
        System.out.println("   Target Premium: " + targetPremium + " +/-" + tolerance);
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
            System.out.println("\n🕯️ [Case 5] Analyzing Bullish Engulfing for: " + instrument);

            // If there is already an open position for this instrument, check stop-loss on candle close
            if (context.hasOpenPosition(instrument)) {
                checkStopLossOnCandleCompletion(instrument, context);
                return result;
            }

            RealTimeCandleBuilder candleBuilder = context.getRealTimeCandleBuilder();
            int completedCount = candleBuilder.getCompletedCandleCount(instrument);
            int minRequired = DOWNTREND_LOOKBACK + 2;
            if (completedCount < minRequired) {
                System.out.println("⏳ [Case 5] Need " + minRequired + " candles (have " + completedCount + ")");
                return result;
            }

            CandleData engulfingCandle = candleBuilder.getLastCompletedCandle(instrument);
            CandleData bearishCandle = candleBuilder.getPreviousCompletedCandle(instrument);
            if (engulfingCandle == null || bearishCandle == null) {
                System.out.println("❌ [Case 5] Missing candle data for " + instrument);
                return result;
            }

            boolean downtrend = checkDowntrend(instrument);
            double downtrendPct = calculateDowntrendPercent(instrument, bearishCandle);
            boolean prevBearish = bearishCandle.getClose() < bearishCandle.getOpen();
            boolean validEngulfing = isBullishEngulfing(bearishCandle, engulfingCandle);

            if (!downtrend || downtrendPct < MIN_DOWNTREND_PCT || !prevBearish || !validEngulfing) {
                return result;
            }

            double engulfingBodyLength = engulfingCandle.getClose() - engulfingCandle.getOpen();
            double riskAmount = engulfingBodyLength * STOP_LOSS_FACTOR;
            double entryTrigger = engulfingCandle.getHigh() + 0.05;
            double stopLoss = entryTrigger - riskAmount;
            double target = entryTrigger + (riskAmount * 2);

            System.out.println("\n" + "🕯️".repeat(15));
            System.out.println("🕯️ [Case 5] BULLISH ENGULFING DETECTED for " + instrument);
            System.out.println("   Bullish Body     : " + String.format("%.2f", engulfingBodyLength));
            System.out.println("   Stop Loss Dist   : " + String.format("%.2f", riskAmount) + " (50% of body)");
            System.out.println("   Entry Trigger    : " + String.format("%.2f", entryTrigger));
            System.out.println("🕯️".repeat(15));

            // Start breakout monitor (engine will call executeBullishEngulfingBuySignal on breakout)
            context.startEngulfingBreakoutMonitor(instrument, entryTrigger, stopLoss, target);

        } catch (Exception e) {
            System.err.println("❌ [Case 5] Error: " + e.getMessage());
        }
        return result;
    }

    @Override
    public void executeBuySignal(String instrument, Map<String, Object> signalDetails, TradingStrategyEngine context) {
        // Not used – breakout monitor calls executeBullishEngulfingBuySignal directly
    }

    // ------------------------------------------------------------------
    //  Stop Loss Management (only on 5‑minute candle close)
    // ------------------------------------------------------------------
    private void checkStopLossOnCandleCompletion(String instrument, TradingStrategyEngine context) {
        try {
            // Get the position from engine
            Position position = context.getPosition(instrument);
            if (position == null) return;

            RealTimeCandleBuilder candleBuilder = context.getRealTimeCandleBuilder();
            CandleData lastCompleted = candleBuilder.getLastCompletedCandle(instrument);
            if (lastCompleted == null) return;

            long candleCloseTime = lastCompleted.getTimestamp().getTime();
            double candleClose = lastCompleted.getClose();

            Long lastTime = lastCandleCloseTime.get(instrument);
            boolean isNewCandle = (lastTime == null || candleCloseTime != lastTime);

            if (isNewCandle) {
                lastCandleCloseTime.put(instrument, candleCloseTime);
                stopLossTriggeredThisCandle.put(instrument, false);

                System.out.println("   📊 [Case 5] New 5-min candle completed for " + instrument
                        + " | Close: " + String.format("%.2f", candleClose));

                boolean slHit = candleClose < position.getStopLoss() &&
                        !stopLossTriggeredThisCandle.getOrDefault(instrument, false);

                if (slHit) {
                    stopLossTriggeredThisCandle.put(instrument, true);
                    System.out.println("\n" + "⛔".repeat(20));
                    System.out.println("⛔ [Case 5] STOP LOSS TRIGGERED (5-min candle closure)");
                    System.out.println("   Instrument    : " + instrument);
                    System.out.println("   Entry Price   : " + String.format("%.2f", position.getEntryPrice()));
                    System.out.println("   Stop Loss     : " + String.format("%.2f", position.getStopLoss()));
                    System.out.println("   Candle Close  : " + String.format("%.2f", candleClose));
                    System.out.println("⛔".repeat(20));

                    // Use engine's public close method (handles real/simulated and logs P&L)
                    context.closePosition(instrument, "STOP_LOSS_ENGULFING");
                }
            }
        } catch (Exception e) {
            System.err.println("❌ [Case 5] Stop loss check error: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    //  Helper methods (unchanged)
    // ------------------------------------------------------------------
    private boolean isBullishEngulfing(CandleData previous, CandleData current) {
        if (previous == null || current == null) return false;
        boolean prevBearish = previous.getClose() < previous.getOpen();
        boolean currBullish = current.getClose() > current.getOpen();
        boolean opensBelow = current.getOpen() <= previous.getClose();
        boolean closesAbove = current.getClose() >= previous.getOpen();
        if (!prevBearish || !currBullish || !opensBelow || !closesAbove) return false;
        double bearishBody = previous.getOpen() - previous.getClose();
        double engulfingBody = current.getClose() - current.getOpen();
        return bearishBody > 0 && (engulfingBody / bearishBody) >= MIN_ENGULF_RATIO;
    }

    private boolean checkDowntrend(String instrument) {
        List<CandleData> history = CandleHistoryManager.getInstance().getHistory(instrument);
        if (history == null || history.size() < DOWNTREND_LOOKBACK) return false;
        int size = history.size();
        int declines = 0;
        for (int i = size - 1; i > size - DOWNTREND_LOOKBACK; i--) {
            if (history.get(i - 1).getClose() > history.get(i).getClose()) declines++;
        }
        return declines >= (DOWNTREND_LOOKBACK - 1);
    }

    private double calculateDowntrendPercent(String instrument, CandleData bearishCandle) {
        List<CandleData> history = CandleHistoryManager.getInstance().getHistory(instrument);
        if (history == null || history.size() < DOWNTREND_LOOKBACK) return 0;
        int size = history.size();
        double highestClose = Double.MIN_VALUE;
        for (int i = Math.max(0, size - DOWNTREND_LOOKBACK); i < size; i++) {
            highestClose = Math.max(highestClose, history.get(i).getClose());
        }
        double currentClose = bearishCandle.getClose();
        return highestClose > 0 ? ((highestClose - currentClose) / highestClose) * 100.0 : 0;
    }

    @Override
    public boolean shouldSkipInstrument(String instrument, TradingStrategyEngine context) {
        return context.shouldSkipInstrument(instrument);
    }
}