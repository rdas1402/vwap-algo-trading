// BullishEngulfingStrategy.java
package com.trading.strategy;

import com.trading.config.AppConfig;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Quote;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Case 5: Bullish Engulfing Pattern Strategy
 *
 * Stop Loss : Half of the bullish engulfing candle body length from entry
 * Target    : Entry + 2 × Risk (1:2 Risk-Reward)
 *
 * SL Trigger: ONLY on 5-minute candle closure below stop loss level
 * Target Trigger: Checked every 1 second (real-time)
 */
public class BullishEngulfingStrategy implements TradingStrategy {

    private static final int    DOWNTREND_LOOKBACK    = 3;
    private static final double MIN_DOWNTREND_PCT     = 1.0;
    private static final double MIN_ENGULF_RATIO      = 1.0;
    private static final double VOLUME_CONFIRM_RATIO  = 1.2;
    private static final int    VOLUME_AVG_PERIOD     = 10;
    private static final int    MAX_HISTORY           = 60;
    private static final double STOP_LOSS_FACTOR      = 0.5;

    private static final long   TARGET_CHECK_INTERVAL_MS = 1000;
    private static final int    CANDLE_DURATION_MINUTES  = 5;

    private final Map<String, ActiveTradeData> activeTrades = new ConcurrentHashMap<>();
    private final ScheduledExecutorService targetMonitorExecutor = Executors.newScheduledThreadPool(5);

    private static class ActiveTradeData {
        final String instrument;
        final double entryPrice;
        final double stopLoss;
        final double target;
        final Date   entryTime;
        final AtomicBoolean isClosed;
        volatile long lastCandleCloseTime;
        volatile double lastCandleClosePrice;
        volatile boolean stopLossTriggeredThisCandle;

        ScheduledFuture<?> monitorTask;

        ActiveTradeData(String instrument, double entryPrice, double stopLoss, double target) {
            this.instrument = instrument;
            this.entryPrice = entryPrice;
            this.stopLoss = stopLoss;
            this.target = target;
            this.entryTime = new Date();
            this.isClosed = new AtomicBoolean(false);
            this.stopLossTriggeredThisCandle = false;
            this.lastCandleCloseTime = 0;
            this.lastCandleClosePrice = entryPrice;
        }

        void markClosed() {
            isClosed.set(true);
            if (monitorTask != null) monitorTask.cancel(false);
        }
    }

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

