package com.trading.strategy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.trading.config.AppConfig;
import com.trading.config.PnLManager;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.kiteconnect.utils.Constants;
import com.zerodhatech.models.Instrument;
import com.zerodhatech.models.Order;
import com.zerodhatech.models.OrderParams;
import com.zerodhatech.models.Quote;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Clean NIFTY NWE options engine.
 *
 * The legacy strategy registry remains in the repository for rollback/reference,
 * but TradingApplication now routes live trading through this class only.
 */
public class NweTradingEngine {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String NIFTY_SPOT = "NSE:NIFTY 50";
    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final int NWE_WINDOW = 500;

    private final KiteConnect kiteConnect;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private final Object brokerLock = new Object();
    private final Object stateLock = new Object();
    private final Object candleProcessingLock = new Object();
    private final PnLManager pnlManager = PnLManager.getInstance();

    private final Map<String, Long> instrumentTokenCache = new HashMap<>();
    private final Map<String, Instrument> nseInstrumentsBySymbol = new HashMap<>();
    private final List<Instrument> nfoNiftyOptions = new ArrayList<>();
    private final List<String> constituentSymbols = new ArrayList<>();
    private final Map<String, Double> lastConstituentVolumes = new HashMap<>();
    private final List<Double> niftyCloseHistory = new ArrayList<>();
    private final List<NweCandleRecord> sessionCache = new ArrayList<>();
    private final Set<String> processedCandleTimes = new HashSet<>();

    private ScheduledFuture<?> bandMonitorFuture;
    private ScheduledFuture<?> positionMonitorFuture;
    private BandMonitor activeBandMonitor;
    private Position openPosition;
    private boolean excelExported = false;
    private boolean dailySummaryLogged = false;
    private boolean candleProcessingActive = false;
    private LocalDateTime lastCompletedNiftyCandleTime;
    private long lastBrokerReconcileAt = 0;
    private int dailyTradeCount = 0;
    private int dailyWinCount = 0;
    private int dailyLossCount = 0;
    private double dailyGrossProfit = 0.0;
    private double dailyGrossLoss = 0.0;

    public NweTradingEngine() {
        this.kiteConnect = initializeKiteConnect();
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        this.scheduler = Executors.newScheduledThreadPool(4);

        loadInstrumentMasters();
        loadNiftyConstituents();
        seedNweHistory();
        initializeConstituentVolumeBaseline();

        logEvent("NWE_ENGINE_READY",
                "bandwidth=" + format(AppConfig.getNweBandwidth())
                        + " multiplier=" + format(AppConfig.getNweMultiplier())
                        + " buyWindow=" + AppConfig.getNweBuyStartTime() + "-" + AppConfig.getNweBuyEndTime()
                        + " candleNearThreshold=" + format(AppConfig.getNweCandleNearThresholdPoints())
                        + " liveTriggerThreshold=" + format(AppConfig.getNweLiveTriggerThresholdPoints())
                        + " optionPremiumMin=" + format(AppConfig.getNweOptionTargetPremium())
                        + " optionSLPoints=" + format(AppConfig.getNweOptionStopLossPoints())
                        + " optionTargetPoints=" + format(AppConfig.getNweOptionTargetPoints()));
    }

    private KiteConnect initializeKiteConnect() {
        KiteConnect kite = new KiteConnect(AppConfig.getApiKey());
        kite.setAccessToken(AppConfig.getAccessToken());
        try {
            kite.getClass().getMethod("setRoot", String.class).invoke(kite, AppConfig.getBaseUrl());
        } catch (Exception ignored) {
            System.out.println("Kite setRoot not available; using default root");
        }
        return kite;
    }

    private void loadInstrumentMasters() {
        try {
            synchronized (brokerLock) {
                for (Instrument instrument : kiteConnect.getInstruments("NSE")) {
                    nseInstrumentsBySymbol.put(instrument.tradingsymbol.toUpperCase(Locale.ROOT), instrument);
                    instrumentTokenCache.put("NSE:" + instrument.tradingsymbol, instrument.instrument_token);
                }

                for (Instrument instrument : kiteConnect.getInstruments("NFO")) {
                    if ("NIFTY".equalsIgnoreCase(instrument.name)
                            && ("CE".equalsIgnoreCase(instrument.instrument_type)
                            || "PE".equalsIgnoreCase(instrument.instrument_type))) {
                        nfoNiftyOptions.add(instrument);
                        instrumentTokenCache.put("NFO:" + instrument.tradingsymbol, instrument.instrument_token);
                    }
                }
            }
            logEvent("NWE_INSTRUMENTS_LOADED", "nse=" + nseInstrumentsBySymbol.size()
                    + " niftyOptions=" + nfoNiftyOptions.size());
        } catch (Exception | KiteException e) {
            throw new RuntimeException("Unable to load Kite instruments: " + e.getMessage(), e);
        }
    }

