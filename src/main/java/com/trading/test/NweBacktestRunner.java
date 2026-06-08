package com.trading.test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.trading.config.AppConfig;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Instrument;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

public class NweBacktestRunner {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String NIFTY_SPOT = "NSE:NIFTY 50";
    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final int NWE_WINDOW = 500;
    private static final int DAYS_TO_REPORT = 10;

    private final KiteConnect kiteConnect;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final Map<String, Long> instrumentTokenCache = new HashMap<>();
    private final List<Instrument> niftyOptions = new ArrayList<>();
    private final Map<String, List<HistoricalCandle>> optionCandleCache = new HashMap<>();
    private final List<Double> closeHistory = new ArrayList<>();
    private final List<DayResult> dayResults = new ArrayList<>();
    private final Set<String> optionCacheMisses = new HashSet<>();

    public NweBacktestRunner() {
        kiteConnect = new KiteConnect(AppConfig.getApiKey());
        kiteConnect.setAccessToken(AppConfig.getAccessToken());
        try {
            kiteConnect.getClass().getMethod("setRoot", String.class).invoke(kiteConnect, AppConfig.getBaseUrl());
        } catch (Exception ignored) {
            System.out.println("Kite setRoot not available; using default root");
        }
    }

    public static void main(String[] args) {
        try {
            new NweBacktestRunner().run();
        } catch (Exception | KiteException e) {
            System.err.println("NWE backtest failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private void run() throws Exception, KiteException {
        System.out.println("=".repeat(110));
        System.out.println("NWE OPTIONS BACKTEST - LAST " + DAYS_TO_REPORT + " COMPLETED TRADING SESSIONS");
        System.out.println("=".repeat(110));
        System.out.println("Config: bandwidth=" + format(AppConfig.getNweBandwidth())
                + " multiplier=" + format(AppConfig.getNweMultiplier())
                + " candleNear=" + format(AppConfig.getNweCandleNearThresholdPoints())
                + " liveTrigger=" + format(AppConfig.getNweLiveTriggerThresholdPoints())
                + " optionTargetPremium>=" + format(AppConfig.getNweOptionTargetPremium())
                + " stop=" + format(AppConfig.getNweOptionStopLossPoints())
                + " target=" + format(AppConfig.getNweOptionTargetPoints())
                + " buyWindow=" + AppConfig.getNweBuyStartTime() + "-" + AppConfig.getNweBuyEndTime());

        loadInstrumentMasters();

        LocalDate today = LocalDate.now(IST);
        LocalDate from = today.minusDays(70);
        List<HistoricalCandle> niftyCandles = fetchHistoricalCandles(getInstrumentToken(NIFTY_SPOT), from, today);
        niftyCandles = niftyCandles.stream()
                .filter(candle -> isWithinMarketDataWindow(candle.time.toLocalTime()))
                .sorted(Comparator.comparing(candle -> candle.time))
                .collect(Collectors.toList());

        if (niftyCandles.isEmpty()) {
            throw new RuntimeException("No NIFTY 5-minute candles returned for " + from + " to " + today);
        }

        List<LocalDate> sessions = selectBacktestSessions(niftyCandles);
        if (sessions.isEmpty()) {
            throw new RuntimeException("No completed trading sessions found in fetched NIFTY data");
        }

        System.out.println("Sessions: " + sessions.stream().map(LocalDate::toString).collect(Collectors.joining(", ")));
        System.out.println();

        Map<LocalDate, List<HistoricalCandle>> candlesByDate = niftyCandles.stream()
                .collect(Collectors.groupingBy(candle -> candle.time.toLocalDate(), LinkedHashMap::new, Collectors.toList()));

        Set<LocalDate> reportDates = new HashSet<>(sessions);
        for (Map.Entry<LocalDate, List<HistoricalCandle>> entry : candlesByDate.entrySet()) {
            LocalDate date = entry.getKey();
            if (reportDates.contains(date)) {
                backtestSession(date, entry.getValue());
            } else if (date.isBefore(sessions.get(0))) {
                seedHistoryOnly(entry.getValue());
            }
        }

        printResults();
    }

    private void loadInstrumentMasters() throws KiteException, IOException {
        for (Instrument instrument : kiteConnect.getInstruments("NSE")) {
            instrumentTokenCache.put("NSE:" + instrument.tradingsymbol, instrument.instrument_token);
        }
        for (Instrument instrument : kiteConnect.getInstruments("NFO")) {
            if ("NIFTY".equalsIgnoreCase(instrument.name)
                    && ("CE".equalsIgnoreCase(instrument.instrument_type)
                    || "PE".equalsIgnoreCase(instrument.instrument_type))) {
                niftyOptions.add(instrument);
                instrumentTokenCache.put("NFO:" + instrument.tradingsymbol, instrument.instrument_token);
            }
        }
        System.out.println("Loaded instruments: NIFTY options=" + niftyOptions.size());
    }

    private void seedHistoryOnly(List<HistoricalCandle> candles) {
        for (HistoricalCandle candle : candles) {
            closeHistory.add(candle.close);
            trimCloseHistory();
        }
    }

    private void backtestSession(LocalDate date, List<HistoricalCandle> candles) throws Exception {
        DayResult result = new DayResult(date);
        ActiveMonitor monitor = null;
        OpenTrade openTrade = null;

        for (HistoricalCandle candle : candles) {
            if (openTrade != null) {
                ExitResult exit = checkOptionExit(openTrade, candle.time);
                if (exit != null) {
                    result.trades.add(ClosedTrade.from(openTrade, exit));
                    openTrade = null;
                    monitor = null;
                }
            }

            if (openTrade == null && monitor != null) {
                if (!isBuySignalWindow(candle.time.toLocalTime())) {
                    monitor = null;
                } else if (monitor.isTriggeredBy(candle)) {
                    OpenTrade trade = tryOpenTrade(monitor, candle);
                    if (trade != null) {
                        openTrade = trade;
                        monitor = null;
                    }
                }
            }

            closeHistory.add(candle.close);
            trimCloseHistory();
            NweBand band = calculateCurrentNweBand();

            if (openTrade == null && monitor == null && isBuySignalWindow(candle.time.toLocalTime())) {
                monitor = createMonitorIfSignal(candle, band);
                if (monitor != null) {
                    result.signals++;
                }
            }
        }

        if (openTrade != null) {
            HistoricalCandle last = candles.get(candles.size() - 1);
            HistoricalCandle optionCandle = getOptionCandle(openTrade.symbol, date, last.time);
            double exit = optionCandle != null ? optionCandle.close : openTrade.entryPrice;
            result.trades.add(ClosedTrade.marketClose(openTrade, last.time, exit));
        }

        dayResults.add(result);
        printDay(result);
    }

    private ActiveMonitor createMonitorIfSignal(HistoricalCandle candle, NweBand band) {
        double upperDiff = band.upper - candle.high;
        double lowerDiff = candle.low - band.lower;
        boolean nearUpper = upperDiff <= AppConfig.getNweCandleNearThresholdPoints();
        boolean nearLower = lowerDiff <= AppConfig.getNweCandleNearThresholdPoints();

        if (!nearUpper && !nearLower) {
            return null;
        }

        if (nearUpper && (!nearLower || Math.abs(upperDiff) <= Math.abs(lowerDiff))) {
            return new ActiveMonitor(OptionSide.PE, band.upper, candle, band, upperDiff, lowerDiff,
                    "NIFTY high near NWE upper, so buy PE for mean reversion");
        }
        return new ActiveMonitor(OptionSide.CE, band.lower, candle, band, upperDiff, lowerDiff,
                "NIFTY low near NWE lower, so buy CE for mean reversion");
    }

    private OpenTrade tryOpenTrade(ActiveMonitor monitor, HistoricalCandle triggerCandle) throws Exception {
        SelectedOption selected = findHistoricalOptionAboveTargetPremium(
                monitor.side,
                triggerCandle.close,
                triggerCandle.time.toLocalDate(),
                triggerCandle.time
        );
        if (selected == null) {
            return null;
        }

        double entry = roundUpToTick(selected.price * (1.0 + AppConfig.getMarketableLimitBufferPercent() / 100.0));
        double stop = roundToTick(entry - AppConfig.getNweOptionStopLossPoints());
        double target = roundToTick(entry + AppConfig.getNweOptionTargetPoints());
        return new OpenTrade(selected.symbol, monitor.side, triggerCandle.time, entry, stop, target,
                monitor.bandLevel, selected.price, monitor, triggerCandle);
    }

    private SelectedOption findHistoricalOptionAboveTargetPremium(OptionSide side, double spot,
                                                                  LocalDate date, LocalDateTime time)
            throws Exception {
        Date expiry = getNearestOptionExpiry(date);
        if (expiry == null) {
            return null;
        }

        double roundedSpot = Math.round(spot / 50.0) * 50.0;
        List<Instrument> candidates = new ArrayList<>();
        for (Instrument instrument : niftyOptions) {
            if (!side.name().equalsIgnoreCase(instrument.instrument_type) || !sameDate(expiry, instrument.expiry)) {
                continue;
            }
            double strike;
            try {
                strike = Double.parseDouble(instrument.strike);
            } catch (NumberFormatException e) {
                continue;
            }
            if (Math.abs(strike - roundedSpot) <= 1200) {
                candidates.add(instrument);
            }
        }

        candidates.sort(Comparator.comparingDouble(instrument -> {
            try {
                return Math.abs(Double.parseDouble(instrument.strike) - roundedSpot);
            } catch (NumberFormatException e) {
                return Double.MAX_VALUE;
            }
        }));

        SelectedOption best = null;
        double targetPremium = AppConfig.getNweOptionTargetPremium();
        for (Instrument instrument : candidates) {
            String symbol = "NFO:" + instrument.tradingsymbol;
            HistoricalCandle optionCandle = getOptionCandle(symbol, date, time);
            if (optionCandle == null || optionCandle.close < targetPremium) {
                continue;
            }
            if (best == null || optionCandle.close < best.price) {
                best = new SelectedOption(symbol, optionCandle.close);
            }
        }
        return best;
    }

    private ExitResult checkOptionExit(OpenTrade trade, LocalDateTime niftyCandleTime) throws Exception {
        HistoricalCandle optionCandle = getOptionCandle(trade.symbol, niftyCandleTime.toLocalDate(), niftyCandleTime);
        if (optionCandle == null) {
            return null;
        }

        boolean stopHit = optionCandle.low <= trade.stopLoss;
        boolean targetHit = optionCandle.high >= trade.target;
        if (stopHit && targetHit) {
            return new ExitResult(niftyCandleTime, trade.stopLoss, "STOP_LOSS_AMBIGUOUS", -AppConfig.getNweOptionStopLossPoints());
        }
        if (stopHit) {
            return new ExitResult(niftyCandleTime, trade.stopLoss, "STOP_LOSS", -AppConfig.getNweOptionStopLossPoints());
        }
        if (targetHit) {
            return new ExitResult(niftyCandleTime, trade.target, "TARGET", AppConfig.getNweOptionTargetPoints());
        }
        if (niftyCandleTime.toLocalTime().isAfter(LocalTime.of(15, 25))) {
            double points = roundToTick(optionCandle.close - trade.entryPrice);
            return new ExitResult(niftyCandleTime, optionCandle.close, "MARKET_CLOSE", points);
        }
        return null;
    }

    private HistoricalCandle getOptionCandle(String symbol, LocalDate date, LocalDateTime time) throws Exception {
        String cacheKey = symbol + "|" + date;
        List<HistoricalCandle> candles = optionCandleCache.get(cacheKey);
        if (candles == null && !optionCacheMisses.contains(cacheKey)) {
            Long token = getInstrumentToken(symbol);
            if (token == null) {
                optionCacheMisses.add(cacheKey);
                return null;
            }
            candles = fetchHistoricalCandles(token, date, date).stream()
                    .filter(candle -> isWithinMarketDataWindow(candle.time.toLocalTime()))
                    .collect(Collectors.toList());
            if (candles.isEmpty()) {
                optionCacheMisses.add(cacheKey);
                return null;
            }
            optionCandleCache.put(cacheKey, candles);
            sleepQuietly(120);
        }
        if (candles == null) {
            return null;
        }
        for (HistoricalCandle candle : candles) {
            if (candle.time.equals(time)) {
                return candle;
            }
        }
        return null;
    }

    private List<LocalDate> selectBacktestSessions(List<HistoricalCandle> niftyCandles) {
        LocalDate today = LocalDate.now(IST);
        boolean includeToday = LocalTime.now(IST).isAfter(LocalTime.of(15, 35));
        TreeSet<LocalDate> dates = new TreeSet<>();
        for (HistoricalCandle candle : niftyCandles) {
            LocalDate date = candle.time.toLocalDate();
            if (!includeToday && date.equals(today)) {
                continue;
            }
            dates.add(date);
        }

        List<LocalDate> sorted = new ArrayList<>(dates);
        int fromIndex = Math.max(0, sorted.size() - DAYS_TO_REPORT);
        return sorted.subList(fromIndex, sorted.size());
    }

    private List<HistoricalCandle> fetchHistoricalCandles(Long token, LocalDate fromDate, LocalDate toDate)
            throws Exception {
        if (token == null) {
            return Collections.emptyList();
        }

        LocalDateTime from = LocalDateTime.of(fromDate, LocalTime.of(9, 15));
        LocalDateTime to = LocalDateTime.of(toDate, LocalTime.of(15, 30));
        String path = "/instruments/historical/" + token + "/5minute"
                + "?from=" + encode(from.format(DATE_TIME_FORMAT))
                + "&to=" + encode(to.format(DATE_TIME_FORMAT));

        JsonObject root = kiteGetJson(path);
        JsonArray rows = root.getAsJsonObject("data").getAsJsonArray("candles");
        List<HistoricalCandle> candles = new ArrayList<>();
        for (JsonElement element : rows) {
            JsonArray row = element.getAsJsonArray();
            candles.add(new HistoricalCandle(
                    parseKiteTime(row.get(0).getAsString()),
                    row.get(1).getAsDouble(),
                    row.get(2).getAsDouble(),
                    row.get(3).getAsDouble(),
                    row.get(4).getAsDouble()
            ));
        }
        candles.sort(Comparator.comparing(candle -> candle.time));
        return candles;
    }

    private JsonObject kiteGetJson(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(AppConfig.getBaseUrl().replaceAll("/$", "") + path))
                .timeout(Duration.ofSeconds(30))
                .header("X-Kite-Version", "3")
                .header("Authorization", "token " + AppConfig.getApiKey() + ":" + AppConfig.getAccessToken())
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException("Kite HTTP " + response.statusCode() + ": " + response.body());
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private Long getInstrumentToken(String fullSymbol) {
        return instrumentTokenCache.get(fullSymbol);
    }

    private Date getNearestOptionExpiry(LocalDate sessionDate) {
        return niftyOptions.stream()
                .map(instrument -> instrument.expiry)
                .filter(Objects::nonNull)
                .filter(expiry -> !toLocalDate(expiry).isBefore(sessionDate))
                .min(Date::compareTo)
                .orElse(null);
    }

    private NweBand calculateCurrentNweBand() {
        List<Double> window = closeHistory.size() <= NWE_WINDOW
                ? new ArrayList<>(closeHistory)
                : new ArrayList<>(closeHistory.subList(closeHistory.size() - NWE_WINDOW, closeHistory.size()));
        int length = window.size();
        if (length < 3) {
            double fallback = window.isEmpty() ? 0 : window.get(length - 1);
            return new NweBand(fallback, fallback, fallback);
        }

        double bandwidth = AppConfig.getNweBandwidth();
        double latestMid = 0.0;
        double latestWeightSum = 0.0;
        for (int j = 0; j < length; j++) {
            double distance = length - 1 - j;
            double weight = Math.exp(-(distance * distance) / (2.0 * bandwidth * bandwidth));
            latestMid += window.get(j) * weight;
            latestWeightSum += weight;
        }
        latestMid = latestWeightSum > 0 ? latestMid / latestWeightSum : window.get(length - 1);

        double absoluteErrorSum = 0.0;
        for (int i = 0; i < length; i++) {
            double smoothed = 0.0;
            double weightSum = 0.0;
            for (int j = 0; j < length; j++) {
                double distance = i - j;
                double weight = Math.exp(-(distance * distance) / (2.0 * bandwidth * bandwidth));
                smoothed += window.get(j) * weight;
                weightSum += weight;
            }
            if (weightSum > 0) {
                smoothed /= weightSum;
            }
            absoluteErrorSum += Math.abs(window.get(i) - smoothed);
        }

        double denominator = Math.max(1, length - 1);
        double mae = (absoluteErrorSum / denominator) * AppConfig.getNweMultiplier();
        return new NweBand(latestMid, latestMid + mae, latestMid - mae);
    }

    private void trimCloseHistory() {
        while (closeHistory.size() > NWE_WINDOW) {
            closeHistory.remove(0);
        }
    }

    private void printDay(DayResult result) {
        System.out.printf(Locale.ROOT, "%s  signals=%d  trades=%d  points=%s%n",
                result.date, result.signals, result.trades.size(), signed(result.totalPoints()));
        for (ClosedTrade trade : result.trades) {
            System.out.printf(Locale.ROOT,
                    "  %s %-2s %-25s entry=%7.2f exit=%7.2f points=%7s reason=%s%n",
                    trade.entryTime.format(TIME_FORMAT),
                    trade.side,
                    trade.symbol.replace("NFO:", ""),
                    trade.entryPrice,
                    trade.exitPrice,
                    signed(trade.points),
                    trade.reason);
            System.out.printf(Locale.ROOT,
                    "     why: %s%n",
                    trade.why);
            System.out.printf(Locale.ROOT,
                    "     setup %s NIFTY O=%.2f H=%.2f L=%.2f C=%.2f | NWE_H=%.2f NWE_L=%.2f | upperDiff=%.2f lowerDiff=%.2f%n",
                    trade.setupTime.format(TIME_FORMAT),
                    trade.setupOpen,
                    trade.setupHigh,
                    trade.setupLow,
                    trade.setupClose,
                    trade.nweUpper,
                    trade.nweLower,
                    trade.upperDiff,
                    trade.lowerDiff);
            System.out.printf(Locale.ROOT,
                    "     trigger %s NIFTY O=%.2f H=%.2f L=%.2f C=%.2f | selectedPremium=%.2f entryLimit=%.2f SL=%.2f target=%.2f band=%.2f%n",
                    trade.entryTime.format(TIME_FORMAT),
                    trade.triggerOpen,
                    trade.triggerHigh,
                    trade.triggerLow,
                    trade.triggerClose,
                    trade.selectedPremium,
                    trade.entryPrice,
                    trade.stopLoss,
                    trade.target,
                    trade.bandLevel);
        }
    }

    private void printResults() {
        System.out.println();
        System.out.println("=".repeat(110));
        System.out.println("DAY-WISE NWE BACKTEST RESULT (OPTION POINTS)");
        System.out.println("=".repeat(110));
        double total = 0.0;
        int trades = 0;
        for (DayResult result : dayResults) {
            double points = result.totalPoints();
            total += points;
            trades += result.trades.size();
            System.out.printf(Locale.ROOT, "%s | trades=%d | points=%s%n",
                    result.date, result.trades.size(), signed(points));
        }
        System.out.println("-".repeat(110));
        System.out.printf(Locale.ROOT, "Total | sessions=%d | trades=%d | points=%s%n",
                dayResults.size(), trades, signed(total));
    }

    private boolean isWithinMarketDataWindow(LocalTime time) {
        return !time.isBefore(LocalTime.of(9, 15)) && !time.isAfter(LocalTime.of(15, 25));
    }

    private boolean isBuySignalWindow(LocalTime time) {
        LocalTime start = LocalTime.parse(AppConfig.getNweBuyStartTime());
        LocalTime end = LocalTime.parse(AppConfig.getNweBuyEndTime());
        return !time.isBefore(start) && !time.isAfter(end);
    }

    private boolean sameDate(Date left, Date right) {
        return left != null && right != null && toLocalDate(left).equals(toLocalDate(right));
    }

    private LocalDate toLocalDate(Date date) {
        return date.toInstant().atZone(IST).toLocalDate();
    }

    private LocalDateTime parseKiteTime(String value) {
        String normalized = value;
        int timezoneIndex = Math.max(value.indexOf('+'), value.indexOf('Z'));
        if (timezoneIndex > 0) {
            normalized = value.substring(0, timezoneIndex);
        }
        normalized = normalized.replace('T', ' ');
        return LocalDateTime.parse(normalized, DATE_TIME_FORMAT);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private double roundToTick(double price) {
        return Math.round(price / 0.05) * 0.05;
    }

    private double roundUpToTick(double price) {
        return Math.ceil(price / 0.05) * 0.05;
    }

    private String signed(double value) {
        return String.format(Locale.ROOT, "%+.2f", value);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private enum OptionSide {
        CE,
        PE
    }

    private static class HistoricalCandle {
        final LocalDateTime time;
        final double open;
        final double high;
        final double low;
        final double close;

        HistoricalCandle(LocalDateTime time, double open, double high, double low, double close) {
            this.time = time;
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
        }
    }

    private static class NweBand {
        final double mid;
        final double upper;
        final double lower;

        NweBand(double mid, double upper, double lower) {
            this.mid = mid;
            this.upper = upper;
            this.lower = lower;
        }
    }

    private static class ActiveMonitor {
        final OptionSide side;
        final double bandLevel;
        final LocalDateTime setupTime;
        final double setupOpen;
        final double setupHigh;
        final double setupLow;
        final double setupClose;
        final double nweUpper;
        final double nweLower;
        final double upperDiff;
        final double lowerDiff;
        final String why;

        ActiveMonitor(OptionSide side, double bandLevel, HistoricalCandle setupCandle, NweBand band,
                      double upperDiff, double lowerDiff, String why) {
            this.side = side;
            this.bandLevel = bandLevel;
            this.setupTime = setupCandle.time;
            this.setupOpen = setupCandle.open;
            this.setupHigh = setupCandle.high;
            this.setupLow = setupCandle.low;
            this.setupClose = setupCandle.close;
            this.nweUpper = band.upper;
            this.nweLower = band.lower;
            this.upperDiff = upperDiff;
            this.lowerDiff = lowerDiff;
            this.why = why;
        }

        boolean isTriggeredBy(HistoricalCandle candle) {
            if (!candle.time.isAfter(setupTime)) {
                return false;
            }
            if (side == OptionSide.PE) {
                return candle.high >= bandLevel - AppConfig.getNweLiveTriggerThresholdPoints();
            }
            return candle.low <= bandLevel + AppConfig.getNweLiveTriggerThresholdPoints();
        }
    }

    private static class SelectedOption {
        final String symbol;
        final double price;

        SelectedOption(String symbol, double price) {
            this.symbol = symbol;
            this.price = price;
        }
    }

    private static class OpenTrade {
        final String symbol;
        final OptionSide side;
        final LocalDateTime entryTime;
        final double entryPrice;
        final double stopLoss;
        final double target;
        final double bandLevel;
        final double selectedPremium;
        final ActiveMonitor monitor;
        final double triggerOpen;
        final double triggerHigh;
        final double triggerLow;
        final double triggerClose;

        OpenTrade(String symbol, OptionSide side, LocalDateTime entryTime, double entryPrice,
                  double stopLoss, double target, double bandLevel, double selectedPremium,
                  ActiveMonitor monitor, HistoricalCandle triggerCandle) {
            this.symbol = symbol;
            this.side = side;
            this.entryTime = entryTime;
            this.entryPrice = entryPrice;
            this.stopLoss = stopLoss;
            this.target = target;
            this.bandLevel = bandLevel;
            this.selectedPremium = selectedPremium;
            this.monitor = monitor;
            this.triggerOpen = triggerCandle.open;
            this.triggerHigh = triggerCandle.high;
            this.triggerLow = triggerCandle.low;
            this.triggerClose = triggerCandle.close;
        }
    }

    private static class ExitResult {
        final LocalDateTime exitTime;
        final double exitPrice;
        final String reason;
        final double points;

        ExitResult(LocalDateTime exitTime, double exitPrice, String reason, double points) {
            this.exitTime = exitTime;
            this.exitPrice = exitPrice;
            this.reason = reason;
            this.points = points;
        }
    }

    private static class ClosedTrade {
        final String symbol;
        final OptionSide side;
        final LocalDateTime entryTime;
        final LocalDateTime exitTime;
        final double entryPrice;
        final double exitPrice;
        final double points;
        final String reason;
        final String why;
        final LocalDateTime setupTime;
        final double setupOpen;
        final double setupHigh;
        final double setupLow;
        final double setupClose;
        final double nweUpper;
        final double nweLower;
        final double upperDiff;
        final double lowerDiff;
        final double triggerOpen;
        final double triggerHigh;
        final double triggerLow;
        final double triggerClose;
        final double selectedPremium;
        final double stopLoss;
        final double target;
        final double bandLevel;

        ClosedTrade(String symbol, OptionSide side, LocalDateTime entryTime, LocalDateTime exitTime,
                    double entryPrice, double exitPrice, double points, String reason, OpenTrade trade) {
            this.symbol = symbol;
            this.side = side;
            this.entryTime = entryTime;
            this.exitTime = exitTime;
            this.entryPrice = entryPrice;
            this.exitPrice = exitPrice;
            this.points = points;
            this.reason = reason;
            this.why = trade.monitor.why;
            this.setupTime = trade.monitor.setupTime;
            this.setupOpen = trade.monitor.setupOpen;
            this.setupHigh = trade.monitor.setupHigh;
            this.setupLow = trade.monitor.setupLow;
            this.setupClose = trade.monitor.setupClose;
            this.nweUpper = trade.monitor.nweUpper;
            this.nweLower = trade.monitor.nweLower;
            this.upperDiff = trade.monitor.upperDiff;
            this.lowerDiff = trade.monitor.lowerDiff;
            this.triggerOpen = trade.triggerOpen;
            this.triggerHigh = trade.triggerHigh;
            this.triggerLow = trade.triggerLow;
            this.triggerClose = trade.triggerClose;
            this.selectedPremium = trade.selectedPremium;
            this.stopLoss = trade.stopLoss;
            this.target = trade.target;
            this.bandLevel = trade.bandLevel;
        }

        static ClosedTrade from(OpenTrade trade, ExitResult exit) {
            return new ClosedTrade(trade.symbol, trade.side, trade.entryTime, exit.exitTime,
                    trade.entryPrice, exit.exitPrice, exit.points, exit.reason, trade);
        }

        static ClosedTrade marketClose(OpenTrade trade, LocalDateTime exitTime, double exitPrice) {
            return new ClosedTrade(trade.symbol, trade.side, trade.entryTime, exitTime,
                    trade.entryPrice, exitPrice, exitPrice - trade.entryPrice, "MARKET_CLOSE", trade);
        }
    }

    private static class DayResult {
        final LocalDate date;
        final List<ClosedTrade> trades = new ArrayList<>();
        int signals;

        DayResult(LocalDate date) {
            this.date = date;
        }

        double totalPoints() {
            return trades.stream().mapToDouble(trade -> trade.points).sum();
        }
    }
}