            if (activeTrades.containsKey(instrument)) {
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

            // Start unified breakout monitor (5 minutes, 3-second checks)
            context.startEngulfingBreakoutMonitor(instrument, entryTrigger, stopLoss, target);

        } catch (Exception e) {
            System.err.println("❌ [Case 5] Error: " + e.getMessage());
        }
        return result;
    }

    @Override
    public void executeBuySignal(String instrument, Map<String, Object> signalDetails, TradingStrategyEngine context) {
        // This method is not used because the breakout monitor calls executeBullishEngulfingBuySignal directly.
        // Kept for interface compliance.
    }

    // ------------------------------------------------------------------
    //  Trade Management (SL on candle close, target every 1 second)
    // ------------------------------------------------------------------
    private void startTargetMonitoring(ActiveTradeData trade, TradingStrategyEngine context) {
        ScheduledFuture<?> monitorTask = targetMonitorExecutor.scheduleAtFixedRate(() -> {
            try {
                if (trade.isClosed.get()) return;
                double currentPrice = getCurrentLTP(trade.instrument, context);
                if (currentPrice <= 0) return;
                if (currentPrice >= trade.target) {
                    System.out.println("\n" + "🎯".repeat(20));
                    System.out.println("🎯 [Case 5] TARGET HIT! (Real-time check)");
                    System.out.println("   Instrument  : " + trade.instrument);
                    System.out.println("   Entry Price : " + String.format("%.2f", trade.entryPrice));
                    System.out.println("   Target      : " + String.format("%.2f", trade.target));
                    System.out.println("   Exit Price  : " + String.format("%.2f", currentPrice));
                    System.out.println("   Profit      : " + String.format("%.2f", currentPrice - trade.entryPrice));
                    System.out.println("🎯".repeat(20));
                    trade.markClosed();
                    closePosition(trade.instrument, currentPrice, "TARGET_HIT", context);
                    activeTrades.remove(trade.instrument);
                }
            } catch (Exception e) {
                System.err.println("❌ [Case 5] Target monitor error: " + e.getMessage());
            }
        }, 0, TARGET_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
        trade.monitorTask = monitorTask;
    }

    private void checkStopLossOnCandleCompletion(String instrument, TradingStrategyEngine context) {
        ActiveTradeData trade = activeTrades.get(instrument);
        if (trade == null || trade.isClosed.get()) return;
        try {
            RealTimeCandleBuilder candleBuilder = context.getRealTimeCandleBuilder();
            CandleData lastCompleted = candleBuilder.getLastCompletedCandle(instrument);
            if (lastCompleted == null) return;
            long candleCloseTime = lastCompleted.getTimestamp().getTime();
            double candleClose = lastCompleted.getClose();
            boolean isNewCandle = (candleCloseTime != trade.lastCandleCloseTime);
            if (isNewCandle) {
                trade.lastCandleCloseTime = candleCloseTime;
                trade.lastCandleClosePrice = candleClose;
                trade.stopLossTriggeredThisCandle = false;
                System.out.println("   📊 [Case 5] New 5-min candle completed for " + instrument
                        + " | Close: " + String.format("%.2f", candleClose));
                if (candleClose < trade.stopLoss && !trade.stopLossTriggeredThisCandle) {
                    trade.stopLossTriggeredThisCandle = true;
                    System.out.println("\n" + "⛔".repeat(20));
                    System.out.println("⛔ [Case 5] STOP LOSS TRIGGERED (5-min candle closure)");
                    System.out.println("   Instrument    : " + instrument);
                    System.out.println("   Entry Price   : " + String.format("%.2f", trade.entryPrice));
                    System.out.println("   Stop Loss     : " + String.format("%.2f", trade.stopLoss));
                    System.out.println("   Candle Close  : " + String.format("%.2f", candleClose));
                    System.out.println("   Loss          : " + String.format("%.2f", trade.entryPrice - candleClose));
                    System.out.println("⛔".repeat(20));
                    trade.markClosed();
                    closePosition(instrument, candleClose, "STOP_LOSS", context);
                    activeTrades.remove(instrument);
                }
            }
        } catch (Exception e) {
            System.err.println("❌ [Case 5] Stop loss check error: " + e.getMessage());
        }
    }

    private void closePosition(String instrument, double price, String reason, TradingStrategyEngine context) {
        System.out.println("🔒 [Case 5] Closing position for " + instrument);
        System.out.println("   Exit Price: " + String.format("%.2f", price));
        System.out.println("   Reason    : " + reason);
        // TODO: Implement actual position closing if needed – currently just removes from active trades.
        activeTrades.remove(instrument);
    }

    // ------------------------------------------------------------------
    //  Helper methods
    // ------------------------------------------------------------------
    private double getCurrentLTP(String instrument, TradingStrategyEngine context) {
        try {
            String[] instrumentArr = {instrument};
            Map<String, Quote> quotes = context.getKiteConnect().getQuote(instrumentArr);
            Quote quote = quotes.get(instrument);
            return quote != null ? quote.lastPrice : 0;
        } catch (Exception | KiteException e) { return 0; }
    }

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

    public void shutdown() {
        targetMonitorExecutor.shutdown();
        try {
            if (!targetMonitorExecutor.awaitTermination(5, TimeUnit.SECONDS))
                targetMonitorExecutor.shutdownNow();
        } catch (InterruptedException e) {
            targetMonitorExecutor.shutdownNow();
        }
    }

    @Override
    public boolean shouldSkipInstrument(String instrument, TradingStrategyEngine context) {
        if (activeTrades.containsKey(instrument)) return true;
        return context.shouldSkipInstrument(instrument);
    }
}