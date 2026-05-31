// TradingStrategyEngine.java
package com.trading.strategy;

import com.trading.config.AppConfig;
import com.trading.config.PnLManager;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.NetworkException;
import com.zerodhatech.kiteconnect.utils.Constants;
import com.zerodhatech.models.*;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class TradingStrategyEngine {

    private KiteConnect kiteConnect;
    private final Map<String, Position> currentPositions;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss");
    private final Map<String, Long> symbolToTokenMap;
    private Timer targetCheckTimer;
    private boolean isTargetCheckRunning = false;
    private Timer tokenRefreshTimer;
    private boolean isTokenRefreshRunning = false;
    private double lastPreloadedSpotPrice = 0;
    private final Object tokenRefreshLock = new Object();

    // Real-time candle builder
    private RealTimeCandleBuilder realTimeCandleBuilder;
    private Set<String> trackedInstruments;

    // Track active breakout monitors
    private final Map<String, BreakoutMonitor> activeMonitors = new ConcurrentHashMap<>();

    // P&L Manager
    private final PnLManager pnlManager = PnLManager.getInstance();

    // API call synchronization lock
    private final Object apiCallLock = new Object();

    // Strategy Registry - Priority based
    private final List<TradingStrategy> strategies = new ArrayList<>();
    private int lastExecutedCase = 0;

    // Trading hours flags
    private boolean canPlaceBuyOrders = false;

    // ---------- NEW: Caching & Rate Limiting ----------
    private double lastKnownSpotPrice = 0.0;
    private long lastSpotPriceUpdateTime = 0;
    private final long SPOT_PRICE_CACHE_TTL_MS = 60_000; // 1 minute

    // Simple rate limiter: 2 calls per second (500ms between calls)
    private final AtomicLong lastApiCallTime = new AtomicLong(0);
    private final long MIN_API_CALL_INTERVAL_MS = 500;

    public TradingStrategyEngine() {
        initializeKiteConnect();
        this.currentPositions = new HashMap<>();
        this.symbolToTokenMap = new HashMap<>();
        this.isTargetCheckRunning = false;

        // Initialize RealTimeCandleBuilder
        this.realTimeCandleBuilder = new RealTimeCandleBuilder(kiteConnect);
        this.trackedInstruments = new HashSet<>();

        // Register strategies in priority order
        registerStrategies();

        // Initialize with Nifty spot to get all instruments
        try {
            double niftySpot = getNiftySpotPrice();
            preloadOptionTokens(niftySpot);
            lastPreloadedSpotPrice = niftySpot;

            // Start the token refresh timer
            startTokenRefreshTimer();

            // CRITICAL FIX: Pre-initialize candle builder BEFORE market opens
            preInitializeCandleBuilder();

            System.out.println("✅ Trading Strategy Engine initialized");
        } catch (Exception | KiteException e) {
            System.err.println("❌ Error initializing instruments: " + e.getMessage());
        }
    }

    // ---------- NEW: Retry Utility ----------
    private <T> T retryApiCall(Callable<T> apiCall, String operationName, int maxRetries) {
        int attempt = 0;
        long delayMs = 500;

        while (attempt < maxRetries) {
            try {
                // Rate limiting
                long now = System.currentTimeMillis();
                long last = lastApiCallTime.get();
                long wait = MIN_API_CALL_INTERVAL_MS - (now - last);
                if (wait > 0) {
                    Thread.sleep(wait);
                }
                lastApiCallTime.set(System.currentTimeMillis());

                return apiCall.call();
            } catch (Exception e) {
                attempt++;
                if (attempt >= maxRetries) {
                    System.err.println("❌ " + operationName + " failed after " + maxRetries + " attempts: " + e.getMessage());
                    throw new RuntimeException(operationName + " failed", e);
                }
                System.err.println("⚠️ " + operationName + " failed (attempt " + attempt + "), retrying in " + delayMs + "ms...");
                try {
                    Thread.sleep(delayMs);
                    delayMs *= 2;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while retrying " + operationName);
                }
            }
        }
        throw new RuntimeException("Unreachable");
    }

//    private void enableMarketProtectionIfMIS(OrderParams params) {
//        if (Constants.PRODUCT_MIS.equals(params.product)) {
//            params.marketProtection = -1; // direct field assignment
//        }
//    }

    // ------------------------------------------------------------------
    //  Unified Breakout Monitor (5 minutes, immediate price trigger)
    // ------------------------------------------------------------------
    public void startBreakoutMonitor(String instrument, double breakoutLevel,
                                     double stopLoss, double target,
                                     String patternType, long checkIntervalSeconds) {
        synchronized (activeMonitors) {
            if (activeMonitors.containsKey(instrument)) {
                System.out.println("   ⚠️ Monitor already exists for " + instrument);
                return;
            }
            BreakoutMonitor monitor = new BreakoutMonitor(
                    instrument, breakoutLevel, stopLoss, target, patternType,
                    300,                    // 5 minutes duration
                    checkIntervalSeconds
            );
            activeMonitors.put(instrument, monitor);
            monitor.start();
        }
    }

    /**
     * Pre-initialize candle builder before market opens with potential option symbols
     */
    private void preInitializeCandleBuilder() {
        try {
            double niftySpot = getNiftySpotPrice();

            // Build list of potential option symbols
            Set<String> potentialSymbols = new HashSet<>();
            double strikeStep = 50.0;
            double atmStrike = Math.round(niftySpot / strikeStep) * strikeStep;

            // Single loop from -10 to 10 covering both CE and PE ranges
            for (int i = -10; i <= 10; i++) {
                double strike = atmStrike + (i * strikeStep);

                // CE: i from -2 to 10
                if (i >= -2) {
                    potentialSymbols.add(buildOptionSymbol(strike, true));
                }

                // PE: i from -10 to 2
                if (i <= 2) {
                    potentialSymbols.add(buildOptionSymbol(strike, false));
                }
            }

            // Add to tracked instruments
            for (String symbol : potentialSymbols) {
                if (symbolToTokenMap.containsKey(symbol)) {
                    trackedInstruments.add(symbol);
                }
            }

            // Start the candle builder BEFORE market opens
            updateRealTimeCandleBuilder();

            System.out.println("✅ Pre-initialized candle builder with " + trackedInstruments.size() + " instruments");

        } catch (Exception | KiteException e) {
            System.err.println("❌ Failed to pre-initialize candle builder: " + e.getMessage());
        }
    }

    /**
     * Register all strategies via the central StrategyRegistry.
     * To add, remove, reorder, or disable a strategy:
     *   -> Edit StrategyRegistry.java ONLY. No changes needed here.
     */
    private void registerStrategies() {
        StrategyRegistry.printSummary();
        strategies.clear();
        strategies.addAll(StrategyRegistry.buildStrategies(this));
    }

    private void initializeKiteConnect() {
        try {
            this.kiteConnect = new KiteConnect(AppConfig.getApiKey());
            this.kiteConnect.setAccessToken(AppConfig.getAccessToken());

            try {
                java.lang.reflect.Method setRootMethod = kiteConnect.getClass().getMethod("setRoot", String.class);
                setRootMethod.invoke(kiteConnect, AppConfig.getBaseUrl());
            } catch (NoSuchMethodException e) {
                System.out.println("setRoot method not available, using default configuration");
            }

            System.out.println("✅ Trading Strategy Engine initialized with Kite Connect");

        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize Trading Strategy Engine: " + e.getMessage(), e);
        }
    }

    /**
     * Check if buy orders can be placed (between 9:45 AM and 3:15 PM)
     */
    public boolean canPlaceBuyOrders() {
        Calendar cal = Calendar.getInstance();
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        int minute = cal.get(Calendar.MINUTE);
        int currentTimeInMinutes = hour * 60 + minute;

        int buyStartTime = 9 * 60 + 45;  // 9:45 AM
        int buyEndTime = 15 * 60 + 15;   // 3:15 PM

        return currentTimeInMinutes >= buyStartTime && currentTimeInMinutes <= buyEndTime;
    }

    /**
     * Check if we should exit all positions (2:45 PM or market close)
     */
    public boolean shouldExitAllPositions() {
        Calendar cal = Calendar.getInstance();
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        int minute = cal.get(Calendar.MINUTE);
        int currentTimeInMinutes = hour * 60 + minute;

        int exitTime = 14 * 60 + 45;  // 2:45 PM

        return currentTimeInMinutes >= exitTime;
    }

    /**
     * Main method to execute trading cycle with priority-based case checking
     * All analysis runs continuously, but buy orders only placed during allowed hours
     */
    public void executeTradingCycle() {
        try {
            System.out.println("\n" + "=".repeat(80));
            System.out.println("🔄 EXECUTING TRADING CYCLE AT: " + dateFormat.format(new Date()));
            System.out.println("=".repeat(80));

            canPlaceBuyOrders = canPlaceBuyOrders();
            System.out.println("   Buy Orders Allowed: " + (canPlaceBuyOrders ? "YES (9:45-15:15)" : "NO"));

            cleanupBreakoutMonitors();

            boolean positionsClosed = manageExistingPositions();

            if (!currentPositions.isEmpty() && !positionsClosed) {
                System.out.println("⏸️ Open positions exist (" + currentPositions.size() + ") - managing positions only");
                displayMarketStatus();
                return;
            }

            if (currentPositions.size() >= AppConfig.getVWAPOptionsMaxPositions()) {
                System.out.println("⏸️ Max positions reached: " + currentPositions.size());
                displayMarketStatus();
                return;
            }

            System.out.println("   Active Positions: " + currentPositions.size());

            if (shouldExitAllPositions() && !currentPositions.isEmpty()) {
                System.out.println("🔒 TIME TO EXIT ALL POSITIONS (2:45 PM or later)");
                exitAllPositions();
                return;
            }

            if (shouldEndTradingDayEarly()) {
                System.out.println("💰 ENDING TRADING DAY EARLY - In profit by 2:45 PM with no open positions");
                System.out.println("📊 Daily P&L: ₹" + String.format("%.2f", pnlManager.getTotalDailyPnL()));
                return;
            }

            if (realTimeCandleBuilder != null) {
                boolean canProceed = realTimeCandleBuilder.waitForCandleFinalizationAndProceed();
                if (!canProceed) {
                    System.out.println("❌ Cannot proceed with trading cycle - candle finalization issue");
                    return;
                }
            }

            ensureRealTimeCandleBuilderRunning();

            synchronized(activeMonitors) {
                if (!activeMonitors.isEmpty()) {
                    boolean allMonitorsNearExpiry = true;
                    for (BreakoutMonitor monitor : activeMonitors.values()) {
                        if (monitor.isRunning() && monitor.getRemainingSeconds() > 60) {
                            allMonitorsNearExpiry = false;
                            break;
                        }
                    }
                    if (!allMonitorsNearExpiry) {
                        System.out.println("⏸️ " + activeMonitors.size() + " breakout monitor(s) active");
                        displayMarketStatus();
                        return;
                    }
                }
            }

            boolean signalGenerated = false;
            lastExecutedCase = 0;

            for (TradingStrategy strategy : strategies) {
                try {
                    System.out.println("\n" + "🎯".repeat(30));
                    System.out.println("EXECUTING: " + strategy.getStrategyName());
                    System.out.println("🎯".repeat(30));

                    Map<String, String> selectedOptions = strategy.findInstruments(this);

                    if (selectedOptions.isEmpty()) {
                        System.out.println("❌ No suitable options found for " + strategy.getStrategyName());
                        continue;
                    }

                    String ceOption = selectedOptions.get("CE");
                    String peOption = selectedOptions.get("PE");

                    System.out.println("✅ Selected Options:");
                    if (ceOption != null) System.out.println("   CE: " + ceOption);
                    if (peOption != null) System.out.println("   PE: " + peOption);

                    List<Map<String, Object>> signals = new ArrayList<>();

                    if (ceOption != null && !strategy.shouldSkipInstrument(ceOption, this)) {
                        Map<String, Object> signal = strategy.analyzeInstrument(ceOption, this);
                        if ((boolean) signal.get("signal")) {
                            signals.add(signal);
                        }
                    }

                    if (peOption != null && !strategy.shouldSkipInstrument(peOption, this)) {
                        Map<String, Object> signal = strategy.analyzeInstrument(peOption, this);
                        if ((boolean) signal.get("signal")) {
                            signals.add(signal);
                        }
                    }

                    if (!signals.isEmpty()) {
                        System.out.println("\n🎯 " + strategy.getStrategyName() + " generated " + signals.size() + " signal(s)");

                        for (Map<String, Object> signal : signals) {
                            String instrument = (String) signal.get("instrument");
                            if (canPlaceBuyOrders) {
                                strategy.executeBuySignal(instrument, signal, this);
                                signalGenerated = true;
                                lastExecutedCase = strategy.getPriority();
                            } else {
                                System.out.println("⏸️ BUY SIGNAL DETECTED but outside buying hours (9:45-15:15)");
                                System.out.println("   Pattern: " + signal.get("pattern") + " for " + instrument);
                            }
                        }

                        if (signalGenerated) {
                            System.out.println("\n✅ Signal generated by " + strategy.getStrategyName());
                            System.out.println("🛑 Stopping further case checking (priority-based execution)");
                            break;
                        }
                    } else {
                        System.out.println("📊 No signals from " + strategy.getStrategyName() + " - checking next case...");
                    }

                } catch (NetworkException ne) {
                    System.err.println("⚠️ Network error while executing " + strategy.getStrategyName() + ": " + ne.getMessage());
                    System.err.println("   Skipping this strategy for this cycle.");
                    // Continue with next strategy
                } catch (Exception | KiteException e) {
                    System.err.println("❌ Error in strategy " + strategy.getStrategyName() + ": " + e.getMessage());
                    e.printStackTrace();
                    // Continue with next strategy
                }
            }

            if (!signalGenerated) {
                System.out.println("📊 No trading signals generated by any strategy this cycle");
            }

            System.out.println("\n✅ Trading cycle completed at: " + dateFormat.format(new Date()));
            displayMarketStatus();

        } catch (Exception e) {
            System.err.println("❌ Error in trading cycle: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("⏰ Next execution in " + AppConfig.getTradingIntervalMinutes() + " minutes.");
        System.out.println("=".repeat(80) + "\n");
    }

    /**
     * Exit all open positions (called at 2:45 PM or market close)
     */
    private void exitAllPositions() {
        if (currentPositions.isEmpty()) {
            return;
        }

        System.out.println("\n" + "🔴".repeat(30));
        System.out.println("EXITING ALL OPEN POSITIONS");
        System.out.println("🔴".repeat(30));

        List<String> positionsToClose = new ArrayList<>(currentPositions.keySet());

        for (String symbol : positionsToClose) {
            closePositionForced(symbol);
        }

        // Stop any active monitors
        synchronized(activeMonitors) {
            for (BreakoutMonitor monitor : activeMonitors.values()) {
                monitor.stop();
            }
            activeMonitors.clear();
        }

        System.out.println("✅ All positions closed");
        System.out.println("📊 Final Daily P&L: ₹" + String.format("%.2f", pnlManager.getTotalDailyPnL()));
    }

    /**
     * Force close a position (for end-of-day)
     */
    private void closePositionForced(String symbol) {
        Position position = currentPositions.get(symbol);
        if (position == null) return;

        try {
            double exitPrice;
            boolean isSimulated = position.isSimulated();

            String[] instruments = {symbol};
            Map<String, Quote> quotes;
            synchronized(apiCallLock) {
                quotes = kiteConnect.getQuote(instruments);
            }
            Quote quote = quotes.get(symbol);
            if (quote == null) {
                System.err.println("Cannot get quote for forced close of " + symbol);
                return;
            }
            exitPrice = quote.lastPrice;

            if (!isSimulated) {
                OrderParams orderParams = new OrderParams();
                orderParams.exchange = "NFO";
                orderParams.tradingsymbol = symbol.replace("NFO:", "");
                orderParams.transactionType = Constants.TRANSACTION_TYPE_SELL;
                orderParams.quantity = position.getQuantity();
                orderParams.orderType = Constants.ORDER_TYPE_MARKET;
                orderParams.product = Constants.PRODUCT_MIS;
                orderParams.validity = Constants.VALIDITY_DAY;
                orderParams.marketProtection = -1;

                Order exitOrder = kiteConnect.placeOrder(orderParams, Constants.VARIETY_REGULAR);
                if (exitOrder == null || exitOrder.orderId == null) {
                    System.err.println("Failed to force close " + symbol);
                    return;
                }
                System.out.println("🔴 FORCED CLOSE (REAL) for " + symbol + " at " + exitPrice);
            } else {
                System.out.println("🧪 SIMULATED FORCED CLOSE for " + symbol + " at " + exitPrice);
            }

            double pnl = (exitPrice - position.getEntryPrice()) * position.getQuantity();
            pnlManager.addToDailyPnL(pnl);
            pnlManager.addTradeLog(symbol, position.getEntryPrice(), exitPrice, pnl,
                    "FORCED_CLOSE", position.getPatternType(), isSimulated);

            currentPositions.remove(symbol);
            PositionManager.removeCachedPosition(symbol);

        } catch (Exception | KiteException e) {
            System.err.println("Error in forced close: " + e.getMessage());
        }
    }

    /**
     * Check if we should end trading day early (profitable by 2:45 PM with no positions)
     */
    private boolean shouldEndTradingDayEarly() {
        try {
            Calendar cal = Calendar.getInstance();
            int hour = cal.get(Calendar.HOUR_OF_DAY);
            int minute = cal.get(Calendar.MINUTE);

            boolean isAfter245PM = (hour > 14) || (hour == 14 && minute >= 45);

            if (!isAfter245PM) {
                return false;
            }

            double dailyPnL = pnlManager.getTotalDailyPnL();
            boolean isProfitable = dailyPnL > 0;
            boolean hasNoPositions = currentPositions.isEmpty();

            if (isProfitable && hasNoPositions) {
                Map<String, Position> cachedPositions = PositionManager.getAllCachedPositions();
                return cachedPositions.isEmpty();
            }

            return false;

        } catch (Exception e) {
            System.err.println("❌ Error checking early trading end conditions: " + e.getMessage());
            return false;
        }
    }

    /**
     * Clean up expired or unnecessary breakout monitors
     */
    private void cleanupBreakoutMonitors() {
        synchronized (activeMonitors) {
            List<String> toRemove = new ArrayList<>();
            for (Map.Entry<String, BreakoutMonitor> e : activeMonitors.entrySet()) {
                BreakoutMonitor m = e.getValue();
                if (!m.isRunning() || m.hasExpired() || m.isBuySignalGenerated() || currentPositions.containsKey(e.getKey())) {
                    m.stop();
                    toRemove.add(e.getKey());
                }
            }
            toRemove.forEach(activeMonitors::remove);
        }
    }

    /**
     * Manage existing positions - check stop loss
     */
    private boolean manageExistingPositions() {
        try {
            if (currentPositions.isEmpty()) {
                return false;
            }

            System.out.println("🔍 Managing " + currentPositions.size() + " existing positions...");

            List<String> positionsToClose = new ArrayList<>();

            List<String> positionSymbols = new ArrayList<>(currentPositions.keySet());
            String[] symbolsArray = positionSymbols.toArray(new String[0]);

            Map<String, Quote> quotes;
            synchronized(apiCallLock) {
                quotes = kiteConnect.getQuote(symbolsArray);
            }

            for (String symbol : currentPositions.keySet()) {
                Position position = currentPositions.get(symbol);
                Quote quote = quotes.get(symbol);

                if (quote != null) {
                    double currentPrice = quote.lastPrice;
                    double stopLoss = position.getStopLoss();

                    if (currentPrice <= stopLoss) {
                        positionsToClose.add(symbol);
                    }
                }
            }

            boolean anyClosed = false;
            for (String symbol : positionsToClose) {
                System.out.println("🔴 STOP LOSS HIT for: " + symbol);
                closePositionDueToStopLoss(symbol);
                anyClosed = true;
            }

            return anyClosed;

        } catch (Exception | KiteException e) {
            System.err.println("❌ Error managing positions: " + e.getMessage());
            return false;
        }
    }

    private void closePositionDueToStopLoss(String symbol) {
        Position position = currentPositions.get(symbol);
        if (position == null) return;

        try {
            double exitPrice;
            boolean isSimulated = position.isSimulated();

            // Get current market price (needed for both real and simulated)
            String[] instruments = {symbol};
            Map<String, Quote> quotes;
            synchronized(apiCallLock) {
                quotes = kiteConnect.getQuote(instruments);
            }
            Quote quote = quotes.get(symbol);
            if (quote == null) {
                System.err.println("Cannot get quote for " + symbol);
                return;
            }
            exitPrice = quote.lastPrice;

            if (!isSimulated) {
                // Real order: place sell order
                OrderParams orderParams = new OrderParams();
                orderParams.exchange = "NFO";
                orderParams.tradingsymbol = symbol.replace("NFO:", "");
                orderParams.transactionType = Constants.TRANSACTION_TYPE_SELL;
                orderParams.quantity = position.getQuantity();
                orderParams.orderType = Constants.ORDER_TYPE_MARKET;
                orderParams.product = Constants.PRODUCT_MIS;
                orderParams.validity = Constants.VALIDITY_DAY;
                orderParams.marketProtection = -1;

                Order exitOrder = kiteConnect.placeOrder(orderParams, Constants.VARIETY_REGULAR);
                if (exitOrder == null || exitOrder.orderId == null) {
                    System.err.println("Failed to place sell order for " + symbol);
                    return;
                }
                System.out.println("🛑 STOP LOSS EXECUTED (REAL) for " + symbol + " at " + exitPrice);
            } else {
                System.out.println("🧪 SIMULATED STOP LOSS for " + symbol + " at " + exitPrice);
            }

            double pnl = (exitPrice - position.getEntryPrice()) * position.getQuantity();
            pnlManager.addToDailyPnL(pnl);
            pnlManager.addTradeLog(symbol, position.getEntryPrice(), exitPrice, pnl,
                    "STOP_LOSS", position.getPatternType(), isSimulated);

            currentPositions.remove(symbol);
            PositionManager.removeCachedPosition(symbol);

        } catch (Exception | KiteException e) {
            System.err.println("Error closing position due to stop loss: " + e.getMessage());
        }
    }

    private void closePositionDueToTarget(String instrument) {
        Position position = currentPositions.get(instrument);
        if (position == null) return;

        try {
            double exitPrice;
            boolean isSimulated = position.isSimulated();

            String[] instruments = {instrument};
            Map<String, Quote> quotes;
            synchronized(apiCallLock) {
                quotes = kiteConnect.getQuote(instruments);
            }
            Quote quote = quotes.get(instrument);
            if (quote == null) {
                System.err.println("Cannot get quote for " + instrument);
                return;
            }
            exitPrice = quote.lastPrice;

            if (!isSimulated) {
                OrderParams orderParams = new OrderParams();
                orderParams.exchange = "NFO";
                orderParams.tradingsymbol = instrument.replace("NFO:", "");
                orderParams.transactionType = Constants.TRANSACTION_TYPE_SELL;
                orderParams.quantity = position.getQuantity();
                orderParams.orderType = Constants.ORDER_TYPE_MARKET;
                orderParams.product = Constants.PRODUCT_MIS;
                orderParams.validity = Constants.VALIDITY_DAY;
                orderParams.marketProtection = -1;

                Order exitOrder = kiteConnect.placeOrder(orderParams, Constants.VARIETY_REGULAR);
                if (exitOrder == null || exitOrder.orderId == null) {
                    System.err.println("Failed to place target sell order for " + instrument);
                    return;
                }
                System.out.println("💰 TARGET HIT (REAL) for " + instrument + " at " + exitPrice);
            } else {
                System.out.println("🧪 SIMULATED TARGET HIT for " + instrument + " at " + exitPrice);
            }

            double pnl = (exitPrice - position.getEntryPrice()) * position.getQuantity();
            pnlManager.addToDailyPnL(pnl);
            pnlManager.addTradeLog(instrument, position.getEntryPrice(), exitPrice, pnl,
                    "TARGET", position.getPatternType(), isSimulated);

            currentPositions.remove(instrument);
            PositionManager.removeCachedPosition(instrument);

        } catch (Exception | KiteException e) {
            System.err.println("Error closing position due to target: " + e.getMessage());
        }
    }

    private void startTargetCheckTimer() {
        if (isTargetCheckRunning) {
            return;
        }

        targetCheckTimer = new Timer();
        long interval = 3 * 1000;

        targetCheckTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                checkTargetConditions();
            }
        }, interval, interval);

        isTargetCheckRunning = true;
        System.out.println("Exit check timer started (3 second intervals for target + stop loss)");
    }

    private void checkTargetConditions() {
        try {
            Map<String, Position> cachedPositions = PositionManager.getAllCachedPositions();

            if (cachedPositions.isEmpty()) {
                return;
            }

            List<String> instruments = new ArrayList<>(cachedPositions.keySet());
            String[] symbolsArray = instruments.toArray(new String[0]);
            Map<String, Quote> quotes;
            synchronized(apiCallLock) {
                quotes = kiteConnect.getQuote(symbolsArray);
            }

            for (String instrument : cachedPositions.keySet()) {
                Position position = cachedPositions.get(instrument);
                Quote quote = quotes.get(instrument);

                if (quote != null) {
                    double currentPrice = quote.lastPrice;
                    if (currentPrice <= position.getStopLoss()) {
                        System.out.println("STOP LOSS HIT for " + instrument + " at live price " +
                                String.format("%.2f", currentPrice) + " (SL " +
                                String.format("%.2f", position.getStopLoss()) + ")");
                        closePositionDueToStopLoss(instrument);
                    } else if (currentPrice >= position.getTarget()) {
                        System.out.println("TARGET HIT for " + instrument);
                        closePositionDueToTarget(instrument);
                    }
                }
            }

        } catch (Exception | KiteException e) {
            System.err.println("❌ Error in target check: " + e.getMessage());
        }
    }

    /**
     * Update real-time candle builder with all tracked instruments
     */
    private void updateRealTimeCandleBuilder() {
        if (trackedInstruments.isEmpty()) {
            return;
        }

        String[] instrumentsArray = trackedInstruments.toArray(new String[0]);

        if (!realTimeCandleBuilder.isRunning()) {
            System.out.println("🚀 Starting real-time candle builder for " + trackedInstruments.size() + " instruments");
            realTimeCandleBuilder.start(instrumentsArray);
        }
    }

    /**
     * Start real-time candle builder if not already running
     */
    private void ensureRealTimeCandleBuilderRunning() {
        if (!trackedInstruments.isEmpty() && !realTimeCandleBuilder.isRunning()) {
            updateRealTimeCandleBuilder();
        }
    }

    public String findOptionNearPremium(double niftySpot, boolean isCall, double targetPrice, double tolerance) {
        try {
            double strikeStep = 50.0;
            double atmStrike = Math.round(niftySpot / strikeStep) * strikeStep;

            System.out.println("🔍 Searching for " + (isCall ? "CE" : "PE") + " options with premium near " + targetPrice);

            List<String> validOptionSymbols = new ArrayList<>();
            for (int i = -10; i <= 10; i++) {
                double strike = atmStrike + (i * strikeStep);

                if (isCall) {
                    // CE: i from -2 to 10
                    if (i >= -2) {
                        String optionSymbol = buildOptionSymbol(strike, true);
                        if (symbolToTokenMap.containsKey(optionSymbol)) {
                            validOptionSymbols.add(optionSymbol);
                        }
                    }
                } else {
                    // PE: i from -10 to 2
                    if (i <= 2) {
                        String optionSymbol = buildOptionSymbol(strike, false);
                        if (symbolToTokenMap.containsKey(optionSymbol)) {
                            validOptionSymbols.add(optionSymbol);
                        }
                    }
                }
            }

            if (validOptionSymbols.isEmpty()) {
                System.out.println("❌ No valid " + (isCall ? "CE" : "PE") + " symbols found in cache");
                return null;
            }

            Map<String, Double> premiums = getBatchOptionPremiums(validOptionSymbols);

            String bestOption = null;
            double bestPremiumDiff = Double.MAX_VALUE;

            for (String optionSymbol : validOptionSymbols) {
                Double premium = premiums.get(optionSymbol);
                if (premium != null && premium > targetPrice && Math.abs(premium - targetPrice) <= tolerance) {
                    double premiumDiff = Math.abs(premium - targetPrice);
                    if (premiumDiff < bestPremiumDiff) {
                        bestPremiumDiff = premiumDiff;
                        bestOption = optionSymbol;
                    }
                }
            }

            if (bestOption != null) {
                System.out.println("✅ Found suitable option: " + bestOption + " (Premium: " + premiums.get(bestOption) + ")");
            } else {
                System.out.println("❌ No " + (isCall ? "CE" : "PE") + " options found");
            }

            return bestOption;

        } catch (Exception e) {
            System.err.println("❌ Error finding " + (isCall ? "CE" : "PE") + " option: " + e.getMessage());
            return null;
        }
    }

    /**
     * Get premiums for multiple options in one API call
     */
    private Map<String, Double> getBatchOptionPremiums(List<String> optionSymbols) {
        Map<String, Double> premiums = new HashMap<>();
        if (optionSymbols.isEmpty()) return premiums;

        try {
            String[] symbolsArray = optionSymbols.toArray(new String[0]);
            Map<String, Quote> allQuotes = retryApiCall(() -> {
                synchronized (apiCallLock) {
                    try {
                        return kiteConnect.getQuote(symbolsArray);
                    } catch (KiteException e) {
                        throw new RuntimeException(e);
                    }
                }
            }, "getBatchQuotes", 2);

            for (String symbol : optionSymbols) {
                Quote quote = allQuotes.get(symbol);
                if (quote != null && quote.lastPrice > 0) {
                    premiums.put(symbol, quote.lastPrice);

                    if (!trackedInstruments.contains(symbol)) {
                        trackedInstruments.add(symbol);
                        realTimeCandleBuilder.addInstrument(symbol);
                    }

                    double vwap = quote.averagePrice > 0 ? quote.averagePrice : quote.lastPrice;
                    realTimeCandleBuilder.processTick(symbol, quote.lastPrice, vwap, new Date());
                }
            }
        } catch (Exception e) {
            System.err.println("⚠️ Batch quote fetch failed: " + e.getMessage());
            // Fallback to individual fetches? Or just return empty.
        }
        updateRealTimeCandleBuilder();
        return premiums;
    }


    private String buildOptionSymbol(double strike, boolean isCall) {
        Calendar expiryDate = getNextExpiryDate();
        boolean isMonthlyExpiry = isLastTuesdayOfMonth(expiryDate);
        String expiryString = formatExpiryDate(expiryDate, isMonthlyExpiry);
        int intStrike = (int) Math.round(strike);
        return "NFO:NIFTY" + expiryString + intStrike + (isCall ? "CE" : "PE");
    }

    private Calendar getNextExpiryDate() {
        Calendar cal = Calendar.getInstance();
        int currentDay = cal.get(Calendar.DAY_OF_WEEK);
        int daysToAdd;

        if (currentDay == Calendar.TUESDAY) {
            int hour = cal.get(Calendar.HOUR_OF_DAY);
            int minute = cal.get(Calendar.MINUTE);

            if (hour > 15 || (hour == 15 && minute >= 30)) {
                daysToAdd = 7;
            } else {
                daysToAdd = 0;
            }
        } else {
            daysToAdd = (Calendar.TUESDAY - currentDay + 7) % 7;
            if (daysToAdd == 0) daysToAdd = 7;
        }

        cal.add(Calendar.DATE, daysToAdd);
        return cal;
    }

    private boolean isLastTuesdayOfMonth(Calendar date) {
        Calendar lastTuesday = getLastTuesdayOfMonth(date);
        return date.get(Calendar.DATE) == lastTuesday.get(Calendar.DATE) &&
                date.get(Calendar.MONTH) == lastTuesday.get(Calendar.MONTH) &&
                date.get(Calendar.YEAR) == lastTuesday.get(Calendar.YEAR);
    }

    private Calendar getLastTuesdayOfMonth(Calendar date) {
        Calendar lastTuesday = (Calendar) date.clone();
        lastTuesday.set(Calendar.DAY_OF_MONTH, lastTuesday.getActualMaximum(Calendar.DAY_OF_MONTH));

        while (lastTuesday.get(Calendar.DAY_OF_WEEK) != Calendar.TUESDAY) {
            lastTuesday.add(Calendar.DATE, -1);
        }
        return lastTuesday;
    }

    private String formatExpiryDate(Calendar date, boolean isMonthlyExpiry) {
        Calendar adjustedDate = AppConfig.adjustForHoliday(date);

        int year = adjustedDate.get(Calendar.YEAR) % 100;
        int month = adjustedDate.get(Calendar.MONTH);
        int day = adjustedDate.get(Calendar.DATE);

        if (isMonthlyExpiry) {
            String[] monthCodes = {"JAN", "FEB", "MAR", "APR", "MAY", "JUN",
                    "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"};
            return String.format("%02d", year) + monthCodes[month];
        } else {
            String[] monthCodes = {"1", "2", "3", "4", "5", "6",
                    "7", "8", "9", "O", "N", "D"};
            return String.format("%02d", year) + monthCodes[month] + String.format("%02d", day);
        }
    }

    // ---------- MODIFIED: getNiftySpotPrice with caching ----------
    public double getNiftySpotPrice() throws KiteException, IOException {
        // Return cached value if fresh
        if (System.currentTimeMillis() - lastSpotPriceUpdateTime < SPOT_PRICE_CACHE_TTL_MS && lastKnownSpotPrice > 0) {
            return lastKnownSpotPrice;
        }

        try {
            double spot = retryApiCall(() -> {
                String[] instruments = {"NSE:NIFTY 50"};
                Map<String, Quote> quoteData;
                synchronized (apiCallLock) {
                    try {
                        quoteData = kiteConnect.getQuote(instruments);
                    } catch (KiteException e) {
                        throw new RuntimeException(e);
                    }
                }
                return quoteData.get("NSE:NIFTY 50").lastPrice;
            }, "getNiftySpotPrice", 3);

            lastKnownSpotPrice = spot;
            lastSpotPriceUpdateTime = System.currentTimeMillis();
            return spot;

        } catch (Exception e) {
            if (lastKnownSpotPrice > 0) {
                System.err.println("⚠️ Using cached Nifty spot price: " + lastKnownSpotPrice);
                return lastKnownSpotPrice;
            }
            throw new RuntimeException("Cannot fetch Nifty spot price and no cache available", e);
        }
    }

    /**
     * Preload tokens for multiple strikes
     */
    public void preloadOptionTokens(double niftySpot) {
        try {
            System.out.println("🔄 Preloading option tokens for Nifty spot: " + niftySpot);

            double strikeStep = 50.0;
            double atmStrike = Math.round(niftySpot / strikeStep) * strikeStep;

            for (int i = -10; i <= 10; i++) {
                double strike = atmStrike + (i * strikeStep);

                // CE: i from -2 to 10
                if (i >= -2) {
                    preloadSingleToken(buildOptionSymbol(strike, true));
                }

                // PE: i from -10 to 2
                if (i <= 2) {
                    preloadSingleToken(buildOptionSymbol(strike, false));
                }
            }

            System.out.println("✅ Preloaded " + symbolToTokenMap.size() + " option tokens");
        } catch (Exception e) {
            System.err.println("❌ Error preloading option tokens: " + e.getMessage());
        }
    }

    private void preloadSingleToken(String optionSymbol) {
        try {
            if (symbolToTokenMap.containsKey(optionSymbol)) {
                return;
            }

            String exchange = "NFO";

            List<Instrument> instruments;
            synchronized(apiCallLock) {
                instruments = kiteConnect.getInstruments(exchange);
            }

            for (Instrument instrument : instruments) {
                String fullSymbol = instrument.exchange + ":" + instrument.tradingsymbol;

                if (fullSymbol.equalsIgnoreCase(optionSymbol)) {
                    Long token = instrument.instrument_token;
                    symbolToTokenMap.put(optionSymbol, token);
                    return;
                }
            }

        } catch (Exception | KiteException e) {
            System.err.println("❌ Error preloading token for " + optionSymbol + ": " + e.getMessage());
        }
    }

    /**
     * Execute buy signal for an instrument
     */
    public void executeBuySignal(String instrument, String patternType) {
        // Double-check that we're within buying hours
        if (!canPlaceBuyOrders()) {
            System.out.println("⏸️ BUY ORDER BLOCKED - Outside buying hours (9:45-15:15)");
            return;
        }

        try {
            System.out.println("\n🚀 EXECUTING BUY SIGNAL for: " + instrument);
            System.out.println("📊 Pattern Type: " + patternType.toUpperCase());

            if (currentPositions.containsKey(instrument) || PositionManager.hasCachedPosition(instrument)) {
                System.out.println("⏸️ Position already exists - skipping");
                return;
            }

            String[] instruments = {instrument};
            Map<String, Quote> quotes;
            synchronized(apiCallLock) {
                Thread.sleep(100);
                quotes = kiteConnect.getQuote(instruments);
            }

            Quote quote = quotes.get(instrument);
            if (quote == null || quote.ohlc == null) {
                System.err.println("❌ Unable to get quote for: " + instrument);
                return;
            }

            double entryPrice = quote.lastPrice;
            double vwapPrice = quote.averagePrice;
            double stopLoss = entryPrice * 0.90;
            double target = entryPrice * 1.20;

            System.out.println("📊 Trade Details:");
            System.out.println("   Entry Price: " + entryPrice);
            System.out.println("   Stop Loss: " + stopLoss);
            System.out.println("   Target: " + target);

            Position position = placeBuyOrder(instrument, entryPrice, stopLoss, target, patternType);
            if (position != null) {
                position.setPatternType(patternType);
                position.setVwap(vwapPrice);
                position.setEntryTime(new Date());

                currentPositions.put(instrument, position);
                PositionManager.cachePosition(position);
                startTargetCheckTimer();

                System.out.println("✅ Position opened: " + instrument);
            }

        } catch (Exception | KiteException e) {
            System.err.println("❌ Error executing buy signal: " + e.getMessage());
        }
    }

    private Position placeBuyOrder(String symbol, double entryPrice, double stopLoss,
                                   double target, String patternType) {
        try {
            if (useManagedEntryExecution()) {
                return placeManagedLimitBuyOrder(symbol, entryPrice, stopLoss, target, patternType, "SIM_");
            }
            int quantity = AppConfig.getVWAPOptionsLotSize();
            String tradingSymbol = symbol.replace("NFO:", "");

            synchronized(apiCallLock) {
                Thread.sleep(100);

                OrderParams orderParams = new OrderParams();
                orderParams.exchange = "NFO";
                orderParams.tradingsymbol = tradingSymbol;
                orderParams.transactionType = Constants.TRANSACTION_TYPE_BUY;
                orderParams.quantity = quantity;
                orderParams.orderType = Constants.ORDER_TYPE_MARKET;
                orderParams.price = 0d;
                orderParams.product = Constants.PRODUCT_MIS;
                orderParams.validity = Constants.VALIDITY_DAY;
                orderParams.marketProtection = -1;

                Order order = kiteConnect.placeOrder(orderParams, Constants.VARIETY_REGULAR);

                if (order != null && order.orderId != null) {
                    Position position = new Position();
                    position.setTradingSymbol(symbol);
                    position.setOrderId(order.orderId);
                    position.setEntryPrice(entryPrice);
                    position.setQuantity(quantity);
                    position.setSignalType(SignalType.BUY);
                    position.setStopLoss(stopLoss);
                    position.setTarget(target);
                    position.setPatternType(patternType);
                    position.setSimulated(false);
                    return position;
                }
            }
        } catch (Exception | KiteException e) {
            System.err.println("❌ Real order failed for " + symbol + ": " + e.getMessage());
            if (AppConfig.isSimulateFailedOrders()) {
                System.out.println("⚠️ SIMULATION MODE: Creating simulated position for " + symbol);
                Position simPosition = new Position();
                simPosition.setTradingSymbol(symbol);
                simPosition.setOrderId("SIM_" + System.currentTimeMillis());
                simPosition.setEntryPrice(entryPrice);
                simPosition.setQuantity(AppConfig.getVWAPOptionsLotSize());
                simPosition.setSignalType(SignalType.BUY);
                simPosition.setStopLoss(stopLoss);
                simPosition.setTarget(target);
                simPosition.setPatternType(patternType);
                simPosition.setSimulated(true);
                return simPosition;
            }
        }
        return null;
    }

    /**
     * Execute pullback buy signal
     */
    public void executePullbackBuySignal(String instrument, double entryPrice, double stopLoss, double target) {
        // Double-check that we're within buying hours
        if (!canPlaceBuyOrders()) {
            System.out.println("⏸️ PULLBACK BUY ORDER BLOCKED - Outside buying hours (9:45-15:15)");
            return;
        }

        try {
            System.out.println("\n🚀 EXECUTING PULLBACK BUY SIGNAL for: " + instrument);

            if (currentPositions.containsKey(instrument) || PositionManager.hasCachedPosition(instrument)) {
                System.out.println("⏸️ Position already exists - skipping");
                return;
            }

            String[] instruments = {instrument};
            Map<String, Quote> quotes;
            synchronized(apiCallLock) {
                Thread.sleep(100);
                quotes = kiteConnect.getQuote(instruments);
            }

            Quote quote = quotes.get(instrument);
            if (quote == null) {
                System.err.println("❌ Unable to get quote for: " + instrument);
                return;
            }

            double currentPrice = quote.lastPrice;
            double vwapPrice = quote.averagePrice;

            if (currentPrice >= entryPrice) {
                entryPrice = currentPrice;
            } else {
                startPullbackBreakoutMonitor(instrument, entryPrice, stopLoss, target);
                return;
            }

            Position position = placePullbackBuyOrder(instrument, entryPrice, stopLoss, target);
            if (position != null) {
                position.setPatternType("ema_vwap_pullback");
                position.setVwap(vwapPrice);
                position.setEntryTime(new Date());
                currentPositions.put(instrument, position);
                PositionManager.cachePosition(position);
                startTargetCheckTimer();
                System.out.println("✅ Pullback position opened: " + instrument);
            }

        } catch (Exception | KiteException e) {
            System.err.println("❌ Error in pullback buy signal: " + e.getMessage());
        }
    }

    private Position placePullbackBuyOrder(String symbol, double entryPrice, double stopLoss, double target) {
        try {
            if (useManagedEntryExecution()) {
                return placeManagedLimitBuyOrder(symbol, entryPrice, stopLoss, target, "ema_vwap_pullback", "SIM_PB_");
            }
            int quantity = AppConfig.getVWAPOptionsLotSize();
            String tradingSymbol = symbol.replace("NFO:", "");

            synchronized(apiCallLock) {
                Thread.sleep(100);

                OrderParams orderParams = new OrderParams();
                orderParams.exchange = "NFO";
                orderParams.tradingsymbol = tradingSymbol;
                orderParams.transactionType = Constants.TRANSACTION_TYPE_BUY;
                orderParams.quantity = quantity;
                orderParams.orderType = Constants.ORDER_TYPE_MARKET;
                orderParams.price = 0d;
                orderParams.product = Constants.PRODUCT_MIS;
                orderParams.validity = Constants.VALIDITY_DAY;
                orderParams.marketProtection = -1;

                Order order = kiteConnect.placeOrder(orderParams, Constants.VARIETY_REGULAR);

                if (order != null && order.orderId != null) {
                    Position position = new Position();
                    position.setTradingSymbol(symbol);
                    position.setOrderId(order.orderId);
                    position.setEntryPrice(entryPrice);
                    position.setQuantity(quantity);
                    position.setSignalType(SignalType.BUY);
                    position.setStopLoss(stopLoss);
                    position.setTarget(target);
                    position.setPatternType("ema_vwap_pullback");
                    position.setSimulated(false);
                    return position;
                }
            }
        } catch (Exception | KiteException e) {
            System.err.println("❌ Real pullback order failed for " + symbol + ": " + e.getMessage());
            if (AppConfig.isSimulateFailedOrders()) {
                System.out.println("⚠️ SIMULATION MODE: Creating simulated pullback position for " + symbol);
                Position simPosition = new Position();
                simPosition.setTradingSymbol(symbol);
                simPosition.setOrderId("SIM_PB_" + System.currentTimeMillis());
                simPosition.setEntryPrice(entryPrice);
                simPosition.setQuantity(AppConfig.getVWAPOptionsLotSize());
                simPosition.setSignalType(SignalType.BUY);
                simPosition.setStopLoss(stopLoss);
                simPosition.setTarget(target);
                simPosition.setPatternType("ema_vwap_pullback");
                simPosition.setSimulated(true);
                return simPosition;
            }
        }
        return null;
    }

    /**
     * Execute hammer reversal buy signal
     */
    public void executeHammerBuySignal(String instrument, double entryPrice, double stopLoss, double target) {
        // Double-check that we're within buying hours
        if (!canPlaceBuyOrders()) {
            System.out.println("⏸️ HAMMER BUY ORDER BLOCKED - Outside buying hours (9:45-15:15)");
            return;
        }

        try {
            System.out.println("\n🔨 EXECUTING HAMMER REVERSAL BUY ORDER");

            if (currentPositions.containsKey(instrument) || PositionManager.hasCachedPosition(instrument)) {
                System.out.println("⏸️ Position already exists - skipping");
                return;
            }

            String[] instruments = {instrument};
            Map<String, Quote> quotes;
            synchronized(apiCallLock) {
                Thread.sleep(100);
                quotes = kiteConnect.getQuote(instruments);
            }

            Quote quote = quotes.get(instrument);
            if (quote == null) {
                System.err.println("❌ Unable to get quote for: " + instrument);
                return;
            }

            double vwapPrice = quote.averagePrice;

            Position position = placeHammerOrder(instrument, entryPrice, stopLoss, target);
            if (position != null) {
                position.setPatternType("hammer_reversal");
                position.setVwap(vwapPrice);
                position.setEntryTime(new Date());
                currentPositions.put(instrument, position);
                PositionManager.cachePosition(position);
                startTargetCheckTimer();
                System.out.println("✅ Hammer reversal position opened: " + instrument);
            }

        } catch (Exception | KiteException e) {
            System.err.println("❌ Error in hammer buy signal: " + e.getMessage());
        }
    }

    /**
     * Execute breakout+retest buy signal
     */
    public void executeBreakoutRetestBuySignal(String instrument, double entryPrice, double stopLoss, double target) {
        // Double-check that we're within buying hours
        if (!canPlaceBuyOrders()) {
            System.out.println("⏸️ BREAKOUT+RETEST BUY ORDER BLOCKED - Outside buying hours (9:45-15:15)");
            return;
        }

        try {
            System.out.println("\n📊 EXECUTING BREAKOUT+RETEST BUY ORDER");

            if (currentPositions.containsKey(instrument) || PositionManager.hasCachedPosition(instrument)) {
                System.out.println("⏸️ Position already exists - skipping");
                return;
            }

            String[] instruments = {instrument};
            Map<String, Quote> quotes;
            synchronized(apiCallLock) {
                Thread.sleep(100);
                quotes = kiteConnect.getQuote(instruments);
            }

            Quote quote = quotes.get(instrument);
            if (quote == null) {
                System.err.println("❌ Unable to get quote for: " + instrument);
                return;
            }

            double vwapPrice = quote.averagePrice;

            Position position = placeBreakoutRetestOrder(instrument, entryPrice, stopLoss, target);
            if (position != null) {
                position.setPatternType("breakout_retest");
                position.setVwap(vwapPrice);
                position.setEntryTime(new Date());
                currentPositions.put(instrument, position);
                PositionManager.cachePosition(position);
                startTargetCheckTimer();
                System.out.println("✅ Breakout+Retest position opened: " + instrument);
            }

        } catch (Exception | KiteException e) {
            System.err.println("❌ Error in breakout+retest buy signal: " + e.getMessage());
        }
    }

    private Position placeHammerOrder(String symbol, double entryPrice, double stopLoss, double target) {
        try {
            if (useManagedEntryExecution()) {
                return placeManagedLimitBuyOrder(symbol, entryPrice, stopLoss, target, "hammer_reversal", "SIM_HM_");
            }
            int quantity = AppConfig.getVWAPOptionsLotSize();
            String tradingSymbol = symbol.replace("NFO:", "");

            synchronized(apiCallLock) {
                Thread.sleep(100);

                OrderParams orderParams = new OrderParams();
                orderParams.exchange = "NFO";
                orderParams.tradingsymbol = tradingSymbol;
                orderParams.transactionType = Constants.TRANSACTION_TYPE_BUY;
                orderParams.quantity = quantity;
                orderParams.orderType = Constants.ORDER_TYPE_MARKET;
                orderParams.price = 0d;
                orderParams.product = Constants.PRODUCT_MIS;
                orderParams.validity = Constants.VALIDITY_DAY;
                orderParams.marketProtection = -1;

                Order order = kiteConnect.placeOrder(orderParams, Constants.VARIETY_REGULAR);

                if (order != null && order.orderId != null) {
                    Position position = new Position();
                    position.setTradingSymbol(symbol);
                    position.setOrderId(order.orderId);
                    position.setEntryPrice(entryPrice);
                    position.setQuantity(quantity);
                    position.setSignalType(SignalType.BUY);
                    position.setStopLoss(stopLoss);
                    position.setTarget(target);
                    position.setPatternType("hammer_reversal");
                    position.setSimulated(false);
                    return position;
                }
            }
        } catch (Exception | KiteException e) {
            System.err.println("❌ Real hammer order failed for " + symbol + ": " + e.getMessage());
            if (AppConfig.isSimulateFailedOrders()) {
                System.out.println("⚠️ SIMULATION MODE: Creating simulated hammer position for " + symbol);
                Position simPosition = new Position();
                simPosition.setTradingSymbol(symbol);
                simPosition.setOrderId("SIM_HM_" + System.currentTimeMillis());
                simPosition.setEntryPrice(entryPrice);
                simPosition.setQuantity(AppConfig.getVWAPOptionsLotSize());
                simPosition.setSignalType(SignalType.BUY);
                simPosition.setStopLoss(stopLoss);
                simPosition.setTarget(target);
                simPosition.setPatternType("hammer_reversal");
                simPosition.setSimulated(true);
                return simPosition;
            }
        }
        return null;
    }

    private Position placeBreakoutRetestOrder(String symbol, double entryPrice, double stopLoss, double target) {
        try {
            if (useManagedEntryExecution()) {
                return placeManagedLimitBuyOrder(symbol, entryPrice, stopLoss, target, "breakout_retest", "SIM_BR_");
            }
            int quantity = AppConfig.getVWAPOptionsLotSize();
            String tradingSymbol = symbol.replace("NFO:", "");

            synchronized(apiCallLock) {
                Thread.sleep(100);

                OrderParams orderParams = new OrderParams();
                orderParams.exchange = "NFO";
                orderParams.tradingsymbol = tradingSymbol;
                orderParams.transactionType = Constants.TRANSACTION_TYPE_BUY;
                orderParams.quantity = quantity;
                orderParams.orderType = Constants.ORDER_TYPE_MARKET;
                orderParams.price = 0d;
                orderParams.product = Constants.PRODUCT_MIS;
                orderParams.validity = Constants.VALIDITY_DAY;
                orderParams.marketProtection = -1;

                Order order = kiteConnect.placeOrder(orderParams, Constants.VARIETY_REGULAR);

                if (order != null && order.orderId != null) {
                    Position position = new Position();
                    position.setTradingSymbol(symbol);
                    position.setOrderId(order.orderId);
                    position.setEntryPrice(entryPrice);
                    position.setQuantity(quantity);
                    position.setSignalType(SignalType.BUY);
                    position.setStopLoss(stopLoss);
                    position.setTarget(target);
                    position.setPatternType("breakout_retest");
                    position.setSimulated(false);
                    return position;
                }
            }
        } catch (Exception | KiteException e) {
            System.err.println("❌ Real breakout+retest order failed for " + symbol + ": " + e.getMessage());
            if (AppConfig.isSimulateFailedOrders()) {
                System.out.println("⚠️ SIMULATION MODE: Creating simulated breakout+retest position for " + symbol);
                Position simPosition = new Position();
                simPosition.setTradingSymbol(symbol);
                simPosition.setOrderId("SIM_BR_" + System.currentTimeMillis());
                simPosition.setEntryPrice(entryPrice);
                simPosition.setQuantity(AppConfig.getVWAPOptionsLotSize());
                simPosition.setSignalType(SignalType.BUY);
                simPosition.setStopLoss(stopLoss);
                simPosition.setTarget(target);
                simPosition.setPatternType("breakout_retest");
                simPosition.setSimulated(true);
                return simPosition;
            }
        }
        return null;
    }

    public void executeMorningStarBuySignal(String instrument, double entryPrice, double stopLoss, double target) {
        if (!canPlaceBuyOrders()) {
            System.out.println("⏸️ MORNING STAR BUY ORDER BLOCKED - Outside buying hours (9:45-15:15)");
            return;
        }

        try {
            System.out.println("\n⭐ EXECUTING MORNING STAR BUY ORDER");

            if (currentPositions.containsKey(instrument) || PositionManager.hasCachedPosition(instrument)) {
                System.out.println("⏸️ Position already exists - skipping");
                return;
            }

            String[] instruments = {instrument};
            Map<String, Quote> quotes;
            synchronized(apiCallLock) {
                Thread.sleep(100);
                quotes = kiteConnect.getQuote(instruments);
            }

            Quote quote = quotes.get(instrument);
            if (quote == null) {
                System.err.println("❌ Unable to get quote for: " + instrument);
                return;
            }

            double vwapPrice = quote.averagePrice;

            Position position = placeMorningStarOrder(instrument, entryPrice, stopLoss, target);
            if (position != null) {
                position.setPatternType("morning_star");
                position.setVwap(vwapPrice);
                position.setEntryTime(new Date());
                currentPositions.put(instrument, position);
                PositionManager.cachePosition(position);
                startTargetCheckTimer();
                System.out.println("✅ Morning Star position opened: " + instrument);
            }

        } catch (Exception | KiteException e) {
            System.err.println("❌ Error in morning star buy signal: " + e.getMessage());
        }
    }

    private Position placeMorningStarOrder(String symbol, double entryPrice, double stopLoss, double target) {
        try {
            if (useManagedEntryExecution()) {
                return placeManagedLimitBuyOrder(symbol, entryPrice, stopLoss, target, "morning_star", "SIM_MS_");
            }
            int quantity = AppConfig.getVWAPOptionsLotSize();
            String tradingSymbol = symbol.replace("NFO:", "");

            synchronized(apiCallLock) {
                Thread.sleep(100);

                OrderParams orderParams = new OrderParams();
                orderParams.exchange = "NFO";
                orderParams.tradingsymbol = tradingSymbol;
                orderParams.transactionType = Constants.TRANSACTION_TYPE_BUY;
                orderParams.quantity = quantity;
                orderParams.orderType = Constants.ORDER_TYPE_MARKET;
                orderParams.price = 0d;
                orderParams.product = Constants.PRODUCT_MIS;
                orderParams.validity = Constants.VALIDITY_DAY;
                orderParams.marketProtection = -1;

                Order order = kiteConnect.placeOrder(orderParams, Constants.VARIETY_REGULAR);

                if (order != null && order.orderId != null) {
                    Position position = new Position();
                    position.setTradingSymbol(symbol);
                    position.setOrderId(order.orderId);
                    position.setEntryPrice(entryPrice);
                    position.setQuantity(quantity);
                    position.setSignalType(SignalType.BUY);
                    position.setStopLoss(stopLoss);
                    position.setTarget(target);
                    position.setPatternType("morning_star");
                    position.setSimulated(false);
                    return position;
                }
            }
        } catch (Exception | KiteException e) {
            System.err.println("❌ Real morning star order failed for " + symbol + ": " + e.getMessage());
            if (AppConfig.isSimulateFailedOrders()) {
                System.out.println("⚠️ SIMULATION MODE: Creating simulated morning star position for " + symbol);
                Position simPosition = new Position();
                simPosition.setTradingSymbol(symbol);
                simPosition.setOrderId("SIM_MS_" + System.currentTimeMillis());
                simPosition.setEntryPrice(entryPrice);
                simPosition.setQuantity(AppConfig.getVWAPOptionsLotSize());
                simPosition.setSignalType(SignalType.BUY);
                simPosition.setStopLoss(stopLoss);
                simPosition.setTarget(target);
                simPosition.setPatternType("morning_star");
                simPosition.setSimulated(true);
                return simPosition;
            }
        }
        return null;
    }

    /**
     * Start breakout monitors
     */
    public void startMorningStarBreakoutMonitor(String instrument, double breakoutLevel, double stopLoss, double target) {
        startBreakoutMonitor(instrument, breakoutLevel, stopLoss, target, "morning_star", 3);
    }
    public void startHammerBreakoutMonitor(String instrument, double breakoutLevel, double stopLoss, double target) {
        startBreakoutMonitor(instrument, breakoutLevel, stopLoss, target, "hammer", 3);
    }
    public void startEngulfingBreakoutMonitor(String instrument, double breakoutLevel, double stopLoss, double target) {
        startBreakoutMonitor(instrument, breakoutLevel, stopLoss, target, "engulfing", 3);
    }
    public void startPullbackBreakoutMonitor(String instrument, double breakoutLevel, double stopLoss, double target) {
        startBreakoutMonitor(instrument, breakoutLevel, stopLoss, target, "pullback", 5);
    }
    public void startCrossoverBreakoutMonitor(String instrument, double breakoutLevel) {
        startBreakoutMonitor(instrument, breakoutLevel, 0, 0, "crossover", 5);
    }
    public void startReversalBreakoutMonitor(String instrument, double breakoutLevel, double stopLossLevel) {
        startBreakoutMonitor(instrument, breakoutLevel, stopLossLevel, 0, "reversal", 5);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PATCH for TradingStrategyEngine.java
    //
    // Add the two blocks below into TradingStrategyEngine.java
    // following the same pattern as executeHammerBuySignal() + placeHammerOrder()
    // ═══════════════════════════════════════════════════════════════════════════


    // ───────────────────────────────────────────────────────────────────────────
    // BLOCK 1 – Public execution method  (add after executeBreakoutRetestBuySignal)
    // ───────────────────────────────────────────────────────────────────────────

    /**
     * Execute buy signal generated by the Bullish Engulfing strategy (Case 5).
     */
    public void executeBullishEngulfingBuySignal(String instrument,
                                                 double entryPrice,
                                                 double stopLoss,
                                                 double target) {
        if (!canPlaceBuyOrders()) {
            System.out.println("⏸️ BULLISH ENGULFING BUY ORDER BLOCKED - Outside buying hours (9:45-15:15)");
            return;
        }

        try {
            System.out.println("\n🕯️ EXECUTING BULLISH ENGULFING BUY ORDER");

            if (currentPositions.containsKey(instrument) || PositionManager.hasCachedPosition(instrument)) {
                System.out.println("⏸️ Position already exists - skipping");
                return;
            }

            String[] instruments = {instrument};
            Map<String, Quote> quotes;
            synchronized (apiCallLock) {
                Thread.sleep(100);
                quotes = kiteConnect.getQuote(instruments);
            }

            Quote quote = quotes.get(instrument);
            if (quote == null) {
                System.err.println("❌ Unable to get quote for: " + instrument);
                return;
            }

            double vwapPrice = quote.averagePrice;

            Position position = placeBullishEngulfingOrder(instrument, entryPrice, stopLoss, target);
            if (position != null) {
                position.setPatternType("bullish_engulfing");
                position.setVwap(vwapPrice);
                position.setEntryTime(new Date());
                currentPositions.put(instrument, position);
                PositionManager.cachePosition(position);
                startTargetCheckTimer();
                System.out.println("✅ Bullish engulfing position opened: " + instrument);
            }

        } catch (Exception | KiteException e) {
            System.err.println("❌ Error in bullish engulfing buy signal: " + e.getMessage());
        }
    }


    // ───────────────────────────────────────────────────────────────────────────
    // BLOCK 2 – Private order placement method  (add after placeBreakoutRetestOrder)
    // ───────────────────────────────────────────────────────────────────────────

    private Position placeBullishEngulfingOrder(String symbol, double entryPrice, double stopLoss, double target) {
        try {
            if (useManagedEntryExecution()) {
                return placeManagedLimitBuyOrder(symbol, entryPrice, stopLoss, target, "bullish_engulfing", "SIM_BE_");
            }
            int quantity = AppConfig.getVWAPOptionsLotSize();
            String tradingSymbol = symbol.replace("NFO:", "");

            synchronized(apiCallLock) {
                Thread.sleep(100);

                OrderParams orderParams = new OrderParams();
                orderParams.exchange = "NFO";
                orderParams.tradingsymbol = tradingSymbol;
                orderParams.transactionType = Constants.TRANSACTION_TYPE_BUY;
                orderParams.quantity = quantity;
                orderParams.orderType = Constants.ORDER_TYPE_MARKET;
                orderParams.price = 0d;
                orderParams.product = Constants.PRODUCT_MIS;
                orderParams.validity = Constants.VALIDITY_DAY;
                orderParams.marketProtection = -1;

                Order order = kiteConnect.placeOrder(orderParams, Constants.VARIETY_REGULAR);

                if (order != null && order.orderId != null) {
                    Position position = new Position();
                    position.setTradingSymbol(symbol);
                    position.setOrderId(order.orderId);
                    position.setEntryPrice(entryPrice);
                    position.setQuantity(quantity);
                    position.setSignalType(SignalType.BUY);
                    position.setStopLoss(stopLoss);
                    position.setTarget(target);
                    position.setPatternType("bullish_engulfing");
                    position.setSimulated(false);
                    return position;
                }
            }
        } catch (Exception | KiteException e) {
            System.err.println("❌ Real bullish engulfing order failed for " + symbol + ": " + e.getMessage());
            if (AppConfig.isSimulateFailedOrders()) {
                System.out.println("⚠️ SIMULATION MODE: Creating simulated bullish engulfing position for " + symbol);
                Position simPosition = new Position();
                simPosition.setTradingSymbol(symbol);
                simPosition.setOrderId("SIM_BE_" + System.currentTimeMillis());
                simPosition.setEntryPrice(entryPrice);
                simPosition.setQuantity(AppConfig.getVWAPOptionsLotSize());
                simPosition.setSignalType(SignalType.BUY);
                simPosition.setStopLoss(stopLoss);
                simPosition.setTarget(target);
                simPosition.setPatternType("bullish_engulfing");
                simPosition.setSimulated(true);
                return simPosition;
            }
        }
        return null;
    }

    private Position placeManagedLimitBuyOrder(String symbol, double triggerEntryPrice, double stopLoss,
                                               double target, String patternType,
                                               String simulationOrderPrefix) throws Exception, KiteException {
        int quantity = AppConfig.getVWAPOptionsLotSize();
        String tradingSymbol = symbol.replace("NFO:", "");

        synchronized(apiCallLock) {
            Thread.sleep(100);

            String[] instruments = {symbol};
            Map<String, Quote> quotes = kiteConnect.getQuote(instruments);
            Quote quote = quotes.get(symbol);
            if (quote == null || quote.lastPrice <= 0) {
                System.err.println("âŒ Unable to get live price for: " + symbol);
                return null;
            }

            double livePrice = quote.lastPrice;
            double effectiveEntryPrice = Math.max(triggerEntryPrice, livePrice);
            if (!isEntrySlippageAcceptable(symbol, triggerEntryPrice, effectiveEntryPrice)) {
                return null;
            }

            double limitPrice = calculateMarketableLimitPrice(effectiveEntryPrice);
            if (!hasAcceptableRewardRiskAfterSlippage(symbol, limitPrice, stopLoss, target)) {
                return null;
            }

            OrderParams orderParams = new OrderParams();
            orderParams.exchange = "NFO";
            orderParams.tradingsymbol = tradingSymbol;
            orderParams.transactionType = Constants.TRANSACTION_TYPE_BUY;
            orderParams.quantity = quantity;
            orderParams.orderType = Constants.ORDER_TYPE_LIMIT;
            orderParams.price = limitPrice;
            orderParams.product = Constants.PRODUCT_MIS;
            orderParams.validity = Constants.VALIDITY_DAY;

            System.out.println("ðŸ“Œ Using marketable LIMIT buy order");
            System.out.println("   Trigger Entry: " + String.format("%.2f", triggerEntryPrice));
            System.out.println("   Live Price: " + String.format("%.2f", livePrice));
            System.out.println("   Limit Price: " + String.format("%.2f", limitPrice));

            Order order = kiteConnect.placeOrder(orderParams, Constants.VARIETY_REGULAR);

            if (order != null && order.orderId != null) {
                Position position = new Position();
                position.setTradingSymbol(symbol);
                position.setOrderId(order.orderId);
                position.setEntryPrice(limitPrice);
                position.setQuantity(quantity);
                position.setSignalType(SignalType.BUY);
                position.setStopLoss(stopLoss);
                position.setTarget(target);
                position.setPatternType(patternType);
                position.setSimulated(false);
                return position;
            }
        }

        if (AppConfig.isSimulateFailedOrders()) {
            return createSimulatedPosition(symbol, triggerEntryPrice, stopLoss, target, patternType, simulationOrderPrefix);
        }
        return null;
    }

    private Position createSimulatedPosition(String symbol, double entryPrice, double stopLoss,
                                             double target, String patternType, String orderPrefix) {
        System.out.println("âš ï¸ SIMULATION MODE: Creating simulated position for " + symbol);
        Position simPosition = new Position();
        simPosition.setTradingSymbol(symbol);
        simPosition.setOrderId(orderPrefix + System.currentTimeMillis());
        simPosition.setEntryPrice(entryPrice);
        simPosition.setQuantity(AppConfig.getVWAPOptionsLotSize());
        simPosition.setSignalType(SignalType.BUY);
        simPosition.setStopLoss(stopLoss);
        simPosition.setTarget(target);
        simPosition.setPatternType(patternType);
        simPosition.setSimulated(true);
        return simPosition;
    }

    private boolean useManagedEntryExecution() {
        return true;
    }

    private boolean isEntrySlippageAcceptable(String symbol, double triggerEntryPrice, double effectiveEntryPrice) {
        if (triggerEntryPrice <= 0) {
            return true;
        }

        double slippagePercent = ((effectiveEntryPrice - triggerEntryPrice) / triggerEntryPrice) * 100.0;
        double maxAllowedSlippage = AppConfig.getMaxEntrySlippagePercent();

        System.out.println("   Entry Slippage: " + String.format("%.2f", slippagePercent) + "%");
        System.out.println("   Max Allowed Slippage: " + String.format("%.2f", maxAllowedSlippage) + "%");

        if (slippagePercent > maxAllowedSlippage) {
            System.out.println("â¸ï¸ Skipping " + symbol + " - entry slippage too high");
            return false;
        }
        return true;
    }

    private boolean hasAcceptableRewardRiskAfterSlippage(String symbol, double adjustedEntryPrice,
                                                         double stopLoss, double target) {
        if (stopLoss <= 0 || target <= 0) {
            return true;
        }

        double risk = adjustedEntryPrice - stopLoss;
        double reward = target - adjustedEntryPrice;
        if (risk <= 0 || reward <= 0) {
            System.out.println("â¸ï¸ Skipping " + symbol + " - invalid post-slippage trade structure");
            return false;
        }

        double rewardRisk = reward / risk;
        double minRewardRisk = AppConfig.getMinRewardRiskAfterSlippage();

        System.out.println("   Reward/Risk After Slippage: " + String.format("%.2f", rewardRisk));
        System.out.println("   Minimum Required Reward/Risk: " + String.format("%.2f", minRewardRisk));

        if (rewardRisk < minRewardRisk) {
            System.out.println("â¸ï¸ Skipping " + symbol + " - reward/risk degraded after slippage");
            return false;
        }
        return true;
    }

    private double calculateMarketableLimitPrice(double referencePrice) {
        double limitBufferPercent = AppConfig.getMarketableLimitBufferPercent();
        double rawLimitPrice = referencePrice * (1 + limitBufferPercent / 100.0);
        return roundToTick(rawLimitPrice);
    }

    private double roundToTick(double price) {
        double tickSize = 0.05;
        return Math.ceil(price / tickSize) * tickSize;
    }


    /**
     * Get KiteConnect instance
     */
    public KiteConnect getKiteConnect() {
        return kiteConnect;
    }

    public boolean shouldSkipInstrument(String instrument) {
        if (currentPositions.containsKey(instrument)) return true;
        if (PositionManager.hasCachedPosition(instrument)) return true;

        synchronized(activeMonitors) {
            BreakoutMonitor monitor = activeMonitors.get(instrument);
            if (monitor != null && monitor.isRunning()) return true;
        }

        return false;
    }

    public RealTimeCandleBuilder getRealTimeCandleBuilder() {
        return realTimeCandleBuilder;
    }

    public boolean hasNoOpenPositions() {
        synchronized(currentPositions) {
            return currentPositions.isEmpty();
        }
    }

    public void displayMarketStatus() {
        System.out.println("\n📈 MARKET STATUS:");
        System.out.println("   - Last Executed Case: " + (lastExecutedCase > 0 ? "Case " + lastExecutedCase : "None"));
        System.out.println("   - Buy Orders Allowed: " + (canPlaceBuyOrders ? "YES (9:45-15:15)" : "NO"));
        System.out.println("   - Daily P&L: ₹" + String.format("%.2f", pnlManager.getTotalDailyPnL()));
        System.out.println("   - Open Positions: " + currentPositions.size());
        System.out.println("   - Active Monitors: " + activeMonitors.size());
        System.out.println("   - Real-time Candles: " + (realTimeCandleBuilder.isRunning() ? "ACTIVE" : "INACTIVE"));
    }

    private void startTokenRefreshTimer() {
        if (isTokenRefreshRunning) return;

        tokenRefreshTimer = new Timer();
        long interval = 30 * 60 * 1000L;

        tokenRefreshTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                refreshTokensIfNeeded();
            }
        }, interval, interval);

        isTokenRefreshRunning = true;
    }

    private void refreshTokensIfNeeded() {
        try {
            synchronized(tokenRefreshLock) {
                double currentSpot = getNiftySpotPrice();
                double priceChange = Math.abs(currentSpot - lastPreloadedSpotPrice);

                if (priceChange >= 50.0) {
                    System.out.println("🔄 Refreshing option tokens (price moved by " + String.format("%.2f", priceChange) + " points)");
                    preloadOptionTokens(currentSpot);
                    lastPreloadedSpotPrice = currentSpot;
                }
            }
        } catch (Exception | KiteException e) {
            System.err.println("❌ Error in token refresh: " + e.getMessage());
        }
    }

    public boolean isPatternBuyTimeAllowed(String patternType) {
        Calendar cal = Calendar.getInstance();
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        int minute = cal.get(Calendar.MINUTE);
        int currentMinutes = hour * 60 + minute;

        // End time for all patterns: 3:15 PM
        if (currentMinutes > 15 * 60 + 15) return false;

        int startMinutes;
        switch (patternType.toLowerCase()) {
            case "hammer":
            case "engulfing":
            case "pullback":
            case "crossover":
            case "morning_star":
                startMinutes = 9 * 60 + 45;   // 9:45 AM
                break;
            case "reversal":
                startMinutes = 10 * 60 + 0;   // 10:00 AM
                break;
            default:
                startMinutes = 9 * 60 + 45;
        }
        return currentMinutes >= startMinutes;
    }

    /**
     * Stop all trading
     */
    public void stopTrading() {
        try {
            if (tokenRefreshTimer != null) {
                tokenRefreshTimer.cancel();
                tokenRefreshTimer = null;
            }

            for (BreakoutMonitor monitor : activeMonitors.values()) {
                monitor.stop();
            }
            activeMonitors.clear();

            if (targetCheckTimer != null) {
                targetCheckTimer.cancel();
                targetCheckTimer = null;
            }

            if (realTimeCandleBuilder != null) {
                realTimeCandleBuilder.stop();
            }

            if (!currentPositions.isEmpty()) {
                System.out.println("🔄 Closing all open positions...");
                for (String symbol : new ArrayList<>(currentPositions.keySet())) {
                    closePositionDueToStopLoss(symbol);
                }
            }

            PositionManager.clearAllPositions();
            System.out.println("🛑 Trading stopped");

        } catch (Exception e) {
            System.err.println("❌ Error stopping trading: " + e.getMessage());
        }
    }

    // Add this method to TradingStrategyEngine.java
    public void closePosition(String symbol, String reason) {
        Position position = currentPositions.get(symbol);
        if (position == null) {
            position = PositionManager.getCachedPosition(symbol);
        }
        if (position == null) {
            System.err.println("Cannot close position – not found: " + symbol);
            return;
        }

        try {
            double exitPrice;
            boolean isSimulated = position.isSimulated();

            // Get current market price
            String[] instruments = {symbol};
            Map<String, Quote> quotes;
            synchronized(apiCallLock) {
                quotes = kiteConnect.getQuote(instruments);
            }
            Quote quote = quotes.get(symbol);
            if (quote == null) {
                System.err.println("Cannot get quote for " + symbol);
                return;
            }
            exitPrice = quote.lastPrice;

            if (!isSimulated) {
                // Real sell order
                OrderParams orderParams = new OrderParams();
                orderParams.exchange = "NFO";
                orderParams.tradingsymbol = symbol.replace("NFO:", "");
                orderParams.transactionType = Constants.TRANSACTION_TYPE_SELL;
                orderParams.quantity = position.getQuantity();
                orderParams.orderType = Constants.ORDER_TYPE_MARKET;
                orderParams.product = Constants.PRODUCT_MIS;
                orderParams.validity = Constants.VALIDITY_DAY;
                orderParams.marketProtection = -1;

                Order exitOrder = kiteConnect.placeOrder(orderParams, Constants.VARIETY_REGULAR);
                if (exitOrder == null || exitOrder.orderId == null) {
                    System.err.println("Failed to place sell order for " + symbol);
                    return;
                }
                System.out.println("🔒 Position closed (REAL) for " + symbol + " at " + exitPrice + " – reason: " + reason);
            } else {
                System.out.println("🧪 SIMULATED position closed for " + symbol + " at " + exitPrice + " – reason: " + reason);
            }

            double pnl = (exitPrice - position.getEntryPrice()) * position.getQuantity();
            pnlManager.addToDailyPnL(pnl);
            pnlManager.addTradeLog(symbol, position.getEntryPrice(), exitPrice, pnl,
                    reason, position.getPatternType(), isSimulated);

            currentPositions.remove(symbol);
            PositionManager.removeCachedPosition(symbol);

        } catch (Exception | KiteException e) {
            System.err.println("Error closing position " + symbol + ": " + e.getMessage());
        }
    }

    public boolean hasOpenPosition(String symbol) {
        return currentPositions.containsKey(symbol) || PositionManager.hasCachedPosition(symbol);
    }

    public Position getPosition(String symbol) {
        Position pos = currentPositions.get(symbol);
        if (pos == null) pos = PositionManager.getCachedPosition(symbol);
        return pos;
    }

    // ====================================================================
    // BREAKOUT MONITOR INNER CLASS
    // ====================================================================

    private class BreakoutMonitor {
        private final String instrument;
        private final double breakoutLevel;
        private final double stopLoss;
        private final double target;
        private final String patternType;
        private final long durationSeconds;
        private final long checkIntervalSeconds;
        private final Date startTime;
        private volatile boolean running = false;
        private volatile boolean buySignalGenerated = false;
        private ScheduledExecutorService scheduler;
        private ScheduledFuture<?> monitorFuture;

        BreakoutMonitor(String instrument, double breakoutLevel, double stopLoss, double target,
                        String patternType, long durationSeconds, long checkIntervalSeconds) {
            this.instrument = instrument;
            this.breakoutLevel = breakoutLevel;
            this.stopLoss = stopLoss;
            this.target = target;
            this.patternType = patternType;
            this.durationSeconds = durationSeconds;
            this.checkIntervalSeconds = checkIntervalSeconds;
            this.startTime = new Date();
        }

        void start() {
            if (running) return;
            running = true;
            scheduler = Executors.newScheduledThreadPool(1);
            scheduler.execute(this::checkBreakout);
            monitorFuture = scheduler.scheduleAtFixedRate(this::checkBreakout,
                    checkIntervalSeconds, checkIntervalSeconds, TimeUnit.SECONDS);
            scheduler.schedule(this::stop, durationSeconds, TimeUnit.SECONDS);
            System.out.printf("   🔄 [%s] Monitor started for %s (%.0f min, %ds interval)%n",
                    patternType, instrument, durationSeconds/60.0, checkIntervalSeconds);
        }

        private void checkBreakout() {
            if (!running || buySignalGenerated) return;
            try {
                String[] arr = {instrument};
                Map<String, Quote> quotes = kiteConnect.getQuote(arr);
                Quote q = quotes.get(instrument);
                if (q == null) return;
                double price = q.lastPrice;
                if (price > breakoutLevel) {
                    buySignalGenerated = true;
                    System.out.printf("🚀 [%s] Breakout at %.2f (level %.2f)%n",
                            patternType, price, breakoutLevel);
                    // Execute the appropriate buy order
                    switch (patternType) {
                        case "hammer":
                            double hammerTarget = target > 0 ? target : price * 1.20;
                            executeHammerBuySignal(instrument, price, stopLoss, hammerTarget);
                            break;
                        case "engulfing":
                            double engulfTarget = target > 0 ? target : price * 1.20;
                            executeBullishEngulfingBuySignal(instrument, price, stopLoss, engulfTarget);
                            break;
                        case "pullback":
                            double pbTarget = target > 0 ? target : price * 1.20;
                            executePullbackBuySignal(instrument, price, stopLoss, pbTarget);
                            break;
                        case "crossover":
                            executeBuySignal(instrument, "crossover");
                            break;
                        case "morning_star":
                            double msTarget = target > 0 ? target : price * 1.20;
                            executeMorningStarBuySignal(instrument, price, stopLoss, msTarget);
                            break;
                        default:
                            executeBuySignal(instrument, patternType);
                    }
                    stop();
                }
            } catch (Exception | KiteException e) {
                System.err.println("Monitor error: " + e.getMessage());
            }
        }

        void stop() {
            if (!running) return;
            running = false;
            if (monitorFuture != null) monitorFuture.cancel(false);
            if (scheduler != null) scheduler.shutdownNow();
            System.out.printf("   🛑 [%s] Monitor stopped for %s%n", patternType, instrument);
        }

        boolean isRunning() { return running; }
        boolean hasExpired() { return System.currentTimeMillis() - startTime.getTime() >= durationSeconds * 1000; }
        public long getRemainingSeconds() { return Math.max(0, durationSeconds - (System.currentTimeMillis() - startTime.getTime()) / 1000); }
        boolean isBuySignalGenerated() { return buySignalGenerated; }
    }
}