    private void loadNiftyConstituents() {
        constituentSymbols.clear();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(AppConfig.getNweConstituentCsvUrl()))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                parseConstituentCsv(response.body());
            }
        } catch (Exception e) {
            System.err.println("Could not fetch NIFTY constituents CSV: " + e.getMessage());
        }

        if (constituentSymbols.isEmpty()) {
            String fallback = AppConfig.getNweFallbackConstituents();
            if (fallback != null && !fallback.isBlank()) {
                for (String symbol : fallback.split(",")) {
                    addConstituent(symbol.trim());
                }
            }
        }

        if (constituentSymbols.isEmpty()) {
            throw new RuntimeException("No NIFTY constituents available for synthetic volume");
        }

        constituentSymbols.removeIf(symbol -> !nseInstrumentsBySymbol.containsKey(symbol));
        if (constituentSymbols.size() < 45) {
            throw new RuntimeException("Only " + constituentSymbols.size()
                    + " NIFTY constituents are mapped in Kite NSE instruments");
        }

        logEvent("NWE_CONSTITUENTS_LOADED", "count=" + constituentSymbols.size());
    }

    private void parseConstituentCsv(String csv) throws IOException {
        try (BufferedReader reader = new BufferedReader(new StringReader(csv))) {
            String header = reader.readLine();
            if (header == null) {
                return;
            }
            String[] columns = header.split(",");
            int symbolIndex = -1;
            for (int i = 0; i < columns.length; i++) {
                if ("symbol".equalsIgnoreCase(columns[i].trim())) {
                    symbolIndex = i;
                    break;
                }
            }
            if (symbolIndex < 0) {
                return;
            }

            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length > symbolIndex) {
                    addConstituent(parts[symbolIndex].trim());
                }
            }
        }
    }

    private void addConstituent(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return;
        }
        String normalized = symbol.toUpperCase(Locale.ROOT);
        if (!constituentSymbols.contains(normalized)) {
            constituentSymbols.add(normalized);
        }
    }

    private void seedNweHistory() {
        try {
            LocalDate today = LocalDate.now(IST);
            List<HistoricalCandle> candles = fetchNiftyHistoricalCandles(today.minusDays(35), today);
            for (HistoricalCandle candle : candles) {
                if (isWithinMarketDataWindow(candle.time.toLocalTime())) {
                    niftyCloseHistory.add(candle.close);
                }
            }
            trimCloseHistory();
            logEvent("NWE_HISTORY_SEEDED", "closes=" + niftyCloseHistory.size());
        } catch (Exception e) {
            System.err.println("Could not seed NWE history: " + e.getMessage());
        }
    }

    private void initializeConstituentVolumeBaseline() {
        try {
            Map<String, Quote> quotes = getQuotes(toNseSymbols(constituentSymbols));
            for (String symbol : constituentSymbols) {
                Quote quote = quotes.get("NSE:" + symbol);
                if (quote != null) {
                    lastConstituentVolumes.put(symbol, quote.volumeTradedToday);
                }
            }
            logEvent("NWE_VOLUME_BASELINE_READY", "symbols=" + lastConstituentVolumes.size());
        } catch (Exception | KiteException e) {
            System.err.println("Could not initialize volume baseline: " + e.getMessage());
        }
    }

    public void executeTradingCycle() {
        LocalDateTime now = LocalDateTime.now(IST);
        if (!isMarketOpen(now.toLocalTime())) {
            exportIfNeeded(now);
            return;
        }

        reconcileManualExitIfNeeded(false);
        processCompletedCandles(now);
        exportIfNeeded(now);
    }

    private void processCompletedCandles(LocalDateTime now) {
        if (!beginCandleProcessing()) {
            logEvent("NWE_CANDLE_FETCH_SKIPPED", "reason=previous_cycle_still_running");
            return;
        }

        try {
            processCompletedCandlesLocked(now);
        } finally {
            endCandleProcessing();
        }
    }

    private boolean beginCandleProcessing() {
        synchronized (candleProcessingLock) {
            if (candleProcessingActive) {
                return false;
            }
            candleProcessingActive = true;
            return true;
        }
    }

    private void endCandleProcessing() {
        synchronized (candleProcessingLock) {
            candleProcessingActive = false;
        }
    }

    private void processCompletedCandlesLocked(LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        LocalDateTime sessionStart = LocalDateTime.of(today, LocalTime.of(9, 15));
        LocalDateTime latestCompleted = floorToFiveMinute(now).minusMinutes(5);
        LocalDateTime sessionEnd = LocalDateTime.of(today, LocalTime.of(15, 25));
        if (latestCompleted.isAfter(sessionEnd)) {
            latestCompleted = sessionEnd;
        }
        if (latestCompleted.isBefore(sessionStart)) {
            return;
        }

        LocalDateTime fetchFrom = sessionStart;
        if (lastCompletedNiftyCandleTime != null && lastCompletedNiftyCandleTime.toLocalDate().equals(today)) {
            fetchFrom = lastCompletedNiftyCandleTime.plusMinutes(5);
        }
        if (fetchFrom.isBefore(sessionStart)) {
            fetchFrom = sessionStart;
        }
        if (fetchFrom.isAfter(latestCompleted)) {
            logEvent("NWE_CANDLE_FETCH_SKIPPED",
                    "reason=no_new_completed_candles lastProcessed=" + formatTime(lastCompletedNiftyCandleTime)
                            + " latestCompleted=" + formatTime(latestCompleted));
            return;
        }

        int processedCount = 0;
        while (!fetchFrom.isAfter(latestCompleted)) {
            LocalDateTime fetchTo = fetchFrom.plusMinutes(25);
            if (fetchTo.isAfter(latestCompleted)) {
                fetchTo = latestCompleted;
            }

            List<HistoricalCandle> candles;
            long startedAt = System.currentTimeMillis();
            try {
                candles = fetchNiftyHistoricalCandles(fetchFrom, fetchTo);
            } catch (Exception e) {
                System.err.println("Could not fetch NIFTY 5-min candles: " + e.getMessage());
                return;
            }

            logEvent("NWE_CANDLE_FETCHED",
                    "from=" + fetchFrom.format(TIME_FORMAT)
                            + " to=" + fetchTo.format(TIME_FORMAT)
                            + " candles=" + candles.size()
                            + " ms=" + (System.currentTimeMillis() - startedAt));

            for (HistoricalCandle candle : candles) {
                if (!isWithinMarketDataWindow(candle.time.toLocalTime())) {
                    continue;
                }
                if (candle.time.isBefore(fetchFrom) || candle.time.isAfter(fetchTo)) {
                    continue;
                }
                String key = candle.time.format(DATE_TIME_FORMAT);
                if (processedCandleTimes.contains(key)) {
                    continue;
                }

                boolean allowMonitorSetup = isRecentCompletedCandle(candle.time, now, latestCompleted);
                processSingleCompletedCandle(candle, allowMonitorSetup);
                processedCandleTimes.add(key);
                lastCompletedNiftyCandleTime = candle.time;
                processedCount++;
            }

            fetchFrom = fetchTo.plusMinutes(5);
        }

        logEvent("NWE_CANDLE_FETCH_COMPLETE",
                "processed=" + processedCount
                        + " lastProcessed=" + formatTime(lastCompletedNiftyCandleTime)
                        + " latestCompleted=" + formatTime(latestCompleted));
    }

    private boolean isRecentCompletedCandle(LocalDateTime candleTime, LocalDateTime now, LocalDateTime latestCompleted) {
        LocalDateTime oldestAllowed = floorToFiveMinute(now).minusMinutes(10);
        return !candleTime.isBefore(oldestAllowed) && !candleTime.isAfter(latestCompleted);
    }

    private void processSingleCompletedCandle(HistoricalCandle candle, boolean allowMonitorSetup) {
        double syntheticVolume = calculateSyntheticVolumeDelta();
        niftyCloseHistory.add(candle.close);
        trimCloseHistory();

        NweBand band = calculateCurrentNweBand();
        double syntheticVwap = updateSessionVwap(candle, syntheticVolume);

        NweCandleRecord record = new NweCandleRecord(
                candle.time,
                candle.open,
                candle.high,
                candle.low,
                candle.close,
                syntheticVolume,
                syntheticVwap,
                band.upper,
                band.lower
        );
        sessionCache.add(record);

        logEvent("NWE_CANDLE", record.toLogLine());
        if (allowMonitorSetup) {
            evaluateMonitorSetup(record);
        } else {
            logEvent("NWE_MONITOR_SKIPPED",
                    "reason=catchup_candle candleTime=" + record.time.format(TIME_FORMAT));
        }
    }

    private double updateSessionVwap(HistoricalCandle candle, double syntheticVolume) {
        double previousPv = 0.0;
        double previousVolume = 0.0;
        for (NweCandleRecord record : sessionCache) {
            double typical = (record.high + record.low + record.close) / 3.0;
            previousPv += typical * record.syntheticVolume;
            previousVolume += record.syntheticVolume;
        }

        double typical = (candle.high + candle.low + candle.close) / 3.0;
        double totalPv = previousPv + typical * syntheticVolume;
        double totalVolume = previousVolume + syntheticVolume;
        return totalVolume > 0 ? totalPv / totalVolume : candle.close;
    }

    private void evaluateMonitorSetup(NweCandleRecord record) {
        synchronized (stateLock) {
            if (openPosition != null || activeBandMonitor != null) {
                return;
            }
        }

        if (!isBuySignalWindow(record.time.toLocalTime())) {
            logEvent("NWE_MONITOR_SKIPPED",
                    "reason=outside_buy_window candleTime=" + record.time.format(TIME_FORMAT));
            return;
        }

        double upperDiff = record.nweUpper - record.high;
        double lowerDiff = record.low - record.nweLower;
        boolean nearUpper = upperDiff <= AppConfig.getNweCandleNearThresholdPoints();
        boolean nearLower = lowerDiff <= AppConfig.getNweCandleNearThresholdPoints();

        if (!nearUpper && !nearLower) {
            logEvent("NWE_MONITOR_NOT_CREATED",
                    "candleTime=" + record.time.format(TIME_FORMAT)
                            + " upperDiff=" + format(upperDiff)
                            + " lowerDiff=" + format(lowerDiff)
                            + " threshold=" + format(AppConfig.getNweCandleNearThresholdPoints()));
            return;
        }

        if (nearUpper && (!nearLower || Math.abs(upperDiff) <= Math.abs(lowerDiff))) {
            startBandMonitor(OptionSide.PE, record.nweUpper, "High near NWE upper");
        } else {
            startBandMonitor(OptionSide.CE, record.nweLower, "Low near NWE lower");
        }
    }

    private void startBandMonitor(OptionSide side, double bandLevel, String reason) {
        synchronized (stateLock) {
            if (openPosition != null || activeBandMonitor != null) {
                return;
            }
            activeBandMonitor = new BandMonitor(side, bandLevel, reason);
            int interval = AppConfig.getNweMonitorIntervalSeconds();
            bandMonitorFuture = scheduler.scheduleAtFixedRate(
                    this::checkBandMonitor,
                    0,
                    interval,
                    TimeUnit.SECONDS
            );
            logEvent("NWE_MONITOR_START",
                    "side=" + side
                            + " band=" + format(bandLevel)
                            + " triggerDiff=" + format(AppConfig.getNweLiveTriggerThresholdPoints())
                            + " intervalSeconds=" + AppConfig.getNweMonitorIntervalSeconds()
                            + " reason=" + reason.replace(' ', '_'));
        }
    }

    private void checkBandMonitor() {
        BandMonitor monitor;
        synchronized (stateLock) {
            monitor = activeBandMonitor;
            if (monitor == null || openPosition != null) {
                return;
            }
        }

        if (!isBuySignalWindow(LocalTime.now(IST))) {
            clearBandMonitor("buy window ended");
            return;
        }

        try {
            double spot = getLastPrice(NIFTY_SPOT);
            double diff = monitor.side == OptionSide.PE
                    ? monitor.bandLevel - spot
                    : spot - monitor.bandLevel;
            logEvent("NWE_MONITOR_CHECK",
                    "side=" + monitor.side
                            + " spot=" + format(spot)
                            + " band=" + format(monitor.bandLevel)
                            + " diff=" + format(diff)
                            + " triggerAtOrBelow=" + format(AppConfig.getNweLiveTriggerThresholdPoints()));

            if (diff <= AppConfig.getNweLiveTriggerThresholdPoints()) {
                executeOptionBuy(monitor.side, spot, monitor.reason);
            }
        } catch (Exception | KiteException e) {
            System.err.println("Band monitor error: " + e.getMessage());
        }
    }

    private void executeOptionBuy(OptionSide side, double spot, String reason) {
        synchronized (stateLock) {
            if (openPosition != null) {
                return;
            }
        }

        if (!isBuySignalWindow(LocalTime.now(IST))) {
            clearBandMonitor("buy window ended before order");
            return;
        }

        try {
            SelectedOption selected = findOptionAboveTargetPremium(side, spot);
            if (selected == null) {
                logEvent("NWE_BUY_SKIPPED",
                        "side=" + side + " reason=no_option_above_target_premium targetPremium="
                                + format(AppConfig.getNweOptionTargetPremium()));
                return;
            }

            double limitPrice = roundUpToTick(selected.livePrice
                    * (1.0 + AppConfig.getMarketableLimitBufferPercent() / 100.0));
            double stopLoss = roundToTick(limitPrice - AppConfig.getNweOptionStopLossPoints());
            double target = roundToTick(limitPrice + AppConfig.getNweOptionTargetPoints());

            OrderParams params = new OrderParams();
            params.exchange = "NFO";
            params.tradingsymbol = selected.instrument.tradingsymbol;
            params.transactionType = Constants.TRANSACTION_TYPE_BUY;
            params.quantity = AppConfig.getVWAPOptionsLotSize();
            params.orderType = Constants.ORDER_TYPE_LIMIT;
            params.price = limitPrice;
            params.product = Constants.PRODUCT_MIS;
            params.validity = Constants.VALIDITY_DAY;

            Order order;
            synchronized (brokerLock) {
                order = kiteConnect.placeOrder(params, Constants.VARIETY_REGULAR);
            }

            if (order == null || order.orderId == null) {
                logEvent("NWE_BUY_FAILED",
                        "symbol=" + selected.fullSymbol() + " reason=no_order_id");
                return;
            }

            Position position = new Position();
            position.setTradingSymbol(selected.fullSymbol());
            position.setOrderId(order.orderId);
            position.setEntryPrice(limitPrice);
            position.setQuantity(params.quantity);
            position.setSignalType(SignalType.BUY);
            position.setStopLoss(stopLoss);
            position.setTarget(target);
            position.setPatternType("NWE_" + side);
            position.setEntryTime(new Date());
            position.setSimulated(false);

            synchronized (stateLock) {
                openPosition = position;
                PositionManager.cachePosition(position);
            }

            clearBandMonitor("position opened");
            startPositionMonitor();

            logEvent("NWE_BUY_PLACED",
                    "side=" + side
                            + " symbol=" + selected.fullSymbol()
                            + " orderId=" + order.orderId
                            + " livePremium=" + format(selected.livePrice)
                            + " entryLimit=" + format(limitPrice)
                            + " sl=" + format(stopLoss)
                            + " target=" + format(target)
                            + " qty=" + params.quantity
                            + " reason=" + reason.replace(' ', '_'));
        } catch (Exception | KiteException e) {
            logEvent("NWE_BUY_FAILED", "side=" + side + " reason=" + sanitizeLogValue(e.getMessage()));
        }
    }

    private SelectedOption findOptionAboveTargetPremium(OptionSide side, double spot) throws Exception, KiteException {
        Date expiry = getNearestOptionExpiry();
        if (expiry == null) {
            throw new RuntimeException("No NIFTY option expiry found");
        }

        double roundedSpot = Math.round(spot / 50.0) * 50.0;
        List<Instrument> candidates = new ArrayList<>();
        for (Instrument instrument : nfoNiftyOptions) {
            if (!side.name().equalsIgnoreCase(instrument.instrument_type)) {
                continue;
            }
            if (!sameDate(expiry, instrument.expiry)) {
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

        if (candidates.isEmpty()) {
            return null;
        }

        SelectedOption best = null;
        double targetPremium = AppConfig.getNweOptionTargetPremium();
        for (List<Instrument> batch : partition(candidates, 50)) {
            String[] symbols = batch.stream()
                    .map(instrument -> "NFO:" + instrument.tradingsymbol)
                    .toArray(String[]::new);
            Map<String, Quote> quotes = getQuotes(List.of(symbols));
            for (Instrument instrument : batch) {
                String fullSymbol = "NFO:" + instrument.tradingsymbol;
                Quote quote = quotes.get(fullSymbol);
                if (quote == null || quote.lastPrice < targetPremium) {
                    continue;
                }
                if (best == null || quote.lastPrice < best.livePrice) {
                    best = new SelectedOption(instrument, quote.lastPrice);
                }
            }
        }
        return best;
    }

    private Date getNearestOptionExpiry() {
        LocalDate today = LocalDate.now(IST);
        return nfoNiftyOptions.stream()
                .map(instrument -> instrument.expiry)
                .filter(Objects::nonNull)
                .filter(expiry -> !toLocalDate(expiry).isBefore(today))
                .min(Date::compareTo)
                .orElse(null);
    }

    private void startPositionMonitor() {
        synchronized (stateLock) {
            if (positionMonitorFuture != null && !positionMonitorFuture.isDone()) {
                return;
            }
            int interval = AppConfig.getNweMonitorIntervalSeconds();
            positionMonitorFuture = scheduler.scheduleAtFixedRate(
                    this::checkOpenPosition,
                    0,
                    interval,
                    TimeUnit.SECONDS
            );
            logEvent("NWE_POSITION_MONITOR_START",
                    "intervalSeconds=" + AppConfig.getNweMonitorIntervalSeconds());
        }
    }

    private void checkOpenPosition() {
        Position position;
        synchronized (stateLock) {
            position = openPosition;
        }
        if (position == null) {
            stopPositionMonitor();
            return;
        }

        reconcileManualExitIfNeeded(false);
        synchronized (stateLock) {
            position = openPosition;
        }
        if (position == null) {
            stopPositionMonitor();
            return;
        }

        try {
            double currentPrice = getLastPrice(position.getTradingSymbol());
            double unrealizedPnl = (currentPrice - position.getEntryPrice()) * position.getQuantity();
            logEvent("NWE_POSITION_CHECK",
                    "symbol=" + position.getTradingSymbol()
                            + " current=" + format(currentPrice)
                            + " entry=" + format(position.getEntryPrice())
                            + " sl=" + format(position.getStopLoss())
                            + " target=" + format(position.getTarget())
                            + " qty=" + position.getQuantity()
                            + " unrealizedPnl=" + format(unrealizedPnl)
                            + " dayPnl=" + format(pnlManager.getTotalDailyPnL()));
            if (currentPrice <= position.getStopLoss()) {
                closeOpenPosition("STOP_LOSS", currentPrice);
            } else if (currentPrice >= position.getTarget()) {
                closeOpenPosition("TARGET", currentPrice);
            } else if (LocalTime.now(IST).isAfter(LocalTime.of(15, 25))) {
                closeOpenPosition("MARKET_CLOSE", currentPrice);
            }
        } catch (Exception | KiteException e) {
            System.err.println("Position monitor error: " + e.getMessage());
        }
    }

    private void closeOpenPosition(String reason, double observedPrice) {
        Position position;
        synchronized (stateLock) {
            position = openPosition;
        }
        if (position == null) {
            stopPositionMonitor();
            return;
        }

        try {
            OrderParams params = new OrderParams();
            params.exchange = "NFO";
            params.tradingsymbol = position.getTradingSymbol().replace("NFO:", "");
            params.transactionType = Constants.TRANSACTION_TYPE_SELL;
            params.quantity = position.getQuantity();
            params.orderType = Constants.ORDER_TYPE_MARKET;
            params.product = Constants.PRODUCT_MIS;
            params.validity = Constants.VALIDITY_DAY;
            params.marketProtection = -1;

            Order order;
            synchronized (brokerLock) {
                order = kiteConnect.placeOrder(params, Constants.VARIETY_REGULAR);
            }

            if (order == null || order.orderId == null) {
                logEvent("NWE_SELL_FAILED",
                        "symbol=" + position.getTradingSymbol()
                                + " reason=no_order_id exitReason=" + reason);
                reconcileAfterFailedSell(position, observedPrice, reason, "Sell returned no order id");
                return;
            }

            clearLocalPosition(position, observedPrice, reason);
            logEvent("NWE_SELL_PLACED",
                    "symbol=" + position.getTradingSymbol()
                            + " orderId=" + order.orderId
                            + " reason=" + reason
                            + " observedPrice=" + format(observedPrice));
        } catch (Exception | KiteException e) {
            logEvent("NWE_SELL_FAILED",
                    "symbol=" + position.getTradingSymbol()
                            + " reason=" + sanitizeLogValue(e.getMessage()));
            reconcileAfterFailedSell(position, observedPrice, reason, e.getMessage());
        }
    }

    private void reconcileManualExitIfNeeded(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastBrokerReconcileAt < 30_000) {
            return;
        }
        lastBrokerReconcileAt = now;

        Position position;
        synchronized (stateLock) {
            position = openPosition;
        }
        if (position == null) {
            return;
        }

        try {
            com.zerodhatech.models.Position brokerPosition = findBrokerMisNetPosition(position.getTradingSymbol());
            if (brokerPosition == null || brokerPosition.netQuantity <= 0) {
                double exitPrice = resolveBrokerExitPrice(brokerPosition, position.getEntryPrice());
                clearLocalPosition(position, exitPrice, "MANUAL_EXIT_RECONCILED");
                logEvent("NWE_MANUAL_EXIT_DETECTED",
                        "symbol=" + position.getTradingSymbol()
                                + " brokerState=flat localPositionCleared=true");
            } else if (brokerPosition.netQuantity < position.getQuantity()) {
                position.setQuantity(brokerPosition.netQuantity);
                PositionManager.cachePosition(position);
                logEvent("NWE_POSITION_RECONCILED",
                        "symbol=" + position.getTradingSymbol()
                                + " brokerQty=" + brokerPosition.netQuantity);
            }
        } catch (Exception | KiteException e) {
            System.err.println("Manual-exit reconciliation failed: " + e.getMessage());
        }
    }

    private void reconcileAfterFailedSell(Position position, double attemptedExitPrice,
                                          String reason, String failureMessage) {
        try {
            logEvent("NWE_SELL_RECONCILE_START",
                    "symbol=" + position.getTradingSymbol()
                            + " failure=" + sanitizeLogValue(failureMessage));
            com.zerodhatech.models.Position brokerPosition = findBrokerMisNetPosition(position.getTradingSymbol());
            if (brokerPosition == null || brokerPosition.netQuantity <= 0) {
                double exitPrice = resolveBrokerExitPrice(brokerPosition, attemptedExitPrice);
                clearLocalPosition(position, exitPrice, reason + "_BROKER_FLAT");
                logEvent("NWE_SELL_RECONCILED_FLAT",
                        "symbol=" + position.getTradingSymbol()
                                + " stoppedRetries=true");
            } else {
                logEvent("NWE_SELL_RETRY_REQUIRED",
                        "symbol=" + position.getTradingSymbol()
                                + " brokerQty=" + brokerPosition.netQuantity);
            }
        } catch (Exception | KiteException e) {
            System.err.println("Failed-sell reconciliation failed: " + e.getMessage());
        }
    }

    private com.zerodhatech.models.Position findBrokerMisNetPosition(String fullSymbol)
            throws KiteException, IOException {
        Map<String, List<com.zerodhatech.models.Position>> positions;
        synchronized (brokerLock) {
            positions = kiteConnect.getPositions();
        }
        List<com.zerodhatech.models.Position> netPositions = positions.getOrDefault("net", Collections.emptyList());
        String expected = fullSymbol.replace("NFO:", "").toUpperCase(Locale.ROOT);
        for (com.zerodhatech.models.Position brokerPosition : netPositions) {
            if (brokerPosition == null || brokerPosition.tradingSymbol == null) {
                continue;
            }
            boolean symbolMatches = expected.equals(brokerPosition.tradingSymbol.toUpperCase(Locale.ROOT));
            boolean productMatches = brokerPosition.product == null
                    || Constants.PRODUCT_MIS.equalsIgnoreCase(brokerPosition.product);
            if (symbolMatches && productMatches) {
                return brokerPosition;
            }
        }
        return null;
    }

    private double resolveBrokerExitPrice(com.zerodhatech.models.Position brokerPosition, double fallback) {
        if (brokerPosition != null) {
            if (brokerPosition.sellPrice != null && brokerPosition.sellPrice > 0) {
                return brokerPosition.sellPrice;
            }
            if (brokerPosition.daySellPrice > 0) {
                return brokerPosition.daySellPrice;
            }
            if (brokerPosition.lastPrice != null && brokerPosition.lastPrice > 0) {
                return brokerPosition.lastPrice;
            }
        }
        return fallback;
    }

    private void clearLocalPosition(Position position, double exitPrice, String reason) {
        double pnl = (exitPrice - position.getEntryPrice()) * position.getQuantity();
        pnlManager.addToDailyPnL(pnl);
        pnlManager.addTradeLog(position.getTradingSymbol(), position.getEntryPrice(), exitPrice,
                pnl, reason, position.getPatternType(), position.isSimulated());
        updateDailyTradeStats(pnl);

        synchronized (stateLock) {
            if (openPosition == position) {
                openPosition = null;
            }
            PositionManager.removeCachedPosition(position.getTradingSymbol());
        }

        stopPositionMonitor();
        logEvent("NWE_TRADE_CLOSED",
                "symbol=" + position.getTradingSymbol()
                        + " reason=" + reason
                        + " entry=" + format(position.getEntryPrice())
                        + " exit=" + format(exitPrice)
                        + " qty=" + position.getQuantity()
                        + " tradePnl=" + format(pnl)
                        + " dayPnl=" + format(pnlManager.getTotalDailyPnL())
                        + " trades=" + dailyTradeCount
                        + " wins=" + dailyWinCount
                        + " losses=" + dailyLossCount);
    }

    private double calculateSyntheticVolumeDelta() {
        try {
            Map<String, Quote> quotes = getQuotes(toNseSymbols(constituentSymbols));
            double totalDelta = 0.0;
            for (String symbol : constituentSymbols) {
                Quote quote = quotes.get("NSE:" + symbol);
                if (quote == null) {
                    continue;
                }
                double previous = lastConstituentVolumes.getOrDefault(symbol, quote.volumeTradedToday);
                double delta = quote.volumeTradedToday - previous;
                if (delta < 0) {
                    delta = 0;
                }
                totalDelta += delta;
                lastConstituentVolumes.put(symbol, quote.volumeTradedToday);
            }
            return totalDelta;
        } catch (Exception | KiteException e) {
            System.err.println("Could not calculate synthetic volume delta: " + e.getMessage());
            return 0.0;
        }
    }

    private NweBand calculateCurrentNweBand() {
        List<Double> window = niftyCloseHistory.size() <= NWE_WINDOW
                ? new ArrayList<>(niftyCloseHistory)
                : new ArrayList<>(niftyCloseHistory.subList(niftyCloseHistory.size() - NWE_WINDOW, niftyCloseHistory.size()));
        int length = window.size();
        if (length == 0) {
            return new NweBand(0, 0, 0);
        }

        List<Double> srcAgo = new ArrayList<>();
        for (int i = length - 1; i >= 0; i--) {
            srcAgo.add(window.get(i));
        }

        double bandwidth = AppConfig.getNweBandwidth();
        double latestMid = srcAgo.get(0);
        double absoluteErrorSum = 0.0;
        for (int i = 0; i < length; i++) {
            double weightedSum = 0.0;
            double weightTotal = 0.0;
            for (int j = 0; j < length; j++) {
                double distance = i - j;
                double weight = Math.exp(-((distance * distance) / (bandwidth * bandwidth * 2.0)));
                weightedSum += srcAgo.get(j) * weight;
                weightTotal += weight;
            }

            double smoothed = weightedSum / weightTotal;
            if (i == 0) {
                latestMid = smoothed;
            }
            absoluteErrorSum += Math.abs(srcAgo.get(i) - smoothed);
        }

        // Matches LuxAlgo's default repainting mode for the latest bar.
        double denominator = Math.max(1, length - 1);
        double mae = (absoluteErrorSum / denominator) * AppConfig.getNweMultiplier();
        return new NweBand(latestMid, latestMid + mae, latestMid - mae);
    }

    private List<HistoricalCandle> fetchNiftyHistoricalCandles(LocalDate fromDate, LocalDate toDate)
            throws Exception {
        LocalDateTime from = LocalDateTime.of(fromDate, LocalTime.of(9, 15));
        LocalDateTime to = LocalDateTime.of(toDate, LocalTime.of(15, 30));
        LocalDateTime now = LocalDateTime.now(IST);
        if (to.isAfter(now)) {
            to = now;
        }
        return fetchNiftyHistoricalCandles(from, to);
    }

    private List<HistoricalCandle> fetchNiftyHistoricalCandles(LocalDateTime from, LocalDateTime to)
            throws Exception {
        Long token = getInstrumentToken(NIFTY_SPOT);
        if (token == null) {
            throw new RuntimeException("NIFTY token not found");
        }
        String path = "/instruments/historical/" + token + "/5minute"
                + "?from=" + encode(from.format(DATE_TIME_FORMAT))
                + "&to=" + encode(to.format(DATE_TIME_FORMAT));
        JsonObject root = kiteGetJson(path);
        JsonArray candles = root.getAsJsonObject("data").getAsJsonArray("candles");

        List<HistoricalCandle> result = new ArrayList<>();
        for (JsonElement element : candles) {
            JsonArray row = element.getAsJsonArray();
            LocalDateTime time = parseKiteTime(row.get(0).getAsString());
            result.add(new HistoricalCandle(
                    time,
                    row.get(1).getAsDouble(),
                    row.get(2).getAsDouble(),
                    row.get(3).getAsDouble(),
                    row.get(4).getAsDouble()
            ));
        }
        result.sort(Comparator.comparing(candle -> candle.time));
        return result;
    }

    private JsonObject kiteGetJson(String path) throws Exception {
        String url = AppConfig.getBaseUrl().replaceAll("/$", "") + path;
        Exception lastFailure = null;

        for (int attempt = 1; attempt <= 3; attempt++) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .version(HttpClient.Version.HTTP_1_1)
                    .timeout(Duration.ofSeconds(70))
                    .header("X-Kite-Version", "3")
                    .header("Authorization", "token " + AppConfig.getApiKey() + ":" + AppConfig.getAccessToken())
                    .GET()
                    .build();

            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return JsonParser.parseString(response.body()).getAsJsonObject();
                }

                RuntimeException failure = new RuntimeException("Kite HTTP " + response.statusCode() + ": " + response.body());
                if (!isRetryableStatus(response.statusCode()) || attempt == 3) {
                    throw failure;
                }
                lastFailure = failure;
                logEvent("NWE_KITE_GET_RETRY",
                        "attempt=" + attempt
                                + " status=" + response.statusCode()
                                + " path=" + sanitizeLogValue(path));
            } catch (IOException e) {
                lastFailure = e;
                if (attempt == 3) {
                    throw e;
                }
                logEvent("NWE_KITE_GET_RETRY",
                        "attempt=" + attempt
                                + " reason=" + sanitizeLogValue(e.getMessage())
                                + " path=" + sanitizeLogValue(path));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }

            sleepQuietly(1000L * attempt);
        }

        throw lastFailure;
    }

    private boolean isRetryableStatus(int statusCode) {
        return statusCode == 408 || statusCode == 429 || statusCode >= 500;
    }

    private Long getInstrumentToken(String fullSymbol) {
        Long cached = instrumentTokenCache.get(fullSymbol);
        if (cached != null) {
            return cached;
        }
        try {
            Map<String, Quote> quote = getQuotes(List.of(fullSymbol));
            Quote value = quote.get(fullSymbol);
            if (value != null && value.instrumentToken > 0) {
                instrumentTokenCache.put(fullSymbol, value.instrumentToken);
                return value.instrumentToken;
            }
        } catch (Exception | KiteException e) {
            System.err.println("Could not resolve token for " + fullSymbol + ": " + e.getMessage());
        }
        return null;
    }

    private Map<String, Quote> getQuotes(List<String> symbols) throws KiteException, IOException {
        if (symbols == null || symbols.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Quote> merged = new HashMap<>();
        for (List<String> batch : partition(symbols, 50)) {
            synchronized (brokerLock) {
                merged.putAll(kiteConnect.getQuote(batch.toArray(new String[0])));
            }
            sleepQuietly(150);
        }
        return merged;
    }

    private double getLastPrice(String fullSymbol) throws KiteException, IOException {
        Map<String, Quote> quotes = getQuotes(List.of(fullSymbol));
        Quote quote = quotes.get(fullSymbol);
        if (quote == null || quote.lastPrice <= 0) {
            throw new RuntimeException("No live price for " + fullSymbol);
        }
        return quote.lastPrice;
    }

    private void exportIfNeeded(LocalDateTime now) {
        if (excelExported || now.toLocalTime().isBefore(LocalTime.of(15, 30))) {
            return;
        }
        exportSessionExcel();
    }

    private void exportSessionExcel() {
        synchronized (stateLock) {
            if (excelExported) {
                return;
            }
            excelExported = true;
        }

        try {
            Path dir = Path.of(AppConfig.getNweExcelOutputDir());
            Files.createDirectories(dir);
            String date = LocalDate.now(IST).toString();
            Path file = dir.resolve("nifty-nwe-5min-" + date + ".xlsx");
            SimpleXlsxWriter.write(file, sessionCache);
            logEvent("NWE_EXCEL_EXPORTED",
                    "path=" + file
                            + " rows=" + sessionCache.size());
            logDailySummary("excel_export");
        } catch (Exception e) {
            logEvent("NWE_EXCEL_EXPORT_FAILED", "reason=" + sanitizeLogValue(e.getMessage()));
        }
    }

    public void stopTrading() {
        clearBandMonitor("engine stop");
        stopPositionMonitor();
        scheduler.shutdownNow();
        exportSessionExcel();
        logDailySummary("engine_stop");
        logEvent("NWE_ENGINE_STOPPED", "dayPnl=" + format(pnlManager.getTotalDailyPnL()));
    }

    public boolean hasNoOpenPositions() {
        synchronized (stateLock) {
            return openPosition == null;
        }
    }

    private void clearBandMonitor(String reason) {
        synchronized (stateLock) {
            if (bandMonitorFuture != null) {
                bandMonitorFuture.cancel(false);
                bandMonitorFuture = null;
            }
            if (activeBandMonitor != null) {
                logEvent("NWE_MONITOR_STOP", "reason=" + sanitizeLogValue(reason));
            }
            activeBandMonitor = null;
        }
    }

    private void stopPositionMonitor() {
        synchronized (stateLock) {
            if (positionMonitorFuture != null) {
                positionMonitorFuture.cancel(false);
                positionMonitorFuture = null;
                logEvent("NWE_POSITION_MONITOR_STOP", "reason=no_open_position");
            }
        }
    }

    private void updateDailyTradeStats(double pnl) {
        dailyTradeCount++;
        if (pnl > 0) {
            dailyWinCount++;
            dailyGrossProfit += pnl;
        } else {
            dailyLossCount++;
            dailyGrossLoss += Math.abs(pnl);
        }
    }

    private void logDailySummary(String trigger) {
        if (dailySummaryLogged) {
            return;
        }
        dailySummaryLogged = true;
        logEvent("NWE_EOD_SUMMARY",
                "trigger=" + trigger
                        + " date=" + LocalDate.now(IST)
                        + " candles=" + sessionCache.size()
                        + " trades=" + dailyTradeCount
                        + " wins=" + dailyWinCount
                        + " losses=" + dailyLossCount
                        + " grossProfit=" + format(dailyGrossProfit)
                        + " grossLoss=" + format(dailyGrossLoss)
                        + " netDayPnl=" + format(pnlManager.getTotalDailyPnL()));
    }

    private void logEvent(String event, String details) {
        System.out.println("[" + LocalDateTime.now(IST).format(DATE_TIME_FORMAT) + "] "
                + event + " " + details);
    }

    private String sanitizeLogValue(String value) {
        if (value == null || value.isBlank()) {
            return "none";
        }
        return value.replaceAll("\\s+", "_")
                .replace('|', '_')
                .replace(';', '_');
    }

    private LocalDateTime floorToFiveMinute(LocalDateTime time) {
        int minute = (time.getMinute() / 5) * 5;
        return time.withMinute(minute).withSecond(0).withNano(0);
    }

    private boolean isMarketOpen(LocalTime time) {
        return !time.isBefore(LocalTime.of(9, 15)) && !time.isAfter(LocalTime.of(15, 30));
    }

    private boolean isWithinMarketDataWindow(LocalTime time) {
        return !time.isBefore(LocalTime.of(9, 15)) && !time.isAfter(LocalTime.of(15, 25));
    }

    private boolean isBuySignalWindow(LocalTime time) {
        LocalTime start = LocalTime.parse(AppConfig.getNweBuyStartTime());
        LocalTime end = LocalTime.parse(AppConfig.getNweBuyEndTime());
        return !time.isBefore(start) && !time.isAfter(end);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String formatTime(LocalDateTime value) {
        return value == null ? "none" : value.format(TIME_FORMAT);
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

    private void trimCloseHistory() {
        while (niftyCloseHistory.size() > NWE_WINDOW) {
            niftyCloseHistory.remove(0);
        }
    }

    private double roundToTick(double price) {
        return Math.round(price / 0.05) * 0.05;
    }

    private double roundUpToTick(double price) {
        return Math.ceil(price / 0.05) * 0.05;
    }

    private boolean sameDate(Date left, Date right) {
        return left != null && right != null && toLocalDate(left).equals(toLocalDate(right));
    }

    private LocalDate toLocalDate(Date date) {
        return date.toInstant().atZone(IST).toLocalDate();
    }

    private List<String> toNseSymbols(List<String> symbols) {
        List<String> result = new ArrayList<>();
        for (String symbol : symbols) {
            result.add("NSE:" + symbol);
        }
        return result;
    }

    private <T> List<List<T>> partition(List<T> items, int batchSize) {
        List<List<T>> result = new ArrayList<>();
        for (int i = 0; i < items.size(); i += batchSize) {
            result.add(items.subList(i, Math.min(items.size(), i + batchSize)));
        }
        return result;
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

    private static class BandMonitor {
        final OptionSide side;
        final double bandLevel;
        final String reason;

        BandMonitor(OptionSide side, double bandLevel, String reason) {
            this.side = side;
            this.bandLevel = bandLevel;
            this.reason = reason;
        }
    }

    private static class SelectedOption {
        final Instrument instrument;
        final double livePrice;

        SelectedOption(Instrument instrument, double livePrice) {
            this.instrument = instrument;
            this.livePrice = livePrice;
        }

        String fullSymbol() {
            return "NFO:" + instrument.tradingsymbol;
        }
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

    private static class NweCandleRecord {
        final LocalDateTime time;
        final double open;
        final double high;
        final double low;
        final double close;
        final double syntheticVolume;
        final double syntheticVwap;
        final double nweUpper;
        final double nweLower;

        NweCandleRecord(LocalDateTime time, double open, double high, double low, double close,
                        double syntheticVolume, double syntheticVwap, double nweUpper, double nweLower) {
            this.time = time;
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
            this.syntheticVolume = syntheticVolume;
            this.syntheticVwap = syntheticVwap;
            this.nweUpper = nweUpper;
            this.nweLower = nweLower;
        }

        String toLogLine() {
            return time.format(TIME_FORMAT)
                    + " O=" + format(open)
                    + " H=" + format(high)
                    + " L=" + format(low)
                    + " C=" + format(close)
                    + " SynVol=" + String.format(Locale.ROOT, "%.0f", syntheticVolume)
                    + " SynVWAP=" + format(syntheticVwap)
                    + " NWE_U=" + format(nweUpper)
                    + " NWE_L=" + format(nweLower);
        }
    }

    private static class SimpleXlsxWriter {
        private static final String[] HEADERS = {
                "Time", "Open", "High", "Low", "Close",
                "Synthetic Volume", "Synthetic VWAP", "NWE Upper", "NWE Lower"
        };

        static void write(Path file, List<NweCandleRecord> rows) throws IOException {
            try (OutputStream output = Files.newOutputStream(file);
                 ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
                entry(zip, "[Content_Types].xml", contentTypes());
                entry(zip, "_rels/.rels", rootRels());
                entry(zip, "xl/workbook.xml", workbook());
                entry(zip, "xl/_rels/workbook.xml.rels", workbookRels());
                entry(zip, "xl/worksheets/sheet1.xml", sheet(rows));
            }
        }

        private static void entry(ZipOutputStream zip, String name, String content) throws IOException {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        private static String contentTypes() {
            return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                    + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                    + "</Types>";
        }

        private static String rootRels() {
            return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                    + "</Relationships>";
        }

        private static String workbook() {
            return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                    + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                    + "<sheets><sheet name=\"NIFTY NWE\" sheetId=\"1\" r:id=\"rId1\"/></sheets>"
                    + "</workbook>";
        }

        private static String workbookRels() {
            return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                    + "</Relationships>";
        }

        private static String sheet(List<NweCandleRecord> rows) {
            StringBuilder builder = new StringBuilder();
            builder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            builder.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
            builder.append("<sheetData>");
            builder.append("<row r=\"1\">");
            for (int i = 0; i < HEADERS.length; i++) {
                builder.append(inlineStringCell(1, i + 1, HEADERS[i]));
            }
            builder.append("</row>");

            int rowNumber = 2;
            for (NweCandleRecord record : rows) {
                builder.append("<row r=\"").append(rowNumber).append("\">");
                builder.append(inlineStringCell(rowNumber, 1, record.time.format(TIME_FORMAT)));
                builder.append(numberCell(rowNumber, 2, record.open));
                builder.append(numberCell(rowNumber, 3, record.high));
                builder.append(numberCell(rowNumber, 4, record.low));
                builder.append(numberCell(rowNumber, 5, record.close));
                builder.append(numberCell(rowNumber, 6, record.syntheticVolume));
                builder.append(numberCell(rowNumber, 7, record.syntheticVwap));
                builder.append(numberCell(rowNumber, 8, record.nweUpper));
                builder.append(numberCell(rowNumber, 9, record.nweLower));
                builder.append("</row>");
                rowNumber++;
            }

            builder.append("</sheetData></worksheet>");
            return builder.toString();
        }

        private static String inlineStringCell(int row, int column, String value) {
            return "<c r=\"" + cellRef(row, column) + "\" t=\"inlineStr\"><is><t>"
                    + escapeXml(value) + "</t></is></c>";
        }

        private static String numberCell(int row, int column, double value) {
            return "<c r=\"" + cellRef(row, column) + "\"><v>"
                    + String.format(Locale.ROOT, "%.4f", value) + "</v></c>";
        }

        private static String cellRef(int row, int column) {
            StringBuilder letters = new StringBuilder();
            int value = column;
            while (value > 0) {
                int mod = (value - 1) % 26;
                letters.insert(0, (char) ('A' + mod));
                value = (value - mod - 1) / 26;
            }
            return letters + String.valueOf(row);
        }

        private static String escapeXml(String value) {
            return value.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&apos;");
        }
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
