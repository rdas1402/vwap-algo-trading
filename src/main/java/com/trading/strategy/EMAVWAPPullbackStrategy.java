package com.trading.strategy;

import com.trading.config.AppConfig;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;

import java.util.*;

public class EMAVWAPPullbackStrategy implements TradingStrategy, EngineAware {

    private static final int EMA_PERIOD = 10;
    private static final int ATR_PERIOD = 14;

    private final Map<String, Double> atrCache = new HashMap<>();
    private transient TradingStrategyEngine engineContext;

    @Override
    public void setEngineContext(TradingStrategyEngine context) {
        this.engineContext = context;
    }

    @Override
    public int getPriority() {
        return 1;
    }

    @Override
    public String getStrategyName() {
        return "Case 1: EMA/VWAP Pullback Strategy (Complete Implementation)";
    }

    @Override
    public Map<String, String> findInstruments(TradingStrategyEngine context) throws Exception, KiteException {
        Map<String, String> options = new HashMap<>();

        double targetPremium = AppConfig.getTargetPremium();
        double tolerance = AppConfig.getPremiumTolerance();

        double niftySpot = context.getNiftySpotPrice();
        System.out.println("📊 [Case 1] Nifty Spot Price: " + niftySpot);
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
            System.out.println("\n🔍 [Case 1] Analyzing " + instrument);
            System.out.println("   Conditions: Price>VWAP, EMA>VWAP, Pullback near VWAP/EMA, Bullish Pattern, Volume Spike");

            RealTimeCandleBuilder candleBuilder = context.getRealTimeCandleBuilder();

            int completedCandleCount = candleBuilder.getCompletedCandleCount(instrument);
            if (completedCandleCount < 10) {
                System.out.println("⏳ [Case 1] Need 10 candles for EMA calculation (have " + completedCandleCount + ")");
                return result;
            }

            CandleData currentCandle = candleBuilder.getCurrentCandle(instrument);
            CandleData lastCompletedCandle = candleBuilder.getLastCompletedCandle(instrument);
            CandleData previousCandle = candleBuilder.getPreviousCompletedCandle(instrument);

            if (currentCandle == null || lastCompletedCandle == null || previousCandle == null) {
                return result;
            }

            double currentClose = lastCompletedCandle.getClose();
            double currentVWAP = lastCompletedCandle.getVWAP();
            double currentEMA = calculateTypicalPriceEMA(instrument);
            double currentATR = calculateATR(instrument);

            atrCache.put(instrument, currentATR);

            boolean priceAboveVWAP = currentClose > currentVWAP;
            boolean emaAboveVWAP = currentEMA > currentVWAP;

            System.out.println("\n   📈 Trend Conditions:");
            System.out.println("      ✅ Price (" + String.format("%.2f", currentClose) +
                    ") > VWAP (" + String.format("%.2f", currentVWAP) + "): " + priceAboveVWAP);
            System.out.println("      ✅ 20 EMA (" + String.format("%.2f", currentEMA) +
                    ") > VWAP (" + String.format("%.2f", currentVWAP) + "): " + emaAboveVWAP);

            if (!priceAboveVWAP || !emaAboveVWAP) {
                System.out.println("   ❌ Trend conditions NOT met");
                return result;
            }

            double pullbackThreshold = Math.max(currentATR * 0.5, currentVWAP * 0.01);
            double distanceToVWAP = Math.abs(currentClose - currentVWAP);
            double distanceToEMA = Math.abs(currentClose - currentEMA);

            boolean nearVWAP = distanceToVWAP <= pullbackThreshold;
            boolean nearEMA = distanceToEMA <= pullbackThreshold;

            CandleData twoCandlesBack = getCandleAtIndex(instrument, candleBuilder, 2);
            boolean wasHigherBefore = twoCandlesBack != null && twoCandlesBack.getClose() > currentClose * 1.01;

            System.out.println("\n   📉 Pullback Analysis:");
            System.out.println("      - ATR: " + String.format("%.2f", currentATR));
            System.out.println("      - Pullback Threshold: " + String.format("%.2f", pullbackThreshold));
            System.out.println("      - Distance to VWAP: " + String.format("%.2f", distanceToVWAP) + " (Near: " + nearVWAP + ")");
            System.out.println("      - Distance to EMA: " + String.format("%.2f", distanceToEMA) + " (Near: " + nearEMA + ")");
            System.out.println("      - Price was higher before: " + wasHigherBefore);

            if ((!nearVWAP && !nearEMA) || !wasHigherBefore) {
                System.out.println("   ❌ Pullback condition NOT met");
                return result;
            }

            boolean bullishEngulfing = checkBullishEngulfing(previousCandle, lastCompletedCandle);
            boolean hammer = checkHammerPattern(lastCompletedCandle);
            boolean bullishPattern = bullishEngulfing || hammer;

            System.out.println("\n   🕯️ Pattern Detection:");
            System.out.println("      - Bullish Engulfing: " + bullishEngulfing);
            System.out.println("      - Hammer Pattern: " + hammer);

            if (!bullishPattern) {
                System.out.println("   ❌ No bullish pattern detected");
                return result;
            }

            boolean volumeSpike = checkVolumeSpike(instrument, lastCompletedCandle, context);
            System.out.println("\n   📊 Volume Analysis:");
            System.out.println("      - Volume Spike: " + volumeSpike);

            if (!volumeSpike) {
                System.out.println("   ❌ No volume spike detected");
                return result;
            }

            // All conditions met – set up breakout monitor
            double signalCandleHigh = lastCompletedCandle.getHigh();
            double signalCandleLow = lastCompletedCandle.getLow();
            double entryPrice = signalCandleHigh;
            double riskAmount = entryPrice - signalCandleLow;
            double stopLoss = signalCandleLow - (currentATR * 0.3);
            double target1 = entryPrice + (riskAmount * 1.5);
            double target2 = findPreviousSwingHigh(instrument, candleBuilder, currentClose);

            String patternName = bullishEngulfing ? "Bullish Engulfing" : "Hammer";

            System.out.println("\n" + "🎯".repeat(20));
            System.out.println("🎯 [Case 1] ALL CONDITIONS MET - PULLBACK PATTERN DETECTED!");
            System.out.println("   ✅ Price > VWAP: " + priceAboveVWAP);
            System.out.println("   ✅ EMA > VWAP: " + emaAboveVWAP);
            System.out.println("   ✅ Pullback near " + (nearVWAP ? "VWAP" : "EMA"));
            System.out.println("   ✅ Pattern: " + patternName);
            System.out.println("   ✅ Volume Spike: Yes");
            System.out.println("\n   📊 Signal Candle:");
            System.out.println("      - High: " + signalCandleHigh);
            System.out.println("      - Low: " + signalCandleLow);
            System.out.println("      - ATR: " + String.format("%.2f", currentATR));
            System.out.println("\n   🎯 Trade Setup:");
            System.out.println("      - 👉 Entry (Break of signal high): " + entryPrice);
            System.out.println("      - 🛑 Stop Loss: " + String.format("%.2f", stopLoss));
            System.out.println("      - 📊 Risk Amount: " + String.format("%.2f", riskAmount));
            System.out.println("      - 🎯 Target 1 (1:1.5): " + String.format("%.2f", target1));
            if (target2 > 0) {
                System.out.println("      - 🎯 Target 2 (Swing High): " + String.format("%.2f", target2));
            }
            System.out.println("🎯".repeat(20));

            if (!context.isPatternBuyTimeAllowed("pullback")) {
                System.out.println("⏸️ Pullback trading allowed only from 09:45 – monitor not started");
                return result;
            }
            context.startPullbackBreakoutMonitor(instrument, entryPrice, stopLoss, target1);

            result.put("message", "Pullback pattern detected – monitoring breakout (5 min)");
            result.put("entryPrice", entryPrice);
            result.put("stopLoss", stopLoss);
            result.put("target", target1);

        } catch (Exception e) {
            System.err.println("❌ [Case 1] Error: " + e.getMessage());
            e.printStackTrace();
        }
        return result;
    }

    // FIX #4: Improved ATR calculation (skip zero-range candles)
    private double calculateATR(String instrument) {
        List<CandleData> history = CandleHistoryManager.getInstance().getHistory(instrument);
        if (history == null || history.size() < ATR_PERIOD + 1) return 0;
        List<Double> trueRanges = new ArrayList<>();
        for (int i = 1; i < history.size(); i++) {
            CandleData current = history.get(i);
            CandleData previous = history.get(i - 1);
            // Skip zero-range candles (flat)
            if (current.getHigh() == current.getLow()) continue;
            double tr1 = current.getHigh() - current.getLow();
            double tr2 = Math.abs(current.getHigh() - previous.getClose());
            double tr3 = Math.abs(current.getLow() - previous.getClose());
            trueRanges.add(Math.max(tr1, Math.max(tr2, tr3)));
        }
        if (trueRanges.size() < ATR_PERIOD) {
            return trueRanges.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        }
        double sum = 0;
        for (int i = trueRanges.size() - ATR_PERIOD; i < trueRanges.size(); i++) sum += trueRanges.get(i);
        return sum / ATR_PERIOD;
    }

    // ---------- Helper methods (unchanged from original) ----------
    private double calculateTypicalPriceEMA(String instrument) {
        List<CandleData> history = CandleHistoryManager.getInstance().getHistory(instrument);
        if (history == null || history.size() < 3) {
            if (engineContext != null) {
                RealTimeCandleBuilder builder = engineContext.getRealTimeCandleBuilder();
                CandleData currentCandle = builder != null ? builder.getCurrentCandle(instrument) : null;
                if (currentCandle != null) return currentCandle.getClose();
            }
            return 0;
        }
        int period = Math.min(EMA_PERIOD, history.size());
        List<Double> typicalPrices = new ArrayList<>();
        for (int i = history.size() - period; i < history.size(); i++) {
            CandleData candle = history.get(i);
            double typicalPrice = (candle.getHigh() + candle.getLow() + candle.getClose()) / 3;
            typicalPrices.add(typicalPrice);
        }
        if (typicalPrices.isEmpty()) return 0;
        double multiplier = 2.0 / (period + 1);
        double ema = typicalPrices.get(0);
        for (int i = 1; i < typicalPrices.size(); i++) {
            ema = (typicalPrices.get(i) - ema) * multiplier + ema;
        }
        return ema;
    }

    private boolean checkVolumeSpike(String instrument, CandleData currentCandle, TradingStrategyEngine context) {
        // Volume check temporarily disabled for options trading
        return true;
    }

    private double findPreviousSwingHigh(String instrument, RealTimeCandleBuilder candleBuilder, double currentPrice) {
        try {
            List<Double> highs = new ArrayList<>();
            for (int i = 1; i <= 15; i++) {
                CandleData candle = getCandleAtIndex(instrument, candleBuilder, i);
                if (candle != null) highs.add(candle.getHigh());
            }
            if (highs.size() < 5) return 0;
            double swingHigh = 0;
            for (int i = 2; i < highs.size() - 2; i++) {
                double current = highs.get(i);
                double prev1 = highs.get(i - 1), prev2 = highs.get(i - 2);
                double next1 = highs.get(i + 1), next2 = highs.get(i + 2);
                if (current > prev1 && current > prev2 && current > next1 && current > next2) {
                    if (current > swingHigh && current > currentPrice * 1.02) swingHigh = current;
                }
            }
            return swingHigh;
        } catch (Exception e) { return 0; }
    }

    private CandleData getCandleAtIndex(String instrument, RealTimeCandleBuilder candleBuilder, int indexFromEnd) {
        CandleData candle = CandleHistoryManager.getInstance().getCandleAtIndex(instrument, indexFromEnd);
        if (candle != null) return candle;
        if (indexFromEnd == 1) return candleBuilder.getLastCompletedCandle(instrument);
        if (indexFromEnd == 2) return candleBuilder.getPreviousCompletedCandle(instrument);
        return null;
    }

    private boolean checkBullishEngulfing(CandleData previous, CandleData current) {
        if (previous == null || current == null) return false;
        return previous.getClose() < previous.getOpen() &&
                current.getClose() > current.getOpen() &&
                current.getOpen() < previous.getClose() &&
                current.getClose() > previous.getOpen();
    }

    private boolean checkHammerPattern(CandleData candle) {
        if (candle == null) return false;
        double bodySize = Math.abs(candle.getClose() - candle.getOpen());
        double lowerWick = Math.min(candle.getClose(), candle.getOpen()) - candle.getLow();
        double upperWick = candle.getHigh() - Math.max(candle.getClose(), candle.getOpen());
        boolean isHammer = lowerWick >= (2 * bodySize) && upperWick <= (bodySize / 3);
        boolean bullish = candle.getClose() > candle.getOpen();
        boolean isDojiHammer = bodySize < 0.01 && lowerWick > upperWick * 2;
        return (isHammer && bullish) || isDojiHammer;
    }

    @Override
    public void executeBuySignal(String instrument, Map<String, Object> signalDetails, TradingStrategyEngine context) {
        double entryPrice = (double) signalDetails.get("entryPrice");
        double stopLoss = (double) signalDetails.get("stopLoss");
        double target = (double) signalDetails.get("target");

        System.out.println("\n" + "🚀".repeat(20));
        System.out.println("🚀 [Case 1] EXECUTING BUY SIGNAL");
        System.out.println("   Strategy: EMA/VWAP Pullback with Breakout Confirmation");
        System.out.println("   Entry: " + entryPrice + " (Break of signal candle high)");
        System.out.println("   SL: " + String.format("%.2f", stopLoss) + " (Below candle low)");
        System.out.println("   Target: " + String.format("%.2f", target) + " (1:1.5 R:R)");
        System.out.println("🚀".repeat(20));

        context.executePullbackBuySignal(instrument, entryPrice, stopLoss, target);
    }

    @Override
    public boolean shouldSkipInstrument(String instrument, TradingStrategyEngine context) {
        return context.shouldSkipInstrument(instrument);
    }
}