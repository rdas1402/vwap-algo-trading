package com.trading.strategy;

import com.trading.config.AppConfig;
import com.trading.config.PnLManager;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.kiteconnect.utils.Constants;
import com.zerodhatech.models.*;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class VWAPOptionsStrategy {
    private KiteConnect kiteConnect;
    private final BatchVWAPAnalyzer vwapAnalyzer;
    private final Map<String, Position> currentPositions;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss");
    private final Map<String, Long> symbolToTokenMap;
    private final Map<Long, String> tokenToSymbolMap;
    private Timer targetCheckTimer;
    private boolean isTargetCheckRunning = false;
    private HistoricalDataManager historicalDataManager;

    // NEW: Real-time candle builder
    private RealTimeCandleBuilder realTimeCandleBuilder;
    private Set<String> trackedInstruments; // Using Set to avoid duplicates

    // Track active breakout monitors
    private final Map<String, BreakoutMonitor> activeMonitors = new ConcurrentHashMap<>();

    // P&L Manager
    private final PnLManager pnlManager = PnLManager.getInstance();

    // ADD THIS: Store reversal pattern data
    private final Map<String, ReversalPatternData> reversalPatternData = new ConcurrentHashMap<>();

    // API call synchronization lock
    private final Object apiCallLock = new Object();

    // Add this inner class definition
    private static class ReversalPatternData {
        final double breakoutLevel;
        final double stopLossLevel;

        ReversalPatternData(double breakoutLevel, double stopLossLevel) {
            this.breakoutLevel = breakoutLevel;
            this.stopLossLevel = stopLossLevel;
        }
    }

    public VWAPOptionsStrategy() {
        initializeKiteConnect();
        this.historicalDataManager = new HistoricalDataManager(kiteConnect);
        this.vwapAnalyzer = new BatchVWAPAnalyzer(kiteConnect, historicalDataManager);
        this.currentPositions = new HashMap<>();
        this.symbolToTokenMap = new HashMap<>();
        this.tokenToSymbolMap = new HashMap<>();
        this.isTargetCheckRunning = false;

        // Initialize RealTimeCandleBuilder
        this.realTimeCandleBuilder = new RealTimeCandleBuilder(kiteConnect);
        this.trackedInstruments = new HashSet<>(); // Using Set to avoid duplicates

        // Initialize with Nifty spot to get all instruments
        try {
            double niftySpot = getNiftySpotPrice();
            preloadOptionTokens(niftySpot);
        } catch (Exception | KiteException e) {
            System.err.println("❌ Error initializing instruments: " + e.getMessage());
        }
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

            System.out.println("✅ VWAP Options Strategy initialized");

        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize VWAP Options Strategy: " + e.getMessage(), e);
        }
    }

    /**
     * Main method to execute VWAP Options Trading in batch mode every 5 minutes
     */
    public void executeTradingCycle() {
        try {
            System.out.println("🔄 Executing VWAP Options Trading Cycle at: " + dateFormat.format(new Date()));

            // NEW: Check if we're profitable by 2:45 PM with no positions
            if (shouldEndTradingDayEarly()) {
                System.out.println("💰 ENDING TRADING DAY EARLY - In profit by 2:45 PM with no open positions");
                System.out.println("📊 Daily P&L: ₹" + String.format("%.2f", pnlManager.getTotalDailyPnL()));
                System.out.println("✅ Trading cycle skipped - Day ended early");
                return;
            }

            // NEW: Wait for candle finalization if needed
            if (realTimeCandleBuilder != null) {
                boolean canProceed = realTimeCandleBuilder.waitForCandleFinalizationAndProceed();

                if (!canProceed) {
                    System.out.println("❌ Cannot proceed with trading cycle - candle finalization issue");
                    return;
                }
            }

            System.out.println("🚀 Starting VWAP trading analysis with fresh candle data...");

            // STEP 0.5: Clean up expired or unnecessary breakout monitors
            cleanupBreakoutMonitors(); // INSTEAD OF killAllBreakoutMonitors()
            System.out.println("📊 Active breakout monitors after cleanup: " + activeMonitors.size());

            // STEP 1: Ensure real-time candle builder is running
            ensureRealTimeCandleBuilderRunning();

            // STEP 2: Close any positions that hit stop loss/target
            // FIXED: Check return value correctly
            boolean positionsClosed = manageExistingPositions();

            // FIXED: If we have positions AND they were NOT closed, skip signal generation
            if (!currentPositions.isEmpty() && !positionsClosed) {
                System.out.println("⏸️ Open positions exist (" + currentPositions.size() + ") and no positions were closed.");
                System.out.println("   Managing positions only - skipping new signal generation.");
                displayMarketStatus();
                System.out.println("✅ Position management completed.");
                System.out.println("⏰ Next execution in " + AppConfig.getTradingIntervalMinutes() + " minutes.");
                System.out.println("=".repeat(80) + "\n");
                return; // Skip new signal generation
            }

            // If positions were closed OR we have no positions, continue with signal generation
            if (positionsClosed) {
                System.out.println("✅ Positions were closed. Now have capacity for new positions.");
                System.out.println("   Current positions: " + currentPositions.size());
            }

            // STEP 3: Skip if max positions reached
            if (currentPositions.size() >= AppConfig.getVWAPOptionsMaxPositions()) {
                System.out.println("⏸️ Max positions reached: " + currentPositions.size());
                displayMarketStatus();
                System.out.println("✅ Trading cycle completed.");
                System.out.println("⏰ Next execution in " + AppConfig.getTradingIntervalMinutes() + " minutes.");
                System.out.println("=".repeat(80) + "\n");
                return;
            }

            // NEW: Skip if we have active breakout monitors running
            synchronized(activeMonitors) {
                if (!activeMonitors.isEmpty()) {
                    // Check if any monitors are about to expire (less than 1 minute left)
                    boolean allMonitorsNearExpiry = true;
                    for (BreakoutMonitor monitor : activeMonitors.values()) {
                        if (monitor.isRunning() && monitor.getRemainingTime() > 60) {
                            allMonitorsNearExpiry = false;
                            break;
                        }
                    }

                    if (!allMonitorsNearExpiry) {
                        System.out.println("⏸️ " + activeMonitors.size() + " breakout monitor(s) active. Skipping new signal generation.");
                        for (BreakoutMonitor monitor : activeMonitors.values()) {
                            System.out.println("   - " + monitor.getStatus());
                        }
                        displayMarketStatus();
                        System.out.println("✅ Trading cycle completed.");
                        System.out.println("⏰ Next execution in " + AppConfig.getTradingIntervalMinutes() + " minutes.");
                        System.out.println("=".repeat(80) + "\n");
                        return;
                    } else {
                        System.out.println("⚠️ Active monitors but all near expiry (<1 minute). Continuing with signal generation.");
                    }
                }
            }

            // STEP 4: Find suitable options
            Map<String, String> selectedOptions = findOptionsNearTargetPrice();
            if (selectedOptions.isEmpty()) {
                System.out.println("❌ No suitable options found near target price");
                displayMarketStatus();
                System.out.println("✅ Trading cycle completed.");
                System.out.println("⏰ Next execution in " + AppConfig.getTradingIntervalMinutes() + " minutes.");
                System.out.println("=".repeat(80) + "\n");
                return;
            }

            String ceOption = selectedOptions.get("CE");
            String peOption = selectedOptions.get("PE");

            System.out.println("✅ Selected Options for this cycle:");
            System.out.println("   CE: " + (ceOption != null ? ceOption : "None"));
            System.out.println("   PE: " + (peOption != null ? peOption : "None"));

            // STEP 5: Analyze using real-time candles for selected options
            Map<String, String> signalResults = new HashMap<>();

            if (ceOption != null && !shouldSkipInstrument(ceOption)) {
                Map<String, String> ceAnalysis = analyzeWithRealTimeCandles(ceOption);
                if ("true".equals(ceAnalysis.get("signal"))) {
                    signalResults.put(ceOption, ceAnalysis.get("pattern"));
                }
            }

            if (peOption != null && !shouldSkipInstrument(peOption)) {
                Map<String, String> peAnalysis = analyzeWithRealTimeCandles(peOption);
                if ("true".equals(peAnalysis.get("signal"))) {
                    signalResults.put(peOption, peAnalysis.get("pattern"));
                }
            }

            // STEP 6: Execute trades based on signal results
            if (!signalResults.isEmpty()) {
                System.out.println("🎯 Trading Signals Generated: " + signalResults.size());
                for (Map.Entry<String, String> entry : signalResults.entrySet()) {
                    String instrument = entry.getKey();
                    String patternType = entry.getValue();
                    executeBuySignal(instrument, patternType);
                }
            } else {
                System.out.println("📊 No trading signals generated this cycle");
            }

            System.out.println("✅ Trading cycle completed at: " + dateFormat.format(new Date()));
            displayMarketStatus();

        } catch (Exception | KiteException e) {
            System.err.println("❌ Error in trading cycle: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("✅ Trading cycle completed.");
        System.out.println("⏰ Next execution in " + AppConfig.getTradingIntervalMinutes() + " minutes.");
        System.out.println("=".repeat(80) + "\n");
    }

    /**
     * Check if we should end trading day early (profitable by 2:45 PM with no positions)
     */
    private boolean shouldEndTradingDayEarly() {
        try {
            Calendar cal = Calendar.getInstance();
            int hour = cal.get(Calendar.HOUR_OF_DAY);
            int minute = cal.get(Calendar.MINUTE);

            // Check if it's 2:45 PM or later
            boolean isAfter245PM = (hour > 14) || (hour == 14 && minute >= 45);

            if (!isAfter245PM) {
                return false;
            }

            // Check if we have overall profit
            double dailyPnL = pnlManager.getTotalDailyPnL();
            boolean isProfitable = dailyPnL > 0;

            // Check if we have no open positions
            boolean hasNoPositions = currentPositions.isEmpty();

            if (isProfitable && hasNoPositions) {
                System.out.println("📊 Early Trading Day End Conditions Met:");
                System.out.println("   - Time: " + hour + ":" + String.format("%02d", minute) + " PM (≥ 2:45 PM)");
                System.out.println("   - Daily P&L: ₹" + String.format("%.2f", dailyPnL) + " (Profitable)");
                System.out.println("   - Open Positions: " + (!hasNoPositions ? "Yes" : "No"));

                // Also check cached positions
                Map<String, Position> cachedPositions = PositionManager.getAllCachedPositions();
                if (!cachedPositions.isEmpty()) {
                    System.out.println("   ⚠️  Cached positions exist: " + cachedPositions.size());
                    return false;
                }

                return true;
            }

            return false;

        } catch (Exception e) {
            System.err.println("❌ Error checking early trading end conditions: " + e.getMessage());
            return false;
        }
    }

    /**
     * Clean up expired or unnecessary breakout monitors (SMART CLEANUP)
     */
    private void cleanupBreakoutMonitors() {
        synchronized(activeMonitors) {
            if (activeMonitors.isEmpty()) {
                System.out.println("📊 No active breakout monitors to clean up");
                return;
            }

            System.out.println("🧹 Cleaning up breakout monitors...");
            List<String> monitorsToRemove = new ArrayList<>();
            int expiredCount = 0;
            int positionCount = 0;
            int signalGeneratedCount = 0;
            int priceMovedAwayCount = 0;

            for (Map.Entry<String, BreakoutMonitor> entry : activeMonitors.entrySet()) {
                String instrument = entry.getKey();
                BreakoutMonitor monitor = entry.getValue();

                // Check if monitor is not running (already stopped)
                if (!monitor.isRunning()) {
                    System.out.println("   🗑️ Removing stopped monitor: " + instrument);
                    monitorsToRemove.add(instrument);
                    continue;
                }

                // Check if monitor has expired (5 minutes elapsed)
                if (monitor.hasExpired()) {
                    System.out.println("   ⏰ Monitor expired: " + instrument +
                            " | Duration: " + monitor.getDurationSeconds() + "s");
                    monitor.stop();
                    monitorsToRemove.add(instrument);
                    expiredCount++;
                    continue;
                }

                // Check if we already have a position in this instrument
                if (currentPositions.containsKey(instrument)) {
                    System.out.println("   🎯 Stopping monitor (position exists): " + instrument);
                    monitor.stop();
                    monitorsToRemove.add(instrument);
                    positionCount++;
                    continue;
                }

                // Check if buy signal already generated for this monitor
                if (monitor.isBuySignalGenerated()) {
                    System.out.println("   ✅ Stopping monitor (signal generated): " + instrument);
                    monitor.stop();
                    monitorsToRemove.add(instrument);
                    signalGeneratedCount++;
                    continue;
                }

                // NEW: Check if price has moved too far away from breakout level
                try {
                    // Get current price
                    String[] instruments = {instrument};
                    Map<String, Quote> quotes;
                    synchronized(apiCallLock) {
                        quotes = kiteConnect.getQuote(instruments);
                    }

                    Quote quote = quotes.get(instrument);
                    if (quote != null) {
                        double currentPrice = quote.lastPrice;
                        double breakoutLevel = monitor.getBreakoutLevel();
                        double distance = breakoutLevel - currentPrice;
                        double distancePercent = (distance / breakoutLevel) * 100;

                        // If price is more than 20% below breakout, cancel
                        if (distancePercent > 20.0) {
                            System.out.println("   📉 Price moved " + String.format("%.2f", distancePercent) +
                                    "% away from breakout. Cancelling: " + instrument);
                            System.out.println("      Breakout: " + breakoutLevel + " | Current: " + currentPrice);
                            monitor.stop();
                            monitorsToRemove.add(instrument);
                            priceMovedAwayCount++;
                            continue;
                        }
                    }
                } catch (Exception | KiteException e) {
                    System.err.println("   ❌ Error checking price for monitor cleanup: " + e.getMessage());
                }

                // Monitor is still active and valid - log its status
                System.out.println("   📊 Active Monitor: " + monitor.getStatus());
            }

            // Remove cleaned up monitors
            for (String instrument : monitorsToRemove) {
                activeMonitors.remove(instrument);
            }

            // Print summary
            if (!monitorsToRemove.isEmpty()) {
                System.out.println("✅ Cleaned up " + monitorsToRemove.size() + " breakout monitors:");
                System.out.println("   - Expired: " + expiredCount);
                System.out.println("   - Position exists: " + positionCount);
                System.out.println("   - Signal generated: " + signalGeneratedCount);
                System.out.println("   - Price moved away: " + priceMovedAwayCount);
            }

            System.out.println("📊 Remaining active monitors: " + activeMonitors.size());
        }
    }

    /**
     * Check if we should skip analyzing/trading an instrument
     */
    private boolean shouldSkipInstrument(String instrument) {
        // Check current positions
        if (currentPositions.containsKey(instrument)) {
            System.out.println("   ⏸️ SKIP: Already have position in " + instrument);
            return true;
        }

        // Check cached positions
        if (PositionManager.hasCachedPosition(instrument)) {
            System.out.println("   ⏸️ SKIP: Position cached for " + instrument);
            return true;
        }

        // Check if we're actively monitoring this instrument
        synchronized(activeMonitors) {
            BreakoutMonitor monitor = activeMonitors.get(instrument);
            if (monitor != null) {
                if (monitor.isRunning()) {
                    System.out.println("   ⏸️ SKIP: Actively monitoring " + instrument +
                            " | Breakout: " + monitor.getBreakoutLevel() +
                            " | Remaining: " + monitor.getRemainingTime() + "s");
                    return true;
                } else if (monitor.isBuySignalGenerated()) {
                    System.out.println("   ⏸️ SKIP: Buy signal already generated for " + instrument);
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Execute buy signal for an instrument with pattern type
     * FIXED: Proper thread synchronization
     */
    private void executeBuySignal(String instrument, String patternType) {
        // Use a dedicated lock for this instrument
        Object instrumentLock = new Object();
        synchronized(instrumentLock) {
            try {
                // Remove from active monitors if this was triggered by breakout
                synchronized(activeMonitors) {
                    BreakoutMonitor monitor = activeMonitors.remove(instrument);
                    if (monitor != null) {
                        monitor.stop();
                        System.out.println("✅ Breakout monitor stopped for " + instrument);
                    }
                }

                System.out.println("🚀 EXECUTING BUY SIGNAL for: " + instrument);
                System.out.println("📊 Pattern Type: " + patternType.toUpperCase());

                // CRITICAL: Check if another thread already opened a position
                if (currentPositions.containsKey(instrument) ||
                        PositionManager.hasCachedPosition(instrument)) {
                    System.out.println("⏸️ Position already exists for " + instrument +
                            " - skipping duplicate buy signal");
                    return;
                }

                // Get current quote with retry logic
                String[] instruments = {instrument};
                Map<String, Quote> quotes = null;
                int retryCount = 0;
                final int MAX_RETRIES = 3;

                while (retryCount < MAX_RETRIES) {
                    try {
                        // Synchronize API calls to prevent rate limiting
                        synchronized(apiCallLock) {
                            // Add small delay to prevent API rate limiting
                            Thread.sleep(100);
                            quotes = kiteConnect.getQuote(instruments);
                        }

                        if (quotes != null && quotes.get(instrument) != null) {
                            break;
                        }
                    } catch (Exception e) {
                        retryCount++;
                        if (retryCount >= MAX_RETRIES) {
                            throw e;
                        }
                        System.out.println("⚠️ Retry " + retryCount + " for quote fetch: " + instrument);
                        Thread.sleep(1000); // Wait 1 second before retry
                    }
                }

                Quote quote = quotes.get(instrument);
                if (quote == null || quote.ohlc == null) {
                    System.err.println("❌ Unable to get quote for: " + instrument);
                    return;
                }

                double entryPrice = quote.lastPrice;
                double vwapPrice = quote.averagePrice;

                double stopLoss, target;

                // Determine stop loss and target based on pattern type
                if ("reversal".equals(patternType)) {
                    // Get stored reversal pattern data
                    ReversalPatternData patternData = reversalPatternData.get(instrument);
                    if (patternData != null) {
                        stopLoss = patternData.stopLossLevel;

                        // NEW: Check if stop loss is too far (>20%) and adjust if needed
                        double slDistance = Math.abs(entryPrice - stopLoss);
                        double slDistancePercent = (slDistance / entryPrice) * 100;

                        if (slDistancePercent > 20.0) {
                            // If stored stop loss is more than 20% away, use 20% from entry price
                            stopLoss = entryPrice * 0.80;
                            System.out.println("📊 Adjusted Stop Loss - Original was " +
                                    String.format("%.2f", slDistancePercent) + "% away");
                            System.out.println("   New Stop Loss (15% from entry): " + stopLoss);
                        }

                        target = entryPrice * 1.20; // 20% target
                        System.out.println("📊 Detected as VWAP REVERSAL PATTERN trade");
                        System.out.println("   Pattern: VWAP Reversal");
                        System.out.println("   Stop Loss: " + stopLoss);
                        System.out.println("   Target: +20%");

                        // Clear stored data
                        reversalPatternData.remove(instrument);
                    } else {
                        // Fallback to original logic
                        CandleData currentCandle = realTimeCandleBuilder.getCurrentCandle(instrument);
                        double candleLow = currentCandle != null ? currentCandle.getLow() : (vwapPrice * 0.98);

                        double distanceToVWAP = Math.abs(entryPrice - vwapPrice);
                        double distancePercent = (distanceToVWAP / vwapPrice) * 100;

                        if (distancePercent <= 2.0) {
                            stopLoss = vwapPrice * 0.98;
                        } else {
                            stopLoss = candleLow;
                        }

                        // NEW: Check if stop loss is too far
                        double slDistance = Math.abs(entryPrice - stopLoss);
                        double slDistancePercent = (slDistance / entryPrice) * 100;

                        if (slDistancePercent > 20.0) {
                            stopLoss = entryPrice * 0.80;
                            System.out.println("📊 Fallback Stop Loss adjusted to 20%: " + stopLoss);
                        }

                        target = entryPrice * 1.12;
                        System.out.println("📊 Detected as VWAP REVERSAL PATTERN trade (fallback)");
                        System.out.println("   Pattern: VWAP Reversal | SL: " + stopLoss + " | Target: +20%");
                    }
                } else {
                    // Original crossover pattern logic
                    stopLoss = calculateDynamicStopLoss(entryPrice, vwapPrice);

                    // NEW: Check if stop loss is too far for crossover patterns too
                    double slDistance = Math.abs(entryPrice - stopLoss);
                    double slDistancePercent = (slDistance / entryPrice) * 100;

                    if (slDistancePercent > 20.0) {
                        stopLoss = entryPrice * 0.80;
                        System.out.println("📊 Crossover Stop Loss adjusted to 20%: " + stopLoss);
                    }

                    target = calculateTarget(entryPrice, stopLoss);
                    System.out.println("📊 Detected as VWAP CROSSOVER trade");
                    System.out.println("   Pattern: VWAP Crossover | SL: " + stopLoss + " | Target: " + target);
                }

                System.out.println("📊 Trade Details:");
                System.out.println("   Instrument: " + instrument);
                System.out.println("   Entry Price: " + entryPrice);
                System.out.println("   VWAP: " + vwapPrice);
                System.out.println("   Stop Loss: " + stopLoss);
                System.out.println("   Target: " + target + " (+" + String.format("%.2f", ((target/entryPrice)-1)*100) + "%)");
                System.out.println("   Stop Loss Distance: " + String.format("%.2f", Math.abs(entryPrice - stopLoss)) +
                        " (" + String.format("%.2f", (Math.abs(entryPrice - stopLoss)/entryPrice)*100) + "%)");

                // Place buy order
                Position position = placeBuyOrder(instrument, entryPrice, stopLoss, target, patternType);
                if (position != null) {
                    // Set pattern type in position
                    position.setPatternType(patternType);
                    position.setVwap(vwapPrice);
                    position.setEntryTime(new Date());

                    currentPositions.put(instrument, position);
                    PositionManager.cachePosition(position);
                    startTargetCheckTimer();

                    System.out.println("✅ Position opened and cached: " + instrument);
                }

            } catch (InterruptedException e) {
                System.err.println("❌ Thread interrupted while executing buy signal: " + instrument);
                Thread.currentThread().interrupt(); // Restore interrupt status
            } catch (Exception | KiteException e) {
                // Improved error handling
                System.err.println("❌ Error executing buy signal for " + instrument + ": " + e.getMessage());

                // Check specific error types
                if (e.getMessage() != null) {
                    if (e.getMessage().contains("rate limit") || e.getMessage().contains("throttle")) {
                        System.err.println("⚠️ API rate limit hit - consider reducing frequency");
                    } else if (e.getMessage().contains("network") || e.getMessage().contains("timeout")) {
                        System.err.println("⚠️ Network issue - check internet connection");
                    }
                }
            }
        }
    }

    /**
     * Calculate target price based on stop loss multiplier
     */
    private double calculateTarget(double entryPrice, double stopLoss) {
        return entryPrice * 1.12;
    }

    /**
     * Place buy order
     */
    private Position placeBuyOrder(String symbol, double entryPrice, double stopLoss,
                                   double target, String patternType) {
        try {
            int quantity = AppConfig.getVWAPOptionsLotSize();

            String tradingSymbol = symbol.replace("NFO:", "");

            System.out.println("📋 Placing BUY Order:");
            System.out.println("   Symbol: " + tradingSymbol);
            System.out.println("   Quantity: " + quantity);
            System.out.println("   Entry: " + entryPrice);
            System.out.println("   Stop Loss: " + stopLoss);
            System.out.println("   Target: " + target);

            // Synchronize API calls
            synchronized(apiCallLock) {
                Thread.sleep(100); // Small delay to prevent rate limiting

                OrderParams orderParams = new OrderParams();
                orderParams.exchange = "NFO";
                orderParams.tradingsymbol = tradingSymbol;
                orderParams.transactionType = Constants.TRANSACTION_TYPE_BUY;
                orderParams.quantity = quantity;
                orderParams.orderType = Constants.ORDER_TYPE_MARKET;
                orderParams.product = Constants.PRODUCT_MIS;
                orderParams.validity = Constants.VALIDITY_DAY;

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

                    System.out.println("✅ Order Placed Successfully:");
                    System.out.println("   Order ID: " + order.orderId);

                    return position;
                } else {
                    System.err.println("❌ Failed to place buy order for: " + symbol);
                }
            }

        } catch (KiteException e) {
            System.err.println("❌ KiteException placing buy order for " + symbol + ": " +
                    e.getMessage() + " (Code: " + e.code + ")");
        } catch (Exception e) {
            System.err.println("❌ Exception placing buy order for " + symbol + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Manage existing positions - check stop loss and target
     */
    private boolean manageExistingPositions() {
        try {
            if (currentPositions.isEmpty()) {
                return false;
            }

            System.out.println("🔍 Managing " + currentPositions.size() + " existing positions...");

            // Store positions that will be closed
            List<String> positionsToClose = new ArrayList<>();
            Map<String, String> closeReasons = new HashMap<>();

            // Get current quotes for all positions
            List<String> positionSymbols = new ArrayList<>(currentPositions.keySet());
            String[] symbolsArray = positionSymbols.toArray(new String[0]);

            // Synchronize API calls
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

                    // Check exit conditions
                    boolean shouldClosePosition = false;
                    String closeReason = "";

                    // Condition 1: Actual stop loss hit
                    if (currentPrice <= stopLoss) {
                        shouldClosePosition = true;
                        closeReason = "STOP LOSS HIT";
                    }
                    // Condition 2: Current price is within ±2% of stop loss
                    else {
                        double priceDifference = Math.abs(currentPrice - stopLoss);
                        double percentageDifference = (priceDifference / stopLoss) * 100;

                        if (percentageDifference <= 1.0) {
                            shouldClosePosition = true;
                            closeReason = "NEAR STOP LOSS (±1%)";

                            System.out.println("⚠️ Near Stop Loss detected for: " + symbol);
                            System.out.println("   Current: " + currentPrice +
                                    " | Stop Loss: " + stopLoss +
                                    " | Difference: " + String.format("%.2f", percentageDifference) + "%");
                        }
                    }

                    if (shouldClosePosition) {
                        positionsToClose.add(symbol);
                        closeReasons.put(symbol, closeReason);
                    }
                }
            }

            // Close positions due to stop loss or near-stop-loss
            boolean anyClosed = false;
            for (String symbol : positionsToClose) {
                String reason = closeReasons.get(symbol);
                System.out.println("🔴 " + reason + " for: " + symbol);
                closePositionDueToStopLoss(symbol);
                anyClosed = true;
            }

            return anyClosed;

        } catch (Exception | KiteException e) {
            System.err.println("❌ Error managing positions: " + e.getMessage());
            return false;
        }
    }

    // Add this method to close position when stop loss is hit
    private void closePositionDueToStopLoss(String symbol) {
        try {
            Position position = currentPositions.get(symbol);
            if (position == null) return;

            // Get current price for P&L calculation
            String[] instruments = {symbol};
            Map<String, Quote> quotes;
            synchronized(apiCallLock) {
                quotes = kiteConnect.getQuote(instruments);
            }
            double exitPrice = quotes.get(symbol).lastPrice;

            OrderParams orderParams = new OrderParams();
            orderParams.exchange = "NFO";
            orderParams.tradingsymbol = symbol.replace("NFO:", "");
            orderParams.transactionType = Constants.TRANSACTION_TYPE_SELL;
            orderParams.quantity = position.getQuantity();
            orderParams.orderType = Constants.ORDER_TYPE_MARKET;
            orderParams.product = Constants.PRODUCT_MIS;
            orderParams.validity = Constants.VALIDITY_DAY;

            Order exitOrder;
            synchronized(apiCallLock) {
                exitOrder = kiteConnect.placeOrder(orderParams, Constants.VARIETY_REGULAR);
            }

            if (exitOrder != null && exitOrder.orderId != null) {
                double pnl = (exitPrice - position.getEntryPrice()) * position.getQuantity();

                // Update P&L in manager
                pnlManager.addToDailyPnL(pnl);

                System.out.println("🛑 STOP LOSS EXECUTED - P&L for " + symbol + ": ₹" + pnl);
                System.out.println("   Entry: " + position.getEntryPrice() + " | Exit: " + exitPrice);
                System.out.println("   Order ID: " + exitOrder.orderId);
                System.out.println("   Total Daily P&L: ₹" + String.format("%.2f", pnlManager.getTotalDailyPnL()));

                // Remove from cache and current positions
                currentPositions.remove(symbol);
                PositionManager.removeCachedPosition(symbol);
            }

        } catch (Exception | KiteException e) {
            System.err.println("❌ Error closing position due to stop loss: " + e.getMessage());
        }
    }

    /**
     * Close a position
     */
    private void closePosition(String symbol) {
        try {
            Position position = currentPositions.get(symbol);
            if (position == null) return;

            // Get current price for P&L calculation
            String[] instruments = {symbol};
            Map<String, Quote> quotes;
            synchronized(apiCallLock) {
                quotes = kiteConnect.getQuote(instruments);
            }
            double exitPrice = quotes.get(symbol).lastPrice;

            OrderParams orderParams = new OrderParams();
            orderParams.exchange = "NFO";
            orderParams.tradingsymbol = symbol.replace("NFO:", "");
            orderParams.transactionType = Constants.TRANSACTION_TYPE_SELL;
            orderParams.quantity = position.getQuantity();
            orderParams.orderType = Constants.ORDER_TYPE_MARKET;
            orderParams.product = Constants.PRODUCT_MIS;
            orderParams.validity = Constants.VALIDITY_DAY;

            Order exitOrder;
            synchronized(apiCallLock) {
                exitOrder = kiteConnect.placeOrder(orderParams, Constants.VARIETY_REGULAR);
            }

            if (exitOrder != null && exitOrder.orderId != null) {
                double pnl = (exitPrice - position.getEntryPrice()) * position.getQuantity();

                // Update P&L in manager
                pnlManager.addToDailyPnL(pnl);

                System.out.println("💰 P&L for " + symbol + ": ₹" + pnl);
                System.out.println("   Entry: " + position.getEntryPrice() + " | Exit: " + exitPrice);
                System.out.println("   Order ID: " + exitOrder.orderId);
                System.out.println("   Total Daily P&L: ₹" + String.format("%.2f", pnlManager.getTotalDailyPnL()));

                currentPositions.remove(symbol);
            }

        } catch (Exception | KiteException e) {
            System.err.println("❌ Error closing position: " + e.getMessage());
        }
    }

    /**
     * Find CE and PE options with premium near target price
     */
    private Map<String, String> findOptionsNearTargetPrice() throws KiteException {
        Map<String, String> options = new HashMap<>();
        try {
            double niftySpot = getNiftySpotPrice();
            System.out.println("📊 Nifty Spot Price: " + niftySpot);

            String ceOption = findOptionNearPremium(niftySpot, true);
            String peOption = findOptionNearPremium(niftySpot, false);

            if (ceOption != null && peOption != null) {
                options.put("CE", ceOption);
                options.put("PE", peOption);
            }

        } catch (Exception e) {
            System.err.println("❌ Error finding options: " + e.getMessage());
        }
        return options;
    }

    private String findOptionNearPremium(double niftySpot, boolean isCall) {
        try {
            double strikeStep = 50.0;
            double atmStrike = Math.round(niftySpot / strikeStep) * strikeStep;
            double targetPrice = AppConfig.getVWAPOptionsTargetPrice();
            double tolerance = AppConfig.getVWAPOptionsPriceTolerance();

            System.out.println("🔍 Searching for " + (isCall ? "CE" : "PE") + " options with premium > " + targetPrice);

            // Only check symbols that we successfully preloaded tokens for
            List<String> validOptionSymbols = new ArrayList<>();
            for (int i = -3; i <= 3; i++) {
                double strike = atmStrike + (i * strikeStep);
                String optionSymbol = buildOptionSymbol(strike, isCall);
                if (symbolToTokenMap.containsKey(optionSymbol)) {
                    validOptionSymbols.add(optionSymbol);
                }
            }

            if (validOptionSymbols.isEmpty()) {
                System.out.println("❌ No valid " + (isCall ? "CE" : "PE") + " symbols found in cache");
                return null;
            }

            // Get premiums in batch
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
                System.out.println("❌ No " + (isCall ? "CE" : "PE") + " options found with premium > " + targetPrice);
            }

            return bestOption;

        } catch (Exception e) {
            System.err.println("❌ Error finding " + (isCall ? "CE" : "PE") + " option: " + e.getMessage());
            return null;
        }
    }

    /**
     * Get premiums for multiple options in one API call
     * Updated to automatically track all fetched options
     */
    private Map<String, Double> getBatchOptionPremiums(List<String> optionSymbols) {
        Map<String, Double> premiums = new HashMap<>();

        if (optionSymbols.isEmpty()) {
            return premiums;
        }

        try {
            String[] symbolsArray = optionSymbols.toArray(new String[0]);
            Map<String, Quote> quoteData;

            synchronized(apiCallLock) {
                quoteData = kiteConnect.getQuote(symbolsArray);
            }

            for (String symbol : optionSymbols) {
                if (quoteData.containsKey(symbol) && quoteData.get(symbol) != null) {
                    Quote quote = quoteData.get(symbol);

                    if (quote.lastPrice > 0) {
                        premiums.put(symbol, quote.lastPrice);

                        if (!trackedInstruments.contains(symbol)) {
                            trackedInstruments.add(symbol);
                            System.out.println("📝 Added to real-time tracking: " + symbol);

                            // Add to real-time candle builder for continuous updates
                            realTimeCandleBuilder.addInstrument(symbol);
                        }

                        // Process this tick
                        realTimeCandleBuilder.processTick(symbol, quote.lastPrice,
                                quote.averagePrice, new Date());
                    }
                }
            }

            // Ensure real-time candle builder is running
            updateRealTimeCandleBuilder();

        } catch (Exception | KiteException e) {
            System.err.println("❌ Error in batch premium fetch: " + e.getMessage());
        }

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
        int year = date.get(Calendar.YEAR) % 100;
        int month = date.get(Calendar.MONTH);
        int day = date.get(Calendar.DATE);

        if (isMonthlyExpiry) {
            String[] monthCodes = {"JAN", "FEB", "MAR", "APR", "MAY", "JUN",
                    "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"};
            return String.format("%02d", year) + monthCodes[month];
        } else {
            return String.format("%02d", year) + "D" + String.format("%02d", day);
        }
    }

    public double getNiftySpotPrice() throws KiteException, IOException {
        String[] instruments = {"NSE:NIFTY 50"};
        Map<String, Quote> quoteData;
        synchronized(apiCallLock) {
            quoteData = kiteConnect.getQuote(instruments);
        }
        return quoteData.get("NSE:NIFTY 50").lastPrice;
    }

    /**
     * Preload tokens for multiple strikes to avoid real-time lookup
     */
    public void preloadOptionTokens(double niftySpot) {
        try {
            System.out.println("🔄 Preloading option tokens for Nifty spot: " + niftySpot);

            double strikeStep = 50.0;
            double atmStrike = Math.round(niftySpot / strikeStep) * strikeStep;

            // Preload 7 CE and 7 PE options around ATM
            List<String> allInstruments = new ArrayList<>();

            for (int i = -3; i <= 3; i++) {
                double strike = atmStrike + (i * strikeStep);

                // Preload CE option
                String ceSymbol = buildOptionSymbol(strike, true);
                preloadSingleToken(ceSymbol, "CE");
                allInstruments.add(ceSymbol);

                // Preload PE option
                String peSymbol = buildOptionSymbol(strike, false);
                preloadSingleToken(peSymbol, "PE");
                allInstruments.add(peSymbol);
            }

            System.out.println("✅ Preloaded " + symbolToTokenMap.size() + " option tokens");
        } catch (Exception e) {
            System.err.println("❌ Error preloading option tokens: " + e.getMessage());
        }
    }

    /**
     * Preload token for a single option symbol
     */
    private void preloadSingleToken(String optionSymbol, String optionType) {
        try {
            // Skip if already cached
            if (symbolToTokenMap.containsKey(optionSymbol)) {
                return;
            }

            System.out.println("🔍 Preloading token for " + optionType + ": " + optionSymbol);

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
                    tokenToSymbolMap.put(token, optionSymbol);
                    System.out.println("✅ Preloaded " + optionType + " token: " + optionSymbol + " -> " + token);
                    return;
                }
            }

            System.err.println("❌ Could not preload token for: " + optionSymbol);

        } catch (Exception | KiteException e) {
            System.err.println("❌ Error preloading token for " + optionSymbol + ": " + e.getMessage());
        }
    }

    /**
     * Stop the VWAP Options Trading
     */
    public void stopVWAPOptionsTrading() {
        try {
            // Stop all breakout monitors
            emergencyKillAllBreakoutMonitors();

            // Stop target check timer
            if (targetCheckTimer != null) {
                targetCheckTimer.cancel();
                targetCheckTimer = null;
                isTargetCheckRunning = false;
                System.out.println("⏰ Target check timer stopped");
            }

            // Stop real-time candle builder
            if (realTimeCandleBuilder != null) {
                realTimeCandleBuilder.stop();
                System.out.println("🕯️ Real-time candle builder stopped");
            }

            // Clear position cache
            PositionManager.clearAllPositions();

            // Clear reversal pattern data
            reversalPatternData.clear();

            // Close all open positions
            if (!currentPositions.isEmpty()) {
                System.out.println("🔄 Closing all open positions...");
                for (String symbol : new ArrayList<>(currentPositions.keySet())) {
                    closePosition(symbol);
                }
            }

            System.out.println("🛑 VWAP Options Trading Stopped");

        } catch (Exception e) {
            System.err.println("❌ Error stopping VWAP options trading: " + e.getMessage());
        }
    }

    private double calculateDynamicStopLoss(double entryPrice, double vwapPrice) {
        double priceDifference = Math.abs(entryPrice - vwapPrice);
        double percentageDifference = (priceDifference / entryPrice) * 100;

        System.out.println("📊 VWAP Stop Loss Analysis:");
        System.out.println("   Entry Price: " + entryPrice);
        System.out.println("   VWAP Price: " + vwapPrice);
        System.out.println("   Difference: " + priceDifference + " (" + String.format("%.2f", percentageDifference) + "%)");

        double stopLoss;

        if (percentageDifference < 5.0) {
            // If difference is less than 5%, set SL at VWAP - 2% (below VWAP)
            stopLoss = entryPrice * 0.90;
            System.out.println("   Condition: <5% difference -> SL = EntryPrice - 5% * EntryPrice = " + stopLoss);
        } else if (percentageDifference <= 10.0) {
            // If difference is between 5-10%, set SL at VWAP
            stopLoss = vwapPrice * 0.98;
            System.out.println("   Condition: 5-10% difference -> SL = VWAP = " + stopLoss);
        } else {
            // If difference is more than 10%, use original calculation
//            stopLoss = entryPrice - (AppConfig.getVWAPOptionsStoplossMultiplier() * (entryPrice - vwapPrice));
            stopLoss = entryPrice * 0.80;
            System.out.println("   Condition: >10% difference -> SL = " + stopLoss);
        }

        return stopLoss;
    }

    // Add this method to start the target check timer
    private void startTargetCheckTimer() {
        if (isTargetCheckRunning) {
            return;
        }

        targetCheckTimer = new Timer();
        long interval = 3 * 1000; // 3 seconds

        targetCheckTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                checkTargetConditions();
            }
        }, interval, interval);

        isTargetCheckRunning = true;
        System.out.println("⏰ Target check timer started (3 second intervals)");
    }

    // Add this method to check target conditions
    private void checkTargetConditions() {
        try {
            Map<String, Position> cachedPositions = PositionManager.getAllCachedPositions();

            if (cachedPositions.isEmpty()) {
                return;
            }

            // Get current quotes for all cached positions
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

                    // Check only for target condition (not stop loss)
                    if (currentPrice >= position.getTarget()) {
                        System.out.println("🎯 TARGET HIT for " + instrument + " at 3-second check!");
                        System.out.println("   Current Price: " + currentPrice + " | Target: " + position.getTarget());

                        // Close position due to target hit
                        closePositionDueToTarget(instrument);
                    }
                }
            }

        } catch (Exception | KiteException e) {
            System.err.println("❌ Error in target check: " + e.getMessage());
        }
    }

    // Add this method to close position when target is hit
    private void closePositionDueToTarget(String instrument) {
        try {
            Position position = currentPositions.get(instrument);
            if (position == null) {
                System.err.println("❌ Position not found for: " + instrument);
                return;
            }

            // Get current price for P&L calculation
            String[] instruments = {instrument};
            Map<String, Quote> quotes;
            synchronized(apiCallLock) {
                quotes = kiteConnect.getQuote(instruments);
            }
            double exitPrice = quotes.get(instrument).lastPrice;

            OrderParams orderParams = new OrderParams();
            orderParams.exchange = "NFO";
            orderParams.tradingsymbol = instrument.replace("NFO:", "");
            orderParams.transactionType = Constants.TRANSACTION_TYPE_SELL;
            orderParams.quantity = position.getQuantity();
            orderParams.orderType = Constants.ORDER_TYPE_MARKET;
            orderParams.product = Constants.PRODUCT_MIS;
            orderParams.validity = Constants.VALIDITY_DAY;

            Order exitOrder;
            synchronized(apiCallLock) {
                exitOrder = kiteConnect.placeOrder(orderParams, Constants.VARIETY_REGULAR);
            }

            if (exitOrder != null && exitOrder.orderId != null) {
                double pnl = (exitPrice - position.getEntryPrice()) * position.getQuantity();

                // Update P&L in manager
                pnlManager.addToDailyPnL(pnl);

                System.out.println("💰 TARGET ACHIEVED - P&L for " + instrument + ": ₹" + pnl);
                System.out.println("   Entry: " + position.getEntryPrice() + " | Exit: " + exitPrice);
                System.out.println("   Order ID: " + exitOrder.orderId);
                System.out.println("   Total Daily P&L: ₹" + String.format("%.2f", pnlManager.getTotalDailyPnL()));

                // Remove from cache and current positions
                currentPositions.remove(instrument);
                PositionManager.removeCachedPosition(instrument);
            }

        } catch (Exception | KiteException e) {
            System.err.println("❌ Error closing position due to target: " + e.getMessage());
        }
    }

    /**
     * NEW: Update real-time candle builder with all tracked instruments
     */
    private void updateRealTimeCandleBuilder() {
        if (trackedInstruments.isEmpty()) {
            return;
        }

        String[] instrumentsArray = trackedInstruments.toArray(new String[0]);

        if (!realTimeCandleBuilder.isRunning()) {
            System.out.println("🚀 Starting real-time candle builder for " + trackedInstruments.size() + " instruments");
            realTimeCandleBuilder.start(instrumentsArray);
        } else {
            // Already running, just ensure all instruments are being tracked
            System.out.println("🔄 Real-time candle builder is already running, tracking " +
                    trackedInstruments.size() + " instruments");
        }
    }

    /**
     * NEW: Start real-time candle builder if not already running
     */
    private void ensureRealTimeCandleBuilderRunning() {
        if (!trackedInstruments.isEmpty() && !realTimeCandleBuilder.isRunning()) {
            updateRealTimeCandleBuilder();
        }
    }

    /**
     * Analyze and return both signal and pattern type
     */
    private Map<String, String> analyzeWithRealTimeCandles(String instrument) {
        Map<String, String> result = new HashMap<>();
        result.put("signal", "false");
        result.put("pattern", "none");

        try {
            System.out.println("🔍 Analyzing with Real-time Candles for: " + instrument);

            // Check if we should skip this instrument
            if (shouldSkipInstrument(instrument)) {
                System.out.println("   ⏸️ Skipping analysis for " + instrument);
                return result;
            }

            // Check if we have sufficient candle data
            if (!realTimeCandleBuilder.hasSufficientCandleData(instrument)) {
                int candleCount = realTimeCandleBuilder.getCompletedCandleCount(instrument);
                System.out.println("⏳ Waiting for more candle data for " + instrument +
                        " (currently " + candleCount + " candles, need at least 2)");
                return result;
            }

            // Get ALL candle data
            CandleData currentCandle = realTimeCandleBuilder.getCurrentCandle(instrument);
            CandleData lastCompletedCandle = realTimeCandleBuilder.getLastCompletedCandle(instrument);
            CandleData previousCandle = realTimeCandleBuilder.getPreviousCompletedCandle(instrument);

            if (currentCandle == null || lastCompletedCandle == null || previousCandle == null) {
                System.out.println("❌ Insufficient candle data for analysis");
                return result;
            }

            // Check buying hours
            if (!isWithinBuyingHours()) {
                return result;
            }

            System.out.println("📊 Candle Data for " + instrument + ":");
            System.out.println("   Previous Candle: O=" + previousCandle.getOpen() +
                    " H=" + previousCandle.getHigh() +
                    " L=" + previousCandle.getLow() +
                    " C=" + previousCandle.getClose() +
                    " VWAP=" + String.format("%.2f", previousCandle.getVWAP()));
            System.out.println("   Last Completed: O=" + lastCompletedCandle.getOpen() +
                    " H=" + lastCompletedCandle.getHigh() +
                    " L=" + lastCompletedCandle.getLow() +
                    " C=" + lastCompletedCandle.getClose() +
                    " VWAP=" + String.format("%.2f", lastCompletedCandle.getVWAP()));
            System.out.println("   Current Candle: O=" + currentCandle.getOpen() +
                    " H=" + currentCandle.getHigh() +
                    " L=" + currentCandle.getLow() +
                    " C=" + currentCandle.getClose() +
                    " VWAP=" + String.format("%.2f", currentCandle.getVWAP()));

            // Check for VWAP reversal pattern (USES ALL THREE CANDLES)
            boolean vwapReversal = checkVWAPReversalPattern(instrument, currentCandle,
                    lastCompletedCandle, previousCandle);

            if (vwapReversal) {
                System.out.println("🎯 VWAP REVERSAL PATTERN DETECTED for " + instrument);
                result.put("signal", "true");
                result.put("pattern", "reversal");
                return result;
            }

            // Check original VWAP crossover (USES ONLY COMPLETED CANDLES)
            boolean originalCrossover = checkOriginalVWAPCrossover(instrument, currentCandle,
                    lastCompletedCandle, previousCandle);

            if (originalCrossover) {
                System.out.println("🎯 ORIGINAL VWAP CROSSOVER DETECTED for " + instrument);
                result.put("signal", "true");
                result.put("pattern", "crossover");
                return result;
            }

            System.out.println("📊 No trading signal detected for " + instrument);
            return result;

        } catch (Exception e) {
            System.err.println("❌ Error in real-time candle analysis for " + instrument + ": " + e.getMessage());
            e.printStackTrace();
            return result;
        }
    }

    /**
     * FIXED: Check for VWAP reversal pattern (price comes to VWAP and bounces)
     * CONDITION 1: All three candles should close nearer or above their VWAP.
     * If it closes just within 1% below vwap then consider it as above vwap
     */
    private boolean checkVWAPReversalPattern(String instrument, CandleData currentCandle,
                                             CandleData lastCompletedCandle, CandleData previousCandle) {
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

            System.out.println("🔍 Checking VWAP Reversal Pattern for " + instrument + ":");
            System.out.println("   Previous Candle: C=" + prevClose + " VWAP=" + String.format("%.2f", prevVWAP) +
                    " L=" + prevLow + " H=" + prevHigh);
            System.out.println("   Last Candle: C=" + lastClose + " VWAP=" + String.format("%.2f", lastVWAP) +
                    " L=" + lastLow + " H=" + lastHigh);
            System.out.println("   Current Candle: C=" + currentClose + " VWAP=" + String.format("%.2f", currentVWAP) +
                    " L=" + currentLow + " H=" + currentHigh);

            // ========================================================
            // CONDITION 1: Check if at least one candle touched/near VWAP
            // ========================================================

            // Check if any candle touched VWAP (low ≤ VWAP)
            boolean prevTouchedVWAP = prevLow <= prevVWAP;
            boolean lastTouchedVWAP = lastLow <= lastVWAP;
            boolean currentTouchedVWAP = currentLow <= currentVWAP;

            // Calculate how close each candle's low is to VWAP (percentage)
            double prevLowDistance = Math.abs(prevVWAP - prevLow);
            double prevLowDistancePercent = (prevLowDistance / prevVWAP) * 100;
            boolean prevLowNearVWAP = prevLowDistancePercent <= 3.0; // Within 3% below VWAP

            double lastLowDistance = Math.abs(lastVWAP - lastLow);
            double lastLowDistancePercent = (lastLowDistance / lastVWAP) * 100;
            boolean lastLowNearVWAP = lastLowDistancePercent <= 3.0;

            double currentLowDistance = Math.abs(currentVWAP - currentLow);
            double currentLowDistancePercent = (currentLowDistance / currentVWAP) * 100;
            boolean currentLowNearVWAP = currentLowDistancePercent <= 3.0;

            boolean anyCandleNearVWAP = prevTouchedVWAP || lastTouchedVWAP || currentTouchedVWAP ||
                    prevLowNearVWAP || lastLowNearVWAP || currentLowNearVWAP;

            if (!anyCandleNearVWAP) {
                System.out.println("   ❌ No candle touched or came near VWAP (within 3% below):");
                System.out.println("      Prev: Touched=" + prevTouchedVWAP +
                        " Low Dist=" + String.format("%.2f", prevLowDistancePercent) + "%");
                System.out.println("      Last: Touched=" + lastTouchedVWAP +
                        " Low Dist=" + String.format("%.2f", lastLowDistancePercent) + "%");
                System.out.println("      Current: Touched=" + currentTouchedVWAP +
                        " Low Dist=" + String.format("%.2f", currentLowDistancePercent) + "%");
                return false;
            }

            System.out.println("   ✓ At least one candle near/touched VWAP:");
            if (prevTouchedVWAP || prevLowNearVWAP) {
                System.out.println("      Prev: Touched=" + prevTouchedVWAP +
                        " Low Dist=" + String.format("%.2f", prevLowDistancePercent) + "%");
            }
            if (lastTouchedVWAP || lastLowNearVWAP) {
                System.out.println("      Last: Touched=" + lastTouchedVWAP +
                        " Low Dist=" + String.format("%.2f", lastLowDistancePercent) + "%");
            }
            if (currentTouchedVWAP || currentLowNearVWAP) {
                System.out.println("      Current: Touched=" + currentTouchedVWAP +
                        " Low Dist=" + String.format("%.2f", currentLowDistancePercent) + "%");
            }

            // ========================================================
            // PATTERN 1: All 3 candles close above VWAP
            // ========================================================
            boolean pattern1Detected = false;
            boolean allAboveVWAP = (prevClose > prevVWAP) && (lastClose > lastVWAP) && (currentClose > currentVWAP);

            if (allAboveVWAP) {
                System.out.println("   🎯 PATTERN 1: All 3 candles close above VWAP");
                System.out.println("      Prev: " + prevClose + " > " + String.format("%.2f", prevVWAP));
                System.out.println("      Last: " + lastClose + " > " + String.format("%.2f", lastVWAP));
                System.out.println("      Current: " + currentClose + " > " + String.format("%.2f", currentVWAP));
                pattern1Detected = true;
            } else {
                System.out.println("   ❌ Not Pattern 1 (all above VWAP):");
                System.out.println("      Prev above VWAP: " + (prevClose > prevVWAP) +
                        " (" + prevClose + " vs " + String.format("%.2f", prevVWAP) + ")");
                System.out.println("      Last above VWAP: " + (lastClose > lastVWAP) +
                        " (" + lastClose + " vs " + String.format("%.2f", lastVWAP) + ")");
                System.out.println("      Current above VWAP: " + (currentClose > currentVWAP) +
                        " (" + currentClose + " vs " + String.format("%.2f", currentVWAP) + ")");
            }

            // ========================================================
            // PATTERN 2: 1 or 2 candles close below VWAP (within 2%), 3rd above VWAP
            // ========================================================
            boolean pattern2Detected = false;

            // Calculate distances for each candle
            double prevDistance = prevClose - prevVWAP;
            double prevDistancePercent = (prevDistance / prevVWAP) * 100;

            double lastDistance = lastClose - lastVWAP;
            double lastDistancePercent = (lastDistance / lastVWAP) * 100;

            double currentDistance = currentClose - currentVWAP;
            double currentDistancePercent = (currentDistance / currentVWAP) * 100;

            // Check if below VWAP but within 2%
            boolean prevBelowWithin2Percent = (prevClose < prevVWAP) && (Math.abs(prevDistancePercent) <= 2.0);
            boolean lastBelowWithin2Percent = (lastClose < lastVWAP) && (Math.abs(lastDistancePercent) <= 2.0);
            boolean currentBelowWithin2Percent = (currentClose < currentVWAP) && (Math.abs(currentDistancePercent) <= 2.0);

            // Count how many are below within 2%
            int belowWithin2PercentCount = 0;
            if (prevBelowWithin2Percent) belowWithin2PercentCount++;
            if (lastBelowWithin2Percent) belowWithin2PercentCount++;
            if (currentBelowWithin2Percent) belowWithin2PercentCount++;

            // Check if 3rd candle is above VWAP
            boolean thirdCandleAboveVWAP = false;
            if (!prevBelowWithin2Percent && prevClose > prevVWAP) thirdCandleAboveVWAP = true;
            if (!lastBelowWithin2Percent && lastClose > lastVWAP) thirdCandleAboveVWAP = true;
            if (!currentBelowWithin2Percent && currentClose > currentVWAP) thirdCandleAboveVWAP = true;

            // Pattern 2: 1 or 2 candles below within 2%, and at least one above VWAP
            if ((belowWithin2PercentCount == 1 || belowWithin2PercentCount == 2) && thirdCandleAboveVWAP && prevDistancePercent >= -2.0) {
                System.out.println("   🎯 PATTERN 2: " + belowWithin2PercentCount + " candle(s) below VWAP (within 2%), 3rd above VWAP");
                System.out.println("      Prev: " + prevClose + " vs VWAP=" + String.format("%.2f", prevVWAP) +
                        " (" + String.format("%.2f", prevDistancePercent) + "%) - BelowWithin2%: " + prevBelowWithin2Percent);
                System.out.println("      Last: " + lastClose + " vs VWAP=" + String.format("%.2f", lastVWAP) +
                        " (" + String.format("%.2f", lastDistancePercent) + "%) - BelowWithin2%: " + lastBelowWithin2Percent);
                System.out.println("      Current: " + currentClose + " vs VWAP=" + String.format("%.2f", currentVWAP) +
                        " (" + String.format("%.2f", currentDistancePercent) + "%) - BelowWithin2%: " + currentBelowWithin2Percent);
                System.out.println("      ✓ Additional Check: Previous candle distance >= -2%: " +
                        String.format("%.2f", prevDistancePercent) + "% >= -2%");
                pattern2Detected = true;
            } else {
                System.out.println("   ❌ Not Pattern 2:");
                System.out.println("      Below within 2% count: " + belowWithin2PercentCount + " (needs 1 or 2)");
                System.out.println("      Third candle above VWAP: " + thirdCandleAboveVWAP);
                if (prevDistancePercent < -2.0) {
                    System.out.println("      ❌ Previous candle too far below VWAP: " +
                            String.format("%.2f", prevDistancePercent) + "% < -2%");
                }
            }

            // ========================================================
            // FINAL CHECK: Must match at least one pattern
            // ========================================================
            if (!pattern1Detected && !pattern2Detected) {
                System.out.println("   ❌ Neither Pattern 1 nor Pattern 2 detected");
                return false;
            }

            // Calculate highest high of the three candles (for breakout)
            double highestHigh = Math.max(prevHigh, Math.max(lastHigh, currentHigh));

            // Calculate lowest low of the three candles (for stop loss)
            double lowestLow = Math.min(prevLow, Math.min(lastLow, currentLow));

            // NEW: Check if lowestLow is more than 20% away from current price
            double lowToCurrentDistance = Math.abs(currentClose - lowestLow);
            double lowToCurrentPercent = (lowToCurrentDistance / currentClose) * 100;

            double stopLossLevel;

            if (lowToCurrentPercent > 20.0) {
                // If lowest low is more than 20% away, set SL at 15% from current price
                stopLossLevel = currentClose * 0.80; // 20% below current price
                System.out.println("   ⚠️  Lowest Low is " + String.format("%.2f", lowToCurrentPercent) +
                        "% away from current price (>20%)");
                System.out.println("      Setting Stop Loss at 20% below current price: " + stopLossLevel);
            } else {
                // Use the original lowest low
                stopLossLevel = lowestLow;
                System.out.println("   Lowest Low is " + String.format("%.2f", lowToCurrentPercent) +
                        "% away from current price (≤20%)");
                System.out.println("      Using Lowest Low as Stop Loss: " + stopLossLevel);
            }

            System.out.println("   📊 Reversal Pattern Statistics:");
            System.out.println("      Pattern Detected: " + (pattern1Detected ? "Pattern 1 (All Above)" : "Pattern 2 (Mixed)"));
            System.out.println("      Highest High of 3 candles: " + highestHigh);
            System.out.println("      Lowest Low of 3 candles: " + lowestLow);
            System.out.println("      Stop Loss Level: " + stopLossLevel);
            System.out.println("      Current Price: " + currentClose);
            System.out.println("      Current to Lowest Low Distance: " + String.format("%.2f", lowToCurrentPercent) + "%");

            // Check if current price already crossed the highest high
            // Allow a small buffer of 0.1 for floating point comparison
            if (currentClose > (highestHigh - 0.1)) {
                System.out.println("   ✅ REVERSAL PATTERN CONFIRMED - Immediate Buy Signal!");
                System.out.println("      ✓ Pattern: " + (pattern1Detected ? "All Above VWAP" : "Mixed with 3rd Above"));
                System.out.println("      ✓ At least one candle near/touched VWAP (within 3%)");
                System.out.println("      ✓ Current price (" + currentClose + ") ≥ Highest High (" + highestHigh + ")");
                System.out.println("      ✓ Stop Loss: " + stopLossLevel);
                System.out.println("      ✓ Buy signal generated immediately");

                // Store the pattern data for execution
                reversalPatternData.put(instrument, new ReversalPatternData(highestHigh, stopLossLevel));
                return true;
            } else {
                // Calculate how close current price is to the breakout level
                double distanceToBreakout = highestHigh - currentClose;
                double distancePercent = (distanceToBreakout / highestHigh) * 100;

                System.out.println("   📊 REVERSAL PATTERN DETECTED - Starting breakout monitor");
                System.out.println("      ✓ Pattern: " + (pattern1Detected ? "All Above VWAP" : "Mixed with 3rd Above"));
                System.out.println("      ✓ At least one candle near/touched VWAP (within 3%)");
                System.out.println("      ✓ Current price: " + currentClose);
                System.out.println("      ✓ Breakout Level (Highest High): " + highestHigh);
                System.out.println("      ✓ Distance to breakout: " + String.format("%.2f", distanceToBreakout) +
                        " (" + String.format("%.2f", distancePercent) + "%)");
                System.out.println("      ✓ Stop Loss Level: " + stopLossLevel);
                System.out.println("      ⏳ Waiting for LTP > " + highestHigh);

                // Start reversal breakout monitor
                startReversalBreakoutMonitor(instrument, highestHigh, stopLossLevel);
                return false; // Return false, actual buy will come from monitor
            }

        } catch (Exception e) {
            System.err.println("❌ Error checking VWAP reversal pattern: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Start breakout monitor for reversal pattern
     */
    private void startReversalBreakoutMonitor(String instrument, double breakoutLevel, double stopLossLevel) {
        synchronized(activeMonitors) {
            // Check if already monitoring
            if (activeMonitors.containsKey(instrument)) {
                System.out.println("   ⚠️ Already monitoring " + instrument);
                return;
            }

            System.out.println("   🚀 Starting reversal breakout monitor for " + instrument);
            System.out.println("      - Breakout Level: " + breakoutLevel);
            System.out.println("      - Stop Loss Level: " + stopLossLevel);
            System.out.println("      - Check Interval: 2 seconds");
            System.out.println("      - Max Duration: 5 minutes");

            // Store pattern data
            reversalPatternData.put(instrument, new ReversalPatternData(breakoutLevel, stopLossLevel));

            // Create and start reversal breakout monitor
            // Note: For reversal patterns, we pass NaN for signalHigh and signalClose
            // since they're not relevant for reversal patterns
            BreakoutMonitor monitor = new BreakoutMonitor(instrument, breakoutLevel,
                    Double.NaN, Double.NaN, new Date(), stopLossLevel, "reversal");
            activeMonitors.put(instrument, monitor);
            monitor.start();
        }
    }

    /**
     * Original VWAP Crossover Logic - Starts breakout monitoring
     */
    private boolean checkOriginalVWAPCrossover(String instrument,
                                               CandleData currentCandle,
                                               CandleData lastCompletedCandle,
                                               CandleData previousCandle) {
        try {
            System.out.println("🔍 Checking ORIGINAL VWAP Crossover for " + instrument + ":");

            // FIRST: Check if we should skip this instrument
            if (shouldSkipInstrument(instrument)) {
                System.out.println("   ⏸️ Skipping crossover check for " + instrument);
                return false;
            }

            // Validate all required candles are present
            if (currentCandle == null || lastCompletedCandle == null || previousCandle == null) {
                System.out.println("   ❌ Missing required candle data");
                return false;
            }

            // Get data from all three candles
            double currentClose = currentCandle.getClose();
            double currentVWAP = currentCandle.getVWAP();

            double lastClose = lastCompletedCandle.getClose();
            double lastVWAP = lastCompletedCandle.getVWAP();
            double lastHigh = lastCompletedCandle.getHigh();

            double previousClose = previousCandle.getClose();
            double previousVWAP = previousCandle.getVWAP();

            System.out.println("   Data Points:");
            System.out.println("      - Previous Candle: Close=" + previousClose + " | VWAP=" +
                    String.format("%.2f", previousVWAP) +
                    " | Diff=" + String.format("%.2f", previousClose - previousVWAP));
            System.out.println("      - Last Candle: Close=" + lastClose + " | VWAP=" +
                    String.format("%.2f", lastVWAP) +
                    " | Diff=" + String.format("%.2f", lastClose - lastVWAP) +
                    " | High=" + lastHigh);
            System.out.println("      - Current Candle: Close=" + currentClose + " | VWAP=" +
                    String.format("%.2f", currentVWAP) +
                    " | Diff=" + String.format("%.2f", currentClose - currentVWAP));
            System.out.println("      - Candle Periods:");
            System.out.println("         • Previous: " + getCandleTimeRange(previousCandle));
            System.out.println("         • Last: " + getCandleTimeRange(lastCompletedCandle));
            System.out.println("         • Current: " + getCandleTimeRange(currentCandle));

            // Capture timestamp
            Date crossoverDetectionTime = new Date();
            System.out.println("      - Detection Time: " + dateFormat.format(crossoverDetectionTime));

            // NEW STRICTER LOGIC:
            // 1. Both previous AND last completed candles must close BELOW their VWAPs
            // 2. Current candle must close ABOVE its VWAP
            boolean previousBelowVWAP = previousClose < previousVWAP;
            boolean lastBelowVWAP = lastClose < lastVWAP;
            boolean currentAboveVWAP = currentClose > currentVWAP;

            // Additional check: Current candle should show improvement
            // (moving from below VWAP to above VWAP)
            double previousVWAPDistance = previousVWAP - previousClose;  // Positive if below
            double lastVWAPDistance = lastVWAP - lastClose;              // Positive if below
            double currentVWAPDistance = currentClose - currentVWAP;     // Positive if above

            boolean crossoverDetected = previousBelowVWAP && lastBelowVWAP && currentAboveVWAP;

            if (crossoverDetected) {
                System.out.println("   ✅ VWAP CROSSOVER DETECTED (STRICTER LOGIC)!");
                System.out.println("      ✓ Previous: Close(" + previousClose + ") < VWAP(" +
                        String.format("%.2f", previousVWAP) + ") by " +
                        String.format("%.2f", previousVWAPDistance));
                System.out.println("      ✓ Last: Close(" + lastClose + ") < VWAP(" +
                        String.format("%.2f", lastVWAP) + ") by " +
                        String.format("%.2f", lastVWAPDistance));
                System.out.println("      ✓ Current: Close(" + currentClose + ") > VWAP(" +
                        String.format("%.2f", currentVWAP) + ") by " +
                        String.format("%.2f", currentVWAPDistance));
                System.out.println("      📊 Trend: Improving from below VWAP to above VWAP");

                // Calculate breakout level (current high + 1)
                double currentHigh = currentCandle.getHigh();
                double breakoutLevel = currentHigh + 1.0;

                System.out.println("      📈 Breakout Level: Current High(" + currentHigh + ") + 1 = " + breakoutLevel);
                System.out.println("      ⏰ Signal Time: " + dateFormat.format(crossoverDetectionTime));

                // Check if current close is already above breakout level
                boolean alreadyAboveBreakout = currentClose > breakoutLevel;
                if (alreadyAboveBreakout) {
                    System.out.println("      🚨 Current price already above breakout level!");
                    System.out.println("         Current: " + currentClose + " > Breakout: " + breakoutLevel);
                    System.out.println("         Difference: +" + String.format("%.2f", (currentClose - breakoutLevel)));
                }

                // DOUBLE CHECK: Make sure we're not already monitoring
                synchronized(activeMonitors) {
                    BreakoutMonitor existingMonitor = activeMonitors.get(instrument);
                    if (existingMonitor != null) {
                        if (existingMonitor.isRunning()) {
                            System.out.println("   ⚠️ WARNING: Monitor already exists but still running!");
                            System.out.println("      Status: " + existingMonitor.getStatus());
                            return false;
                        } else {
                            // Remove dead monitor
                            System.out.println("   🗑️ Removing dead monitor for " + instrument);
                            activeMonitors.remove(instrument);
                        }
                    }

                    // Start new breakout monitor WITH CAPTURED VALUES
                    System.out.println("   🚀 Starting real-time breakout monitor...");
                    System.out.println("      - Instrument: " + instrument);
                    System.out.println("      - Breakout Level: " + breakoutLevel);
                    System.out.println("      - Current High: " + currentHigh);
                    System.out.println("      - Current Close: " + currentClose);
                    System.out.println("      - Signal Time: " + dateFormat.format(crossoverDetectionTime));
                    System.out.println("      - Check Interval: 5 seconds");
                    System.out.println("      - Max Duration: 5 minutes");
                    System.out.println("      - Already above breakout: " + alreadyAboveBreakout);

                    BreakoutMonitor monitor = new BreakoutMonitor(instrument, breakoutLevel,
                            currentHigh, currentClose, crossoverDetectionTime,
                            Double.NaN, "crossover");
                    activeMonitors.put(instrument, monitor);
                    monitor.start();

                    // If already above breakout level, trigger immediate check
                    if (alreadyAboveBreakout) {
                        System.out.println("   🔥 Triggering immediate breakout check...");
                        monitor.forceCheck();
                    }
                }

                // Return false for now - actual buy signal will come from monitor thread
                System.out.println("   ⏳ Waiting for breakout confirmation...");
                System.out.println("   ℹ️  Buy signal will be generated when LTP > " + breakoutLevel);
                return false;

            } else {
                System.out.println("   ❌ No VWAP Crossover (stricter criteria not met):");
                System.out.println("      - Previous below VWAP: " + previousBelowVWAP +
                        " (" + previousClose + " < " + String.format("%.2f", previousVWAP) + ")");
                System.out.println("      - Last below VWAP: " + lastBelowVWAP +
                        " (" + lastClose + " < " + String.format("%.2f", lastVWAP) + ")");
                System.out.println("      - Current above VWAP: " + currentAboveVWAP +
                        " (" + currentClose + " > " + String.format("%.2f", currentVWAP) + ")");

                // Log which condition failed
                if (!previousBelowVWAP) {
                    System.out.println("      ❌ FAILED: Previous candle NOT below VWAP");
                    System.out.println("         Previous Close: " + previousClose +
                            " | VWAP: " + String.format("%.2f", previousVWAP) +
                            " | Diff: " + String.format("%.2f", previousClose - previousVWAP));
                }
                if (!lastBelowVWAP) {
                    System.out.println("      ❌ FAILED: Last candle NOT below VWAP");
                    System.out.println("         Last Close: " + lastClose +
                            " | VWAP: " + String.format("%.2f", lastVWAP) +
                            " | Diff: " + String.format("%.2f", lastClose - lastVWAP));
                }
                if (!currentAboveVWAP) {
                    System.out.println("      ❌ FAILED: Current candle NOT above VWAP");
                    System.out.println("         Current Close: " + currentClose +
                            " | VWAP: " + String.format("%.2f", currentVWAP) +
                            " | Diff: " + String.format("%.2f", currentClose - currentVWAP));
                }
                return false;
            }

        } catch (Exception e) {
            System.err.println("❌ Error in VWAP crossover check: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Helper method to get candle time range
     */
    private String getCandleTimeRange(CandleData candle) {
        if (candle == null || candle.getTimestamp() == null) {
            return "Unknown";
        }

        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm");
        Date startTime = candle.getTimestamp();
        Date endTime = new Date(startTime.getTime() + (5 * 60 * 1000)); // 5 minutes later

        return timeFormat.format(startTime) + " to " + timeFormat.format(endTime);
    }

    /**
     * NEW: Check if within buying hours
     */
    private boolean isWithinBuyingHours() {
        try {
            Calendar cal = Calendar.getInstance();
            int hour = cal.get(Calendar.HOUR_OF_DAY);
            int minute = cal.get(Calendar.MINUTE);
            int currentTimeInMinutes = hour * 60 + minute;

            // Get buying hours from AppConfig
            String buyingStartTime = AppConfig.getBuyingStartTime();
            String buyingEndTime = AppConfig.getBuyingEndTime();

            // Parse the time strings
            int startBuyTime = parseTimeToMinutes(buyingStartTime);
            int endBuyTime = parseTimeToMinutes(buyingEndTime);

            boolean isWithin = currentTimeInMinutes >= startBuyTime &&
                    currentTimeInMinutes <= endBuyTime;

            if (!isWithin) {
                String currentTimeStr = hour + ":" + String.format("%02d", minute);
                System.out.println("⏸️ Outside buying hours: " + currentTimeStr +
                        " (Allowed: " + buyingStartTime + " to " + buyingEndTime + ")");
            }

            return isWithin;

        } catch (Exception e) {
            System.err.println("❌ Error checking buying hours: " + e.getMessage());
            return false;
        }
    }

    /**
     * NEW: Parse time string to minutes
     */
    private int parseTimeToMinutes(String timeString) {
        try {
            if (timeString != null && timeString.contains(":")) {
                String[] parts = timeString.split(":");
                if (parts.length == 2) {
                    int hours = Integer.parseInt(parts[0].trim());
                    int minutes = Integer.parseInt(parts[1].trim());
                    return hours * 60 + minutes;
                }
            }
            return timeString.contains("09:45") ? (9 * 60 + 45) : (15 * 60 + 15);
        } catch (NumberFormatException e) {
            System.err.println("❌ Error parsing time string: " + timeString);
            return 0;
        }
    }

    /**
     * Display market status
     */
    public void displayMarketStatus() {
        System.out.println("📈 MARKET STATUS:");
        System.out.println("   - Trading Hours: " + (isWithinTradingHours() ? "OPEN ✅" : "CLOSED 🔒"));
        System.out.println("   - VWAP Strategy: " + (isTradingAllowed() ? "ACTIVE 🎯" : "INACTIVE"));
        System.out.println("   - Trading Allowed: " + (isTradingAllowed() ? "YES ✅" : "NO ❌"));
        System.out.println("   - Daily P&L: ₹" + String.format("%.2f", pnlManager.getTotalDailyPnL()));
        System.out.println("   - Max Daily Loss: ₹" + AppConfig.getMaxDailyLoss());
        System.out.println("   - Open Position: ₹" + currentPositions.size());

        // Display current positions
        if (!currentPositions.isEmpty()) {
            System.out.println("   - Open Positions: " + currentPositions.size());
            for (Position position : currentPositions.values()) {
                System.out.println("     • " + position.getTradingSymbol() +
                        " | Entry: " + position.getEntryPrice() +
                        " | SL: " + position.getStopLoss() +
                        " | Target: " + position.getTarget());
            }
        } else {
            System.out.println("   - Open Positions: None");
        }

        // Display cached positions
        Map<String, Position> cachedPositions = PositionManager.getAllCachedPositions();
        if (!cachedPositions.isEmpty()) {
            System.out.println("   - Cached Positions: " + cachedPositions.size());
            for (Position position : cachedPositions.values()) {
                System.out.println("     • " + position.getTradingSymbol() +
                        " | Entry: " + position.getEntryPrice() +
                        " | SL: " + position.getStopLoss() +
                        " | Target: " + position.getTarget());
            }
        }

        // Display active breakout monitors
        synchronized(activeMonitors) {
            if (!activeMonitors.isEmpty()) {
                int activeCount = 0;
                for (BreakoutMonitor monitor : activeMonitors.values()) {
                    if (monitor.isRunning()) {
                        activeCount++;
                    }
                }

                System.out.println("   - Active Breakout Monitors: " + activeCount);
                if (activeCount > 0) {
                    for (BreakoutMonitor monitor : activeMonitors.values()) {
                        if (monitor.isRunning()) {
                            System.out.println("     • " + monitor.getInstrument() +
                                    " | Breakout: " + monitor.getBreakoutLevel() +
                                    " | Remaining: " + monitor.getRemainingTime() + "s");
                        }
                    }
                }
            } else {
                System.out.println("   - Active Breakout Monitors: None");
            }
        }

        // Display max positions status
        System.out.println("   - Max Positions Limit: " + AppConfig.getVWAPOptionsMaxPositions() +
                " | Used: " + currentPositions.size());

        if (pnlManager.isDailyLossLimitReached()) {
            System.out.println("   ⚠️  DAILY LOSS LIMIT REACHED - Trading Stopped");
        }

        // Display real-time candle builder status
        if (realTimeCandleBuilder != null) {
            System.out.println("   - Real-time Candles: " +
                    (realTimeCandleBuilder.isRunning() ? "ACTIVE ✅" : "INACTIVE ⏸️"));
        }

        // Display buying hours status
        System.out.println("   - Buying Hours: " +
                (isWithinBuyingHours() ? "ACTIVE (09:45-15:15) ✅" : "INACTIVE ⏸️"));
    }

    /**
     * Check if within trading hours for strategy
     */
    private boolean isWithinTradingHours() {
        try {
            Calendar cal = Calendar.getInstance();
            int hour = cal.get(Calendar.HOUR_OF_DAY);
            int minute = cal.get(Calendar.MINUTE);
            int currentTimeInMinutes = hour * 60 + minute;

            // Get trading hours from AppConfig
            String tradingStartTime = AppConfig.getTradingStartTime();
            String tradingEndTime = AppConfig.getTradingEndTime();

            // Parse the time strings
            int startTradingTime = parseTimeToMinutes(tradingStartTime);
            int endTradingTime = parseTimeToMinutes(tradingEndTime);

            return currentTimeInMinutes >= startTradingTime &&
                    currentTimeInMinutes <= endTradingTime;

        } catch (Exception e) {
            System.err.println("❌ Error checking trading hours: " + e.getMessage());
            return false;
        }
    }

    /**
     * Check if trading is allowed
     */
    private boolean isTradingAllowed() {
        return isWithinTradingHours() && !pnlManager.isDailyLossLimitReached();
    }

    /**
     * Get status of all active breakout monitors
     */
    public void printBreakoutMonitorStatus() {
        synchronized(activeMonitors) {
            if (activeMonitors.isEmpty()) {
                System.out.println("📊 No active breakout monitors");
                return;
            }

            System.out.println("📊 Active Breakout Monitors (" + activeMonitors.size() + "):");
            for (BreakoutMonitor monitor : activeMonitors.values()) {
                if (monitor.isRunning()) {
                    System.out.println("   - " + monitor.getStatus());
                }
            }
        }
    }

    /**
     * EMERGENCY ONLY: Kill all breakout monitors
     * Use only when needed, not in normal trading cycles
     */
    private void emergencyKillAllBreakoutMonitors() {
        synchronized(activeMonitors) {
            if (activeMonitors.isEmpty()) {
                System.out.println("📊 No breakout monitors to kill");
                return;
            }

            System.out.println("🆘 EMERGENCY: Killing " + activeMonitors.size() + " active breakout monitors...");
            for (BreakoutMonitor monitor : activeMonitors.values()) {
                if (monitor != null) {
                    monitor.stop();
                }
            }
            activeMonitors.clear();
            System.out.println("✅ All breakout monitors killed");
        }
    }

    // ====================================================================
    // BREAKOUT MONITOR INNER CLASS - FIXED VERSION
    // ====================================================================

    /**
     * Real-time breakout monitor as inner class
     */
    private class BreakoutMonitor {
        private final String instrument;
        private final double breakoutLevel;
        private final double signalHigh;
        private final double signalClose;
        private final Date signalTime;
        private final Date startTime;
        private final double stopLossLevel; // NEW: For reversal patterns
        private final String patternType;   // NEW: "crossover" or "reversal"

        private volatile boolean running = false;
        private volatile boolean buySignalGenerated = false;
        private ScheduledExecutorService scheduler;
        private ScheduledFuture<?> monitorFuture;
        private final Object lock = new Object();

        public BreakoutMonitor(String instrument, double breakoutLevel,
                               double signalHigh, double signalClose, Date signalTime,
                               double stopLossLevel, String patternType) {
            this.instrument = instrument;
            this.breakoutLevel = breakoutLevel;
            this.signalHigh = signalHigh;
            this.signalClose = signalClose;
            this.signalTime = signalTime;
            this.startTime = new Date();
            this.stopLossLevel = stopLossLevel;
            this.patternType = patternType;
        }

        /**
         * Start monitoring for breakout
         */
        public void start() {
            synchronized(lock) {
                if (running) {
                    System.out.println("⚠️ Monitor already running for " + instrument);
                    return;
                }

                running = true;
                buySignalGenerated = false;
                scheduler = Executors.newScheduledThreadPool(1);

                System.out.println("🔍 Starting breakout monitor for " + instrument);
                System.out.println("   - Signal Detected At: " + dateFormat.format(signalTime));
                System.out.println("   - Signal High: " + signalHigh);
                System.out.println("   - Signal Close: " + signalClose);
                System.out.println("   - Breakout Level: " + breakoutLevel);
                System.out.println("   - Start Time: " + dateFormat.format(startTime));
                System.out.println("   - Duration: 5 minutes");

                // PERFORM IMMEDIATE CHECK FIRST
                System.out.println("   🔄 Performing immediate breakout check...");
                scheduler.execute(this::checkBreakout);

                // Schedule regular checks every 5 seconds
                monitorFuture = scheduler.scheduleAtFixedRate(
                        this::checkBreakout,
                        5, // Start checking every 5 seconds AFTER immediate check
                        5, // period (5 seconds)
                        TimeUnit.SECONDS
                );

                // Schedule auto-stop after 5 minutes (300 seconds)
                scheduler.schedule(this::stop, 5, TimeUnit.MINUTES);

                System.out.println("✅ Breakout monitor started successfully");
            }
        }

        /**
         * Stop monitoring
         */
        public void stop() {
            synchronized(lock) {
                if (!running) {
                    return;
                }

                System.out.println("🛑 Stopping breakout monitor for " + instrument);
                running = false;
//                buySignalGenerated = true; // Mark as processed

                // Cancel the scheduled task
                if (monitorFuture != null) {
                    monitorFuture.cancel(true);
                    monitorFuture = null;
                }

                // Shutdown the scheduler
                if (scheduler != null) {
                    try {
                        scheduler.shutdown();
                        if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                            scheduler.shutdownNow();
                        }
                    } catch (InterruptedException e) {
                        scheduler.shutdownNow();
                        Thread.currentThread().interrupt();
                    }
                    scheduler = null;
                }

                System.out.println("✅ Breakout monitor stopped for " + instrument);
            }
        }

        /**
         * Modified: Check for breakout condition with rate limiting
         */
        private void checkBreakout() {
            try {
                // Double-check if still running
                if (!running) {
                    return;
                }

                // Check if buy signal already generated
                if (buySignalGenerated) {
                    stop();
                    return;
                }

                // Get current LTP - with synchronized access to kiteConnect
                String[] instruments = {instrument};
                Map<String, Quote> quotes;

                synchronized(apiCallLock) {
                    Thread.sleep(50); // Small delay to prevent rate limiting
                    quotes = kiteConnect.getQuote(instruments);
                }

                Quote quote = quotes.get(instrument);
                if (quote == null) {
                    System.err.println("❌ No quote data for " + instrument);
                    return;
                }

                double currentPrice = quote.lastPrice;
                Date currentTime = new Date();

                // NEW: Check if price has moved too far AWAY from breakout level
                // If price is more than 15% below breakout level, cancel the monitor
                double distanceFromBreakout = breakoutLevel - currentPrice;
                double distancePercent = (distanceFromBreakout / breakoutLevel) * 100;

                // For reversal patterns, check if we're getting too far from the pattern
                if ("reversal".equals(patternType) && distancePercent > 20.0) {
                    System.out.println("⚠️  Price moved " + String.format("%.2f", distancePercent) +
                            "% AWAY from breakout level. Cancelling breakout monitor.");
                    System.out.println("   Breakout: " + breakoutLevel + " | Current: " + currentPrice);
                    System.out.println("   Pattern type: " + patternType);
                    stop();
                    return;
                }

                // For crossover patterns, check if we're getting too far from the signal
                if ("crossover".equals(patternType)) {
                    double distanceFromSignal = currentPrice - signalClose;
                    double distanceFromSignalPercent = (distanceFromSignal / signalClose) * 100;

                    // If price moved more than 20% below the signal close, cancel
                    if (distanceFromSignalPercent < -20.0) {
                        System.out.println("⚠️  Price moved " + String.format("%.2f", Math.abs(distanceFromSignalPercent)) +
                                "% below signal price. Cancelling breakout monitor.");
                        System.out.println("   Signal Close: " + signalClose + " | Current: " + currentPrice);
                        stop();
                        return;
                    }
                }

                // Log every 30 seconds only
                long timeSinceStart = System.currentTimeMillis() - startTime.getTime();
                if (timeSinceStart % 15000 < 5000) {
                    System.out.println("📊 Breakout Check: " + instrument +
                            " | Current: " + currentPrice +
                            " | Breakout: " + breakoutLevel +
                            " | Distance: " + String.format("%.2f", distancePercent) + "%" +
                            " | Time: " + dateFormat.format(currentTime) +
                            " | Remaining: " + getRemainingTime() + "s");
                }

                // Check if LTP crosses breakout level
                if (currentPrice > breakoutLevel) {
                    System.out.println("\n" + "🎯".repeat(10));
                    System.out.println("🎯 BREAKOUT CONFIRMED! " + instrument);
                    System.out.println("   Signal Time: " + dateFormat.format(signalTime));
                    System.out.println("   Breakout Time: " + dateFormat.format(currentTime));
                    System.out.println("   Current: " + currentPrice + " > Breakout: " + breakoutLevel);
                    System.out.println("   Difference: +" + String.format("%.2f", (currentPrice - breakoutLevel)));
                    System.out.println("   Time to breakout: " + getElapsedSeconds(startTime, currentTime) + " seconds");
                    System.out.println("🎯".repeat(10) + "\n");

                    // Generate buy signal
                    synchronized(lock) {
                        if (running && !buySignalGenerated) {
                            buySignalGenerated = true; // MARK AS GENERATED
                            System.out.println("🚀 GENERATING INSTANT BUY SIGNAL!");

                            // Execute buy signal in main thread pool, not monitor thread
                            scheduler.execute(() -> {
                                try {
                                    executeBreakoutBuySignal();
                                } catch (Exception e) {
                                    System.err.println("❌ Error in breakout buy signal execution: " + e.getMessage());
                                } finally {
                                    stop(); // Ensure monitor stops
                                }
                            });
                        }
                    }
                }

            } catch (InterruptedException e) {
                System.err.println("❌ Thread interrupted in breakout check for " + instrument);
                Thread.currentThread().interrupt();
            } catch (Exception | KiteException e) {
                System.err.println("❌ Error in breakout check for " + instrument + ": " + e.getMessage());

                // If it's a rate limit error, reduce frequency
                if (e.getMessage() != null &&
                        (e.getMessage().contains("rate limit") || e.getMessage().contains("429"))) {
                    System.err.println("⚠️ API rate limit exceeded - consider reducing breakout check frequency");
                }
            }
        }

        /**
         * Execute buy signal for breakout patterns
         */
        private void executeBreakoutBuySignal() {
            try {
                System.out.println("🚀 [BreakoutMonitor] Executing breakout buy signal for: " + instrument);
                System.out.println("📊 Pattern Type: " + patternType.toUpperCase());

                // Use the main strategy's executeBuySignal method
                // This ensures consistent buy signal execution
                VWAPOptionsStrategy.this.executeBuySignal(instrument, patternType);

                System.out.println("✅ [BreakoutMonitor] Buy signal execution completed for: " + instrument);

            } catch (Exception e) {
                System.err.println("❌ [BreakoutMonitor] Error in breakout buy signal: " + e.getMessage());
                // Don't re-throw - let the monitor stop normally
            }
        }

        // Helper method to calculate elapsed seconds
        private long getElapsedSeconds(Date start, Date end) {
            return (end.getTime() - start.getTime()) / 1000;
        }

        /**
         * Check if monitor is running
         */
        public boolean isRunning() {
            synchronized(lock) {
                return running;
            }
        }

        /**
         * Check if monitor has expired (5 minutes elapsed)
         */
        public boolean hasExpired() {
            synchronized(lock) {
                if (!running) return true;

                long elapsed = System.currentTimeMillis() - startTime.getTime();
                return elapsed >= (5 * 60 * 1000); // 5 minutes
            }
        }

        /**
         * Get instrument being monitored
         */
        public String getInstrument() {
            return instrument;
        }

        /**
         * Get breakout level
         */
        public double getBreakoutLevel() {
            return breakoutLevel;
        }

        /**
         * Get remaining time in seconds
         */
        public long getRemainingTime() {
            synchronized(lock) {
                if (!running) return 0;

                long elapsed = System.currentTimeMillis() - startTime.getTime();
                long totalDuration = 5 * 60 * 1000 - 2 * 1000; // 4 minutes 58 seconds in milliseconds
                long remaining = totalDuration - elapsed;

                return remaining > 0 ? remaining / 1000 : 0;
            }
        }

        /**
         * Get duration in seconds
         */
        public long getDurationSeconds() {
            synchronized(lock) {
                long elapsed = System.currentTimeMillis() - startTime.getTime();
                return elapsed / 1000;
            }
        }

        /**
         * Check if buy signal was generated
         */
        public boolean isBuySignalGenerated() {
            synchronized(lock) {
                return buySignalGenerated;
            }
        }

        /**
         * Force immediate check (for testing/debugging)
         */
        public void forceCheck() {
            if (running) {
                checkBreakout();
            }
        }

        /**
         * Get monitor status as string
         */
        public String getStatus() {
            synchronized(lock) {
                long remainingSeconds = getRemainingTime();
                long durationSeconds = getDurationSeconds();

                return "BreakoutMonitor{" +
                        "instrument='" + instrument + '\'' +
                        ", breakoutLevel=" + breakoutLevel +
                        ", running=" + running +
                        ", buySignalGenerated=" + buySignalGenerated +
                        ", duration=" + durationSeconds + "s" +
                        ", remaining=" + remainingSeconds + "s" +
                        ", signalTime=" + dateFormat.format(signalTime) +
                        '}';
            }
        }
    }

    public boolean hasNoOpenPositions() {
        synchronized(currentPositions) {
            return currentPositions.isEmpty();
        }
    }
}