package com.trading.strategy;

import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Quote;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class RealTimeCandleBuilder {
    private final KiteConnect kiteConnect;
    private final ScheduledExecutorService scheduler;

    // Store tick data for each instrument
    private final Map<String, List<TickData>> tickDataMap;
    private final Map<String, CandleData> currentCandleMap;

    // Store completed 5-minute candles
    private final Map<String, List<CandleData>> completedCandlesMap;

    // Store all instruments for continuous tracking
    private final Set<String> allTrackedInstruments;

    // Synchronization for trading cycle
    private final Object tradingWaitLock = new Object();
    private volatile boolean isCandleFinalizationInProgress = false;
    private volatile boolean isTradingAllowed = false;

    private final int candlePeriodSeconds = 300; // 5 minutes
    private boolean isRunning = false;

    // Track boundary times
    private Date lastBoundaryTime = null;

    // Constructor
    public RealTimeCandleBuilder(KiteConnect kiteConnect) {
        this.kiteConnect = kiteConnect;
        this.tickDataMap = new ConcurrentHashMap<>();
        this.currentCandleMap = new ConcurrentHashMap<>();
        this.completedCandlesMap = new ConcurrentHashMap<>();
        this.allTrackedInstruments = ConcurrentHashMap.newKeySet();
        this.scheduler = Executors.newScheduledThreadPool(3);
    }

    // Public getter for isRunning
    public boolean isRunning() {
        return isRunning;
    }

    // Start the real-time candle builder
    public void start(String[] instruments) {
        if (isRunning) {
            System.out.println("⚠️ RealTimeCandleBuilder is already running");
            return;
        }

        System.out.println("🚀 Starting RealTimeCandleBuilder for " + instruments.length + " instruments");
        isRunning = true;

        // Clear any old data
        tickDataMap.clear();
        currentCandleMap.clear();
        completedCandlesMap.clear();
        allTrackedInstruments.clear();

        // Add initial instruments
        allTrackedInstruments.addAll(Arrays.asList(instruments));

        // Schedule price fetch every 2 seconds for ALL tracked instruments
        scheduler.scheduleAtFixedRate(() -> {
            fetchAndProcessAllPrices();
        }, 0, 2, TimeUnit.SECONDS);

        // Schedule candle finalization at exact 5-minute boundaries
        scheduleCandleFinalizationAtBoundaries();

        System.out.println("✅ RealTimeCandleBuilder started - Continuously tracking " +
                allTrackedInstruments.size() + " instruments");
    }

    /**
     * Schedule candle finalization at exact 5-minute boundaries
     */
    private void scheduleCandleFinalizationAtBoundaries() {
        Calendar now = Calendar.getInstance();
        int currentMinute = now.get(Calendar.MINUTE);
        int currentSecond = now.get(Calendar.SECOND);

        // Calculate seconds until next 5-minute boundary
        int minutesToNextBoundary = 5 - (currentMinute % 5);
        long secondsToNextBoundary = (minutesToNextBoundary * 60L) - currentSecond;

        // If we're exactly at a boundary, start immediately
        if (secondsToNextBoundary == 0) {
            secondsToNextBoundary = 300; // 5 minutes
        }

        System.out.println("⏰ Next candle finalization at " +
                getNextBoundaryTime() + " (in " + secondsToNextBoundary + " seconds)");

        // Schedule first execution at the next 5-minute boundary
        scheduler.scheduleAtFixedRate(() -> {
            // Reset trading state at the beginning of each boundary
            synchronized(tradingWaitLock) {
                isTradingAllowed = false;
                System.out.println("🔁 Reset trading state for new boundary cycle");
            }

            finalizeAllCandles();
        }, secondsToNextBoundary * 1000, 300 * 1000, TimeUnit.MILLISECONDS);
    }

    /**
     * Get the next 5-minute boundary time
     */
    private String getNextBoundaryTime() {
        Calendar cal = Calendar.getInstance();
        int currentMinute = cal.get(Calendar.MINUTE);
        int minutesToAdd = 5 - (currentMinute % 5);
        cal.add(Calendar.MINUTE, minutesToAdd);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);

        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
        return sdf.format(cal.getTime());
    }

    /**
     * CRITICAL FIX: Finalize all candles at 5-minute boundary with proper reset
     */
    private void finalizeAllCandles() {
        synchronized(tradingWaitLock) {
            isCandleFinalizationInProgress = true;
            isTradingAllowed = false;

            try {
                Date boundaryTime = new Date();
                SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");

                System.out.println("\n" + "=".repeat(60));
                System.out.println("🕯️ 5-MINUTE CANDLE BOUNDARY: " + sdf.format(boundaryTime));
                System.out.println("=".repeat(60));

                List<String> instrumentsToFinalize = new ArrayList<>(currentCandleMap.keySet());

                if (instrumentsToFinalize.isEmpty()) {
                    System.out.println("📭 No active candles to finalize");
                    System.out.println("=".repeat(60));

                    // STILL create new candles for all tracked instruments
                    createNewCandlesAtBoundary(boundaryTime);
                    return;
                }

                int ceCount = 0;
                int peCount = 0;

                for (String instrument : instrumentsToFinalize) {
                    CandleData candle = currentCandleMap.get(instrument);
                    if (candle != null) {
                        finalizeCandle(instrument, candle, boundaryTime);

                        if (instrument.contains("CE")) {
                            ceCount++;
                        } else if (instrument.contains("PE")) {
                            peCount++;
                        }
                    }
                }

                // CRITICAL FIX: Immediately create new candles for ALL tracked instruments
                createNewCandlesAtBoundary(boundaryTime);

                System.out.println("✅ Finalized " + instrumentsToFinalize.size() + " candles at boundary");
                System.out.println("   CE Options: " + ceCount + " | PE Options: " + peCount);
                System.out.println("=".repeat(60) + "\n");

            } catch (Exception e) {
                System.err.println("❌ Error in finalizeAllCandles: " + e.getMessage());
                e.printStackTrace();
            } finally {
                // MARK: Finalization complete, notify waiting threads
                isCandleFinalizationInProgress = false;
                isTradingAllowed = true;
                tradingWaitLock.notifyAll(); // WAKE UP ALL WAITING TRADING CYCLES
                System.out.println("🔔 Notified waiting trading cycles that candles are finalized");
            }
        }
    }

    /**
     * NEW: Create new candles immediately after finalization
     */
    private void createNewCandlesAtBoundary(Date boundaryTime) {
        // Clear current candles
        currentCandleMap.clear();

        // Create new candles for all tracked instruments
        Calendar nextCandleStart = Calendar.getInstance();
        nextCandleStart.setTime(boundaryTime);
        nextCandleStart.set(Calendar.SECOND, 0);
        nextCandleStart.set(Calendar.MILLISECOND, 0);

        // Get initial prices for all instruments
        Map<String, Double> initialPrices = new HashMap<>();
        try {
            if (!allTrackedInstruments.isEmpty()) {
                String[] instrumentsArray = allTrackedInstruments.toArray(new String[0]);
                Map<String, Quote> quotes = kiteConnect.getQuote(instrumentsArray);

                for (String instrument : allTrackedInstruments) {
                    Quote quote = quotes.get(instrument);
                    if (quote != null && quote.lastPrice > 0) {
                        initialPrices.put(instrument, quote.lastPrice);
                    }
                }
            }
        } catch (Exception | KiteException e) {
            System.err.println("⚠️ Could not fetch initial prices: " + e.getMessage());
        }

        int count = 0;
        for (String instrument : allTrackedInstruments) {
            double initialPrice = initialPrices.getOrDefault(instrument, 0.0);

            if (count++ < 20) { // Limit logging to avoid spam
                SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
                System.out.println("🕯️ New candle started for " + instrument +
                        " at " + sdf.format(nextCandleStart.getTime()) +
                        " | Initial Price: " + initialPrice);
            }

            // Create candle with initial price (or 0 if not available yet)
            CandleData newCandle = new CandleData(
                    instrument,
                    initialPrice,  // Use actual initial price, not 0!
                    initialPrice,  // High
                    initialPrice > 0 ? initialPrice : Double.MAX_VALUE,  // Low
                    initialPrice,  // Close
                    0,             // Volume
                    nextCandleStart.getTime(),
                    initialPrice > 0 ? initialPrice : 0  // VWAP
            );
            currentCandleMap.put(instrument, newCandle);
        }

        if (allTrackedInstruments.size() > 20) {
            System.out.println("   ... and " + (allTrackedInstruments.size() - 20) + " more instruments");
        }
    }

    /**
     * FIXED: Public method for trading cycle to wait for candle finalization
     * Returns true if trading should proceed, false if timeout or error
     */
    public boolean waitForCandleFinalizationAndProceed() {
        synchronized(tradingWaitLock) {
            try {
                // Check if we're at a 5-minute boundary
                Calendar cal = Calendar.getInstance();
                int minute = cal.get(Calendar.MINUTE);
                int second = cal.get(Calendar.SECOND);

                // If NOT at a boundary (minute % 5 != 0), trading can proceed immediately
                if (minute % 5 != 0) {
                    System.out.println("✅ Not at 5-minute boundary (" + minute + ":" + second + "), trading can proceed immediately");
                    return true;
                }

                // We're at a boundary - check if finalization has completed
                System.out.println("⏳ At 5-minute boundary (" + minute + ":" + second + ") - ensuring candle finalization completes first...");

                // CRITICAL FIX: Always trigger finalization if at boundary and not already in progress
                if (!isCandleFinalizationInProgress && !isTradingAllowed) {
                    System.out.println("🚀 Triggering immediate candle finalization for boundary...");
                    // Execute finalization immediately
                    finalizeAllCandles();
                    System.out.println("✅ Candle finalization complete! Trading can proceed");
                    return true;
                }

                // If finalization is already in progress, wait for it
                if (isCandleFinalizationInProgress) {
                    System.out.println("⏳ Candle finalization in progress - waiting for completion...");

                    // Wait for notification from finalizeAllCandles()
                    long startTime = System.currentTimeMillis();
                    long timeoutMs = 30 * 1000; // 30 second timeout

                    while (isCandleFinalizationInProgress && !isTradingAllowed) {
                        long timeLeft = timeoutMs - (System.currentTimeMillis() - startTime);
                        if (timeLeft <= 0) {
                            System.out.println("❌ Timeout waiting for candle finalization");
                            return false;
                        }

                        System.out.println("   Waiting... (" + (timeLeft/1000) + " seconds remaining)");
                        tradingWaitLock.wait(5000); // Wait 5 seconds at a time
                    }

                    System.out.println("✅ Candle finalization complete! Trading can proceed");
                    return true;
                }

                // If we get here, finalization must have completed
                if (isTradingAllowed) {
                    System.out.println("✅ Trading allowed after completed finalization");
                    return true;
                }

                System.out.println("⚠️ Unexpected state - allowing trading to proceed");
                return true;

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println("❌ Interrupted while waiting for candle finalization");
                return false;
            }
        }
    }

    /**
     * Check if trading should proceed immediately
     */
    public boolean canTradingProceedImmediately() {
        synchronized(tradingWaitLock) {
            return !isCandleFinalizationInProgress && isTradingAllowed;
        }
    }

    /**
     * NEW: Fetch prices for ALL tracked instruments continuously
     */
    private void fetchAndProcessAllPrices() {
        try {
            if (allTrackedInstruments.isEmpty()) {
                return;
            }

            // Convert Set to Array
            String[] instrumentsArray = allTrackedInstruments.toArray(new String[0]);

            // Fetch quotes in batches if too many instruments
            int batchSize = 50; // Kite API limit
            for (int i = 0; i < instrumentsArray.length; i += batchSize) {
                int end = Math.min(instrumentsArray.length, i + batchSize);
                String[] batch = Arrays.copyOfRange(instrumentsArray, i, end);

                try {
                    Map<String, Quote> quotes = kiteConnect.getQuote(batch);
                    Date currentTime = new Date();

                    for (String instrument : batch) {
                        Quote quote = quotes.get(instrument);
                        if (quote != null && quote.lastPrice > 0) {
                            processTick(instrument, quote.lastPrice, quote.averagePrice, currentTime);
                        }
                    }

                    // Small delay between batches to avoid rate limiting
                    if (end < instrumentsArray.length) {
                        Thread.sleep(100);
                    }

                } catch (KiteException e) {
                    System.err.println("❌ KiteException in batch fetch: " + e.getMessage());
                    // Continue with next batch
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

        } catch (Exception e) {
            System.err.println("❌ Error fetching real-time prices: " + e.getMessage());
        }
    }

    // Stop the candle builder
    public void stop() {
        isRunning = false;
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        System.out.println("🛑 RealTimeCandleBuilder stopped");
    }

    /**
     * FIXED: Process a single tick with synchronization
     */
    public void processTick(String instrument, double price, double vwap, Date timestamp) {
        // Ensure instrument is in tracking set
        if (!allTrackedInstruments.contains(instrument)) {
            allTrackedInstruments.add(instrument);
        }

        // Store tick data WITH SYNCHRONIZATION
        synchronized(tickDataMap) {
            List<TickData> ticks = tickDataMap.computeIfAbsent(instrument, k -> new ArrayList<>());
            ticks.add(new TickData(price, vwap, timestamp));

            // Keep only ticks from current candle period (last 5 minutes)
            long currentTime = timestamp.getTime();
            ticks.removeIf(tick -> (currentTime - tick.getTimestamp().getTime()) > (candlePeriodSeconds * 1000));
        }

        // FIX: Check if we need to fix a zero-open candle
        CandleData currentCandle = currentCandleMap.get(instrument);
        if (currentCandle != null && currentCandle.getOpen() == 0 && price > 0) {
            // Fix the candle with actual price data
            CandleData fixedCandle = new CandleData(
                    instrument,
                    price,   // Set open to first real price
                    price,   // High
                    price,   // Low
                    price,   // Close
                    0,       // Volume
                    currentCandle.getTimestamp(), // Keep original timestamp
                    vwap     // VWAP
            );
            currentCandleMap.put(instrument, fixedCandle);

            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
            System.out.println("🔧 Fixed zero-open candle for " + instrument +
                    " at " + sdf.format(timestamp) + " | Open: " + price);
        } else {
            // Normal update
            updateCurrentCandle(instrument, price, vwap, timestamp);
        }
    }

    /**
     * NEW: Add instrument to tracking dynamically
     */
    public void addInstrument(String instrument) {
        if (!allTrackedInstruments.contains(instrument)) {
            allTrackedInstruments.add(instrument);
            System.out.println("➕ Added to continuous tracking: " + instrument);

            // If we have a boundary time, create a new candle immediately
            if (lastBoundaryTime != null) {
                CandleData newCandle = new CandleData(
                        instrument,
                        0, 0, Double.MAX_VALUE, 0, 0,
                        lastBoundaryTime, 0
                );
                currentCandleMap.put(instrument, newCandle);
            }
        }
    }

    // Update current candle with new tick
    private void updateCurrentCandle(String instrument, double price, double vwap, Date timestamp) {
        CandleData currentCandle = currentCandleMap.get(instrument);

        if (currentCandle == null) {
            // Start new candle with actual price as open
            currentCandle = new CandleData(
                    instrument,
                    price,   // Open = actual price
                    price,   // High
                    price,   // Low
                    price,   // Close
                    0,       // Volume
                    timestamp,
                    vwap     // Initial VWAP
            );
            currentCandleMap.put(instrument, currentCandle);

            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
            System.out.println("🕯️ New candle started for " + instrument +
                    " at " + sdf.format(timestamp) + " | Open: " + price);
        } else {
            // FIX: If open is 0, set it to the first price we receive
            if (currentCandle.getOpen() == 0) {
                // Create new candle with proper open price
                currentCandle = new CandleData(
                        instrument,
                        price,   // Set open to first real price
                        Math.max(currentCandle.getHigh(), price),
                        Math.min(currentCandle.getLow(), price),
                        price,   // Close = latest price
                        0,       // Volume
                        currentCandle.getTimestamp(), // Keep original timestamp
                        vwap     // VWAP
                );
                currentCandleMap.put(instrument, currentCandle);

                SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
                System.out.println("🔧 Fixed open price for " + instrument +
                        " at " + sdf.format(timestamp) + " | Open: " + price);
            } else {
                // Normal update
                updateExistingCandle(instrument, currentCandle, price, vwap, timestamp);
            }
        }
    }

    /**
     * FIXED: Update existing candle with new price data with synchronization
     */
    private void updateExistingCandle(String instrument, CandleData currentCandle, double price, double vwap, Date timestamp) {
        double newHigh = Math.max(currentCandle.getHigh(), price);
        double newLow = Math.min(currentCandle.getLow(), price);

        // Calculate average VWAP from all ticks in current candle
        List<TickData> ticks = null;
        double averageVWAP = vwap;

        // SYNCHRONIZED access to tickDataMap
        synchronized(tickDataMap) {
            ticks = tickDataMap.get(instrument);
            if (ticks != null && !ticks.isEmpty()) {
                double sumVWAP = 0;
                int count = 0;
                Calendar candleCal = Calendar.getInstance();
                candleCal.setTime(currentCandle.getTimestamp());
                int candle5MinBlock = candleCal.get(Calendar.MINUTE) / 5;

                // Create a copy to avoid concurrent modification
                List<TickData> ticksCopy = new ArrayList<>(ticks);

                for (TickData tick : ticksCopy) {
                    // Only include ticks from current 5-minute block
                    Calendar tickCal = Calendar.getInstance();
                    tickCal.setTime(tick.getTimestamp());
                    Calendar candleCalCheck = Calendar.getInstance();
                    candleCalCheck.setTime(currentCandle.getTimestamp());

                    int tick5MinBlock = tickCal.get(Calendar.MINUTE) / 5;
                    int candle5MinBlockCheck = candleCalCheck.get(Calendar.MINUTE) / 5;

                    if (tick5MinBlock == candle5MinBlockCheck) {
                        sumVWAP += tick.getVwap();
                        count++;
                    }
                }
                if (count > 0) {
                    averageVWAP = sumVWAP / count;
                }
            }
        }

        CandleData updatedCandle = new CandleData(
                instrument,
                currentCandle.getOpen(),
                newHigh,
                newLow,
                price,  // Close = latest price
                0,      // Volume
                currentCandle.getTimestamp(), // Keep original timestamp
                averageVWAP // Use calculated average VWAP
        );
        currentCandleMap.put(instrument, updatedCandle);
    }

    /**
     * FIXED: Finalize a completed 5-minute candle with synchronization
     */
    private void finalizeCandle(String instrument, CandleData candle, Date endTime) {
        // Calculate final VWAP from all ticks in the completed candle period
        List<TickData> ticks = null;
        double finalVWAP = candle.getVWAP();

        // SYNCHRONIZED BLOCK for tick data access
        synchronized(tickDataMap) {
            ticks = tickDataMap.get(instrument);
            if (ticks != null && !ticks.isEmpty()) {
                double sumVWAP = 0;
                int count = 0;
                Calendar candleCal = Calendar.getInstance();
                candleCal.setTime(candle.getTimestamp());
                int candle5MinBlock = candleCal.get(Calendar.MINUTE) / 5;

                // Create a copy of the list to avoid ConcurrentModificationException
                List<TickData> ticksCopy = new ArrayList<>(ticks);

                for (TickData tick : ticksCopy) {
                    Calendar tickCal = Calendar.getInstance();
                    tickCal.setTime(tick.getTimestamp());
                    int tick5MinBlock = tickCal.get(Calendar.MINUTE) / 5;

                    if (tick5MinBlock == candle5MinBlock) {
                        sumVWAP += tick.getVwap();
                        count++;
                    }
                }
                if (count > 0) {
                    finalVWAP = sumVWAP / count;
                }
            }
        }

        // Create finalized candle with final VWAP
        CandleData finalizedCandle = new CandleData(
                instrument,
                candle.getOpen(),
                candle.getHigh(),
                candle.getLow(),
                candle.getClose(),
                0,
                candle.getTimestamp(),
                finalVWAP
        );

        // Store completed candle
        List<CandleData> completedCandles = completedCandlesMap.computeIfAbsent(instrument, k -> new ArrayList<>());
        completedCandles.add(finalizedCandle);

        // Keep only last 20 candles
        if (completedCandles.size() > 20) {
            completedCandles.remove(0);
        }

        // Clear ticks for this instrument's completed candle period (with synchronization)
        synchronized(tickDataMap) {
            List<TickData> instrumentTicks = tickDataMap.get(instrument);
            if (instrumentTicks != null && !instrumentTicks.isEmpty()) {
                Calendar candleCal = Calendar.getInstance();
                candleCal.setTime(candle.getTimestamp());
                int candle5MinBlock = candleCal.get(Calendar.MINUTE) / 5;

                // Use iterator to safely remove elements
                Iterator<TickData> iterator = instrumentTicks.iterator();
                while (iterator.hasNext()) {
                    TickData tick = iterator.next();
                    Calendar tickCal = Calendar.getInstance();
                    tickCal.setTime(tick.getTimestamp());
                    int tick5MinBlock = tickCal.get(Calendar.MINUTE) / 5;

                    if (tick5MinBlock == candle5MinBlock) {
                        iterator.remove();
                    }
                }
            }
        }

        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm");
        SimpleDateFormat fullFormat = new SimpleDateFormat("HH:mm:ss");

        System.out.println("✅ Finalized 5-min Candle for " + instrument);
        System.out.println("   Time: " + timeFormat.format(candle.getTimestamp()) +
                " to " + fullFormat.format(endTime));
        System.out.println("   O:" + candle.getOpen() +
                " H:" + candle.getHigh() +
                " L:" + candle.getLow() +
                " C:" + candle.getClose() +
                " VWAP:" + String.format("%.2f", finalVWAP));

        // Also print candle statistics
        double candleRange = candle.getHigh() - candle.getLow();
        if (candleRange > 0) {
            double closePosition = (candle.getClose() - candle.getLow()) / candleRange;
            System.out.println("   Stats: Range=" + String.format("%.2f", candleRange) +
                    ", Close at " + String.format("%.2f", closePosition * 100) + "% of range");

        } else {
            System.out.println("   Stats: Zero range candle (flat)");

            // Debug warning for flat PE options
            if (instrument.contains("PE")) {
                System.out.println("   ⚠️  PE Option has no price movement - checking data collection");
            }
        }
    }

    // Get the most recent completed candle
    public CandleData getLastCompletedCandle(String instrument) {
        List<CandleData> candles = completedCandlesMap.get(instrument);
        return (candles != null && !candles.isEmpty()) ?
                candles.get(candles.size() - 1) : null;
    }

    // Get the previous completed candle
    public CandleData getPreviousCompletedCandle(String instrument) {
        List<CandleData> candles = completedCandlesMap.get(instrument);
        return (candles != null && candles.size() >= 2) ?
                candles.get(candles.size() - 2) : null;
    }

    // Get current running candle
    public CandleData getCurrentCandle(String instrument) {
        CandleData current = currentCandleMap.get(instrument);

        // If no current candle exists, check if we should create one
        if (current == null) {
            synchronized(tradingWaitLock) {
                current = currentCandleMap.get(instrument);
                if (current == null) {
                    // Create a new candle starting at the last boundary
                    Calendar cal = Calendar.getInstance();
                    int currentMinute = cal.get(Calendar.MINUTE);
                    int currentSecond = cal.get(Calendar.SECOND);

                    // Calculate last 5-minute boundary
                    int boundaryMinute = (currentMinute / 5) * 5;
                    cal.set(Calendar.MINUTE, boundaryMinute);
                    cal.set(Calendar.SECOND, 0);
                    cal.set(Calendar.MILLISECOND, 0);

                    // If we're near the beginning of a candle period (within 10 seconds)
                    if (currentSecond < 10) {
                        boundaryMinute = Math.max(0, boundaryMinute - 5);
                        cal.set(Calendar.MINUTE, boundaryMinute);
                    }

                    Date boundaryTime = cal.getTime();
                    current = new CandleData(
                            instrument,
                            0, 0, Double.MAX_VALUE, 0, 0,
                            boundaryTime, 0
                    );
                    currentCandleMap.put(instrument, current);

                    SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
                    System.out.println("🆕 Created missing current candle for " + instrument +
                            " at " + sdf.format(boundaryTime));
                }
            }
        }

        return current;
    }

    // Get number of completed candles
    public int getCompletedCandleCount(String instrument) {
        List<CandleData> candles = completedCandlesMap.get(instrument);
        return candles != null ? candles.size() : 0;
    }

    // Check if we have sufficient candle data for analysis
    public boolean hasSufficientCandleData(String instrument) {
        return getCompletedCandleCount(instrument) >= 2;
    }

    // Tick data class
    private static class TickData {
        private final double price;
        private final double vwap;
        private final Date timestamp;

        public TickData(double price, double vwap, Date timestamp) {
            this.price = price;
            this.vwap = vwap;
            this.timestamp = timestamp;
        }

        public double getPrice() { return price; }
        public double getVwap() { return vwap; }
        public Date getTimestamp() { return timestamp; }
    }
}