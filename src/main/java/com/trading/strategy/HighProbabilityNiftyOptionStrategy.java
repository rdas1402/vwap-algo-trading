package com.trading.strategy;

import com.trading.config.AppConfig;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.HistoricalData;
import com.zerodhatech.models.Quote;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class HighProbabilityNiftyOptionStrategy implements TradingStrategy {

    private static final String NIFTY_SYMBOL = "NSE:NIFTY 50";
    private static final int EMA_PERIOD = 9;
    private static final int PREMIUM_BREAKOUT_LOOKBACK = 4;
    private static final int ATR_PERIOD = 10;
    private static final int ADX_PERIOD = 14;

    private final Map<String, Double> lastSeenOi = new HashMap<>();
    private DailyLevels cachedDailyLevels;
    private String cachedDailyLevelsDate;

    @Override
    public int getPriority() {
        return 1;
    }

    @Override
    public String getStrategyName() {
        return "Case 1: High Probability NIFTY Options Stack";
    }

    @Override
    public Map<String, String> findInstruments(TradingStrategyEngine context) throws Exception, KiteException {
        Map<String, String> options = new HashMap<>();
        double targetPremium = AppConfig.getHighProbabilityTargetPremium();
        double tolerance = AppConfig.getHighProbabilityPremiumTolerance();
        double niftySpot = context.getNiftySpotPrice();

        System.out.println("[HighProb] NIFTY spot: " + String.format("%.2f", niftySpot));
        System.out.println("[HighProb] Target premium: " + targetPremium + " +/- " + tolerance);

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
            boolean isCall = instrument.toUpperCase(Locale.ROOT).endsWith("CE");
            if (!isCall && !instrument.toUpperCase(Locale.ROOT).endsWith("PE")) {
                return result;
            }

            if (isInNoTradeWindow()) {
                System.out.println("[HighProb] Skipping " + instrument + " - no-trade window is active");
                return result;
            }

            if (context.hasOpenPosition(instrument)) {
                return result;
            }

            RealTimeCandleBuilder builder = context.getRealTimeCandleBuilder();
            List<CandleData> niftyCandles = CandleHistoryManager.getInstance().getHistory(NIFTY_SYMBOL);
            List<CandleData> optionCandles = CandleHistoryManager.getInstance().getHistory(instrument);

            if (niftyCandles.size() < 5 || optionCandles.size() < 5) {
                System.out.println("[HighProb] Need at least 5 NIFTY and option candles. NIFTY="
                        + niftyCandles.size() + ", option=" + optionCandles.size());
                return result;
            }

            Map<String, Quote> quotes = context.getKiteConnect().getQuote(new String[]{NIFTY_SYMBOL, instrument});
            Quote niftyQuote = quotes.get(NIFTY_SYMBOL);
            Quote optionQuote = quotes.get(instrument);
            if (niftyQuote == null || optionQuote == null || optionQuote.lastPrice <= 0) {
                System.out.println("[HighProb] Missing live quote for " + instrument);
                return result;
            }

            CandleData lastNifty = last(niftyCandles);
            CandleData lastOption = last(optionCandles);
            CandleData previousOption = previous(optionCandles);
            if (lastNifty == null || lastOption == null || previousOption == null) {
                return result;
            }

            if (!isTradableRegime(niftyCandles)) {
                System.out.println("[HighProb] Skipping " + instrument + " - NIFTY ATR/ADX regime is weak");
                return result;
            }

            DailyLevels levels = getDailyLevels(context);
            double dayOpen = niftyQuote.ohlc != null ? niftyQuote.ohlc.open : 0;
            OpeningRange openingRange = calculateOpeningRange(niftyCandles);
            boolean oiConfirmed = isOiConfirmed(instrument, optionQuote);
            if (!oiConfirmed) {
                System.out.println("[HighProb] Skipping " + instrument + " - OI confirmation failed");
                return result;
            }

            boolean niftyDirectional = isNiftyDirectional(isCall, lastNifty, niftyQuote, levels, dayOpen);
            if (!niftyDirectional) {
                System.out.println("[HighProb] Skipping " + instrument + " - NIFTY direction not aligned");
                return result;
            }

            boolean premiumBreakout = isPremiumBreakout(optionCandles, isCall);
            boolean orbRetest = isOpeningRangeRetest(niftyCandles, openingRange, isCall);
            boolean vwapContinuation = isVwapTrendContinuation(niftyCandles, optionCandles, isCall);
            boolean previousDayOrCprBreakout = isPreviousDayOrCprBreakout(lastNifty, niftyQuote, levels, dayOpen, isCall);

            SetupCandidate candidate = chooseCandidate(
                    orbRetest, vwapContinuation, previousDayOrCprBreakout, premiumBreakout);
            if (candidate == null) {
                System.out.println("[HighProb] No high-probability setup confirmed for " + instrument);
                return result;
            }

            TradeLevels trade = buildTradeLevels(optionCandles, optionQuote.lastPrice);
            if (trade == null) {
                System.out.println("[HighProb] Invalid trade levels for " + instrument);
                return result;
            }

            System.out.println("[HighProb] Setup confirmed: " + candidate.pattern);
            System.out.println("   Entry trigger: " + String.format("%.2f", trade.entry));
            System.out.println("   Stop loss    : " + String.format("%.2f", trade.stopLoss));
            System.out.println("   Target       : " + String.format("%.2f", trade.target));
            System.out.println("   Reason       : " + candidate.reason);

            result.put("signal", true);
            result.put("pattern", candidate.pattern);
            result.put("entryPrice", trade.entry);
            result.put("stopLoss", trade.stopLoss);
            result.put("target", trade.target);
            result.put("message", candidate.reason);

        } catch (Exception | KiteException e) {
            System.err.println("[HighProb] Error analyzing " + instrument + ": " + e.getMessage());
        }

        return result;
    }

    @Override
    public void executeBuySignal(String instrument, Map<String, Object> signalDetails, TradingStrategyEngine context) {
        String pattern = (String) signalDetails.get("pattern");
        double entry = getDouble(signalDetails, "entryPrice");
        double stopLoss = getDouble(signalDetails, "stopLoss");
        double target = getDouble(signalDetails, "target");
        context.executeStructuredBuySignal(instrument, pattern, entry, stopLoss, target);
    }

    @Override
    public boolean shouldSkipInstrument(String instrument, TradingStrategyEngine context) {
        return context.shouldSkipInstrument(instrument);
    }

    private SetupCandidate chooseCandidate(boolean orbRetest, boolean vwapContinuation,
                                           boolean previousDayOrCprBreakout, boolean premiumBreakout) {
        if (orbRetest && premiumBreakout) {
            return new SetupCandidate("orb_retest_premium_breakout",
                    "Opening range breakout retest confirmed with option premium breakout");
        }
        if (previousDayOrCprBreakout && premiumBreakout) {
            return new SetupCandidate("pdh_pdl_cpr_premium_breakout",
                    "Previous-day/CPR breakout confirmed with option premium breakout");
        }
        if (vwapContinuation) {
            return new SetupCandidate("vwap_trend_continuation",
                    "NIFTY trend and option premium continued from VWAP/EMA pullback");
        }
        if (premiumBreakout) {
            return new SetupCandidate("premium_breakout",
                    "Option premium broke recent structure in the NIFTY trend direction");
        }
        return null;
    }

    private boolean isNiftyDirectional(boolean isCall, CandleData lastNifty, Quote niftyQuote,
                                       DailyLevels levels, double dayOpen) {
        double lastClose = lastNifty.getClose();
        double vwap = lastNifty.getVWAP();
        double previousClose = niftyQuote.ohlc != null ? niftyQuote.ohlc.close : 0;

        boolean aboveOpen = dayOpen > 0 && lastClose > dayOpen;
        boolean belowOpen = dayOpen > 0 && lastClose < dayOpen;
        boolean abovePrevClose = previousClose > 0 && lastClose >= previousClose;
        boolean belowPrevClose = previousClose > 0 && lastClose <= previousClose;
        boolean aboveVwap = vwap > 0 && lastClose > vwap;
        boolean belowVwap = vwap > 0 && lastClose < vwap;

        if (levels != null) {
            aboveOpen = aboveOpen || lastClose > levels.cprTop;
            belowOpen = belowOpen || lastClose < levels.cprBottom;
        }

        return isCall
                ? aboveVwap && aboveOpen && abovePrevClose
                : belowVwap && belowOpen && belowPrevClose;
    }

    private boolean isTradableRegime(List<CandleData> niftyCandles) {
        double atrPercent = calculateAtrPercent(niftyCandles);
        double adx = calculateAdx(niftyCandles);
        boolean atrOk = atrPercent >= AppConfig.getHighProbabilityMinAtrPercent();
        boolean adxOk = adx == 0 || adx >= AppConfig.getHighProbabilityMinAdx();

        System.out.println("[HighProb] Regime filter: ATR%=" + String.format("%.3f", atrPercent)
                + ", ADX=" + String.format("%.2f", adx)
                + ", ATR OK=" + atrOk + ", ADX OK=" + adxOk);
        return atrOk && adxOk;
    }

    private boolean isOpeningRangeRetest(List<CandleData> niftyCandles, OpeningRange range, boolean isCall) {
        if (range == null) return false;

        CandleData lastCandle = last(niftyCandles);
        if (lastCandle == null || !lastCandle.getTimestamp().after(range.endTime)) {
            return false;
        }

        double level = isCall ? range.high : range.low;
        double buffer = Math.max(3.0, level * 0.0005);
        boolean brokeRange = false;
        for (CandleData candle : niftyCandles) {
            if (!candle.getTimestamp().after(range.endTime)) continue;
            if (isCall && candle.getClose() > range.high) brokeRange = true;
            if (!isCall && candle.getClose() < range.low) brokeRange = true;
        }

        boolean retestHold = isCall
                ? lastCandle.getLow() <= level + buffer && lastCandle.getClose() > level
                : lastCandle.getHigh() >= level - buffer && lastCandle.getClose() < level;

        return brokeRange && retestHold;
    }

    private boolean isVwapTrendContinuation(List<CandleData> niftyCandles, List<CandleData> optionCandles, boolean isCall) {
        CandleData lastNifty = last(niftyCandles);
        CandleData lastOption = last(optionCandles);
        CandleData previousOption = previous(optionCandles);
        if (lastNifty == null || lastOption == null || previousOption == null) return false;

        double optionEma = calculateEma(optionCandles, EMA_PERIOD);
        double pullbackLevel = Math.max(lastOption.getVWAP(), optionEma);
        double pullbackBuffer = Math.max(0.30, lastOption.getClose() * 0.006);
        boolean optionHeldPullback = lastOption.getLow() <= pullbackLevel + pullbackBuffer
                && lastOption.getClose() > pullbackLevel
                && lastOption.getClose() > previousOption.getClose();

        boolean niftyAligned = isCall
                ? lastNifty.getClose() > lastNifty.getVWAP()
                : lastNifty.getClose() < lastNifty.getVWAP();

        return niftyAligned && optionHeldPullback;
    }

    private boolean isPreviousDayOrCprBreakout(CandleData lastNifty, Quote niftyQuote,
                                               DailyLevels levels, double dayOpen, boolean isCall) {
        if (lastNifty == null || levels == null) return false;
        double close = lastNifty.getClose();
        double buffer = Math.max(5.0, close * 0.0004);
        boolean cprBullish = dayOpen > levels.cprTop && close > levels.cprTop;
        boolean cprBearish = dayOpen < levels.cprBottom && close < levels.cprBottom;
        boolean prevBreakBullish = close > levels.previousHigh + buffer;
        boolean prevBreakBearish = close < levels.previousLow - buffer;

        if (niftyQuote != null && niftyQuote.ohlc != null) {
            prevBreakBullish = prevBreakBullish || niftyQuote.lastPrice > levels.previousHigh + buffer;
            prevBreakBearish = prevBreakBearish || niftyQuote.lastPrice < levels.previousLow - buffer;
        }

        return isCall ? (cprBullish || prevBreakBullish) : (cprBearish || prevBreakBearish);
    }

    private boolean isPremiumBreakout(List<CandleData> optionCandles, boolean isCall) {
        if (optionCandles.size() < PREMIUM_BREAKOUT_LOOKBACK + 1) return false;

        CandleData lastCandle = last(optionCandles);
        double previousHigh = 0;
        int start = Math.max(0, optionCandles.size() - PREMIUM_BREAKOUT_LOOKBACK - 1);
        for (int i = start; i < optionCandles.size() - 1; i++) {
            previousHigh = Math.max(previousHigh, optionCandles.get(i).getHigh());
        }

        boolean premiumBreakout = lastCandle.getClose() > previousHigh
                && lastCandle.getClose() > lastCandle.getVWAP();
        System.out.println("[HighProb] Premium breakout=" + premiumBreakout
                + " close=" + String.format("%.2f", lastCandle.getClose())
                + " priorHigh=" + String.format("%.2f", previousHigh));
        return premiumBreakout;
    }

    private boolean isOiConfirmed(String instrument, Quote optionQuote) {
        if (optionQuote == null || optionQuote.oi <= 0) {
            return true;
        }

        Double previousOi = lastSeenOi.put(instrument, optionQuote.oi);
        if (previousOi == null || previousOi <= 0) {
            return true;
        }

        double oiChangePercent = ((optionQuote.oi - previousOi) / previousOi) * 100.0;
        System.out.println("[HighProb] OI change for " + instrument + ": "
                + String.format("%.2f", oiChangePercent) + "%");
        return oiChangePercent >= -1.0;
    }

    private TradeLevels buildTradeLevels(List<CandleData> optionCandles, double livePrice) {
        CandleData lastCandle = last(optionCandles);
        if (lastCandle == null) return null;

        double entry = Math.max(lastCandle.getHigh() + 0.05, livePrice);
        double recentLow = lastCandle.getLow();
        int lookback = Math.min(3, optionCandles.size());
        for (int i = optionCandles.size() - lookback; i < optionCandles.size(); i++) {
            recentLow = Math.min(recentLow, optionCandles.get(i).getLow());
        }

        double stopBuffer = Math.max(0.25, entry * AppConfig.getHighProbabilityStopBufferPercent() / 100.0);
        double stopLoss = Math.max(0.05, recentLow - stopBuffer);
        if (stopLoss >= entry) {
            return null;
        }

        double risk = entry - stopLoss;
        double target = entry + (risk * Math.max(2.0, AppConfig.getHighProbabilityMinRewardRisk()));
        return new TradeLevels(entry, stopLoss, target);
    }

    private OpeningRange calculateOpeningRange(List<CandleData> niftyCandles) {
        List<CandleData> today = todayCandles(niftyCandles);
        if (today.isEmpty()) return null;

        Calendar start = Calendar.getInstance();
        start.setTime(today.get(0).getTimestamp());
        start.set(Calendar.HOUR_OF_DAY, 9);
        start.set(Calendar.MINUTE, 15);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);

        Calendar end = (Calendar) start.clone();
        end.add(Calendar.MINUTE, AppConfig.getHighProbabilityOpeningRangeMinutes());

        double high = 0;
        double low = Double.MAX_VALUE;
        int count = 0;
        for (CandleData candle : today) {
            Date timestamp = candle.getTimestamp();
            if (!timestamp.before(start.getTime()) && timestamp.before(end.getTime())) {
                high = Math.max(high, candle.getHigh());
                low = Math.min(low, candle.getLow());
                count++;
            }
        }

        if (count < Math.max(1, AppConfig.getHighProbabilityOpeningRangeMinutes() / 5)
                || high <= 0 || low == Double.MAX_VALUE) {
            return null;
        }

        return new OpeningRange(high, low, end.getTime());
    }

    private DailyLevels getDailyLevels(TradingStrategyEngine context) {
        try {
            String today = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
            if (cachedDailyLevels != null && today.equals(cachedDailyLevelsDate)) {
                return cachedDailyLevels;
            }

            Long token = context.getInstrumentToken(NIFTY_SYMBOL);
            if (token == null) {
                return null;
            }

            Calendar from = Calendar.getInstance();
            from.add(Calendar.DAY_OF_MONTH, -10);
            Calendar to = Calendar.getInstance();
            HistoricalData history = context.getKiteConnect().getHistoricalData(
                    from.getTime(), to.getTime(), token.toString(), "day", false, false);
            if (history == null || history.dataArrayList == null || history.dataArrayList.isEmpty()) {
                return null;
            }

            HistoricalData previousDay = null;
            for (HistoricalData day : history.dataArrayList) {
                String candleDate = extractDate(day.timeStamp);
                if (candleDate != null && candleDate.compareTo(today) < 0) {
                    previousDay = day;
                }
            }

            if (previousDay == null || previousDay.high <= 0 || previousDay.low <= 0 || previousDay.close <= 0) {
                return null;
            }

            double pivot = (previousDay.high + previousDay.low + previousDay.close) / 3.0;
            double bc = (previousDay.high + previousDay.low) / 2.0;
            double tc = (pivot - bc) + pivot;
            cachedDailyLevels = new DailyLevels(previousDay.high, previousDay.low, previousDay.close,
                    Math.max(tc, bc), Math.min(tc, bc), pivot);
            cachedDailyLevelsDate = today;

            System.out.println("[HighProb] Previous day levels: H=" + String.format("%.2f", previousDay.high)
                    + " L=" + String.format("%.2f", previousDay.low)
                    + " C=" + String.format("%.2f", previousDay.close)
                    + " CPR Top=" + String.format("%.2f", cachedDailyLevels.cprTop)
                    + " CPR Bottom=" + String.format("%.2f", cachedDailyLevels.cprBottom));
            return cachedDailyLevels;
        } catch (Exception | KiteException e) {
            System.err.println("[HighProb] Could not load previous-day/CPR levels: " + e.getMessage());
            return null;
        }
    }

    private double calculateAtrPercent(List<CandleData> candles) {
        if (candles.size() < 2) return 0;
        int start = Math.max(1, candles.size() - ATR_PERIOD);
        double sum = 0;
        int count = 0;
        for (int i = start; i < candles.size(); i++) {
            CandleData current = candles.get(i);
            CandleData previous = candles.get(i - 1);
            double tr = trueRange(current, previous);
            if (tr > 0) {
                sum += tr;
                count++;
            }
        }
        double close = last(candles).getClose();
        return count > 0 && close > 0 ? (sum / count) / close * 100.0 : 0;
    }

    private double calculateAdx(List<CandleData> candles) {
        if (candles.size() < ADX_PERIOD + 1) return 0;
        int start = candles.size() - ADX_PERIOD;
        double trSum = 0;
        double plusDmSum = 0;
        double minusDmSum = 0;

        for (int i = start; i < candles.size(); i++) {
            CandleData current = candles.get(i);
            CandleData previous = candles.get(i - 1);
            trSum += trueRange(current, previous);

            double upMove = current.getHigh() - previous.getHigh();
            double downMove = previous.getLow() - current.getLow();
            plusDmSum += (upMove > downMove && upMove > 0) ? upMove : 0;
            minusDmSum += (downMove > upMove && downMove > 0) ? downMove : 0;
        }

        if (trSum <= 0) return 0;
        double plusDi = 100.0 * (plusDmSum / trSum);
        double minusDi = 100.0 * (minusDmSum / trSum);
        double denominator = plusDi + minusDi;
        return denominator > 0 ? 100.0 * Math.abs(plusDi - minusDi) / denominator : 0;
    }

    private double trueRange(CandleData current, CandleData previous) {
        double tr1 = current.getHigh() - current.getLow();
        double tr2 = Math.abs(current.getHigh() - previous.getClose());
        double tr3 = Math.abs(current.getLow() - previous.getClose());
        return Math.max(tr1, Math.max(tr2, tr3));
    }

    private double calculateEma(List<CandleData> candles, int period) {
        if (candles.isEmpty()) return 0;
        int start = Math.max(0, candles.size() - period);
        double multiplier = 2.0 / (period + 1);
        double ema = candles.get(start).getClose();
        for (int i = start + 1; i < candles.size(); i++) {
            ema = ((candles.get(i).getClose() - ema) * multiplier) + ema;
        }
        return ema;
    }

    private List<CandleData> todayCandles(List<CandleData> candles) {
        if (candles.isEmpty()) return Collections.emptyList();
        String today = new SimpleDateFormat("yyyyMMdd").format(new Date());
        List<CandleData> result = new ArrayList<>();
        SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMdd");
        for (CandleData candle : candles) {
            if (today.equals(formatter.format(candle.getTimestamp()))) {
                result.add(candle);
            }
        }
        return result;
    }

    private boolean isInNoTradeWindow() {
        String windows = AppConfig.getHighProbabilityNoTradeWindows();
        if (windows == null || windows.trim().isEmpty()) return false;

        Calendar now = Calendar.getInstance();
        int currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        for (String window : windows.split(",")) {
            String[] parts = window.trim().split("-");
            if (parts.length != 2) continue;
            int start = parseMinutes(parts[0]);
            int end = parseMinutes(parts[1]);
            if (start >= 0 && end >= 0 && currentMinutes >= start && currentMinutes <= end) {
                return true;
            }
        }
        return false;
    }

    private int parseMinutes(String time) {
        String[] parts = time.trim().split(":");
        if (parts.length != 2) return -1;
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }

    private String extractDate(String timestamp) {
        if (timestamp == null || timestamp.length() < 10) return null;
        return timestamp.substring(0, 10);
    }

    private CandleData last(List<CandleData> candles) {
        return candles.isEmpty() ? null : candles.get(candles.size() - 1);
    }

    private CandleData previous(List<CandleData> candles) {
        return candles.size() < 2 ? null : candles.get(candles.size() - 2);
    }

    private double getDouble(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value instanceof Number ? ((Number) value).doubleValue() : 0;
    }

    private static class DailyLevels {
        final double previousHigh;
        final double previousLow;
        final double previousClose;
        final double cprTop;
        final double cprBottom;
        final double pivot;

        DailyLevels(double previousHigh, double previousLow, double previousClose,
                    double cprTop, double cprBottom, double pivot) {
            this.previousHigh = previousHigh;
            this.previousLow = previousLow;
            this.previousClose = previousClose;
            this.cprTop = cprTop;
            this.cprBottom = cprBottom;
            this.pivot = pivot;
        }
    }

    private static class OpeningRange {
        final double high;
        final double low;
        final Date endTime;

        OpeningRange(double high, double low, Date endTime) {
            this.high = high;
            this.low = low;
            this.endTime = endTime;
        }
    }

    private static class SetupCandidate {
        final String pattern;
        final String reason;

        SetupCandidate(String pattern, String reason) {
            this.pattern = pattern;
            this.reason = reason;
        }
    }

    private static class TradeLevels {
        final double entry;
        final double stopLoss;
        final double target;

        TradeLevels(double entry, double stopLoss, double target) {
            this.entry = entry;
            this.stopLoss = stopLoss;
            this.target = target;
        }
    }
}
