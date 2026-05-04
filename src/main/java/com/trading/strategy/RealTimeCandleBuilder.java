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

    private final Map<String, List<TickData>> tickDataMap;
    private final Map<String, CandleData> currentCandleMap;
    private final Map<String, List<CandleData>> completedCandlesMap;
    private final Set<String> allTrackedInstruments;

    private final Object tradingWaitLock = new Object();
    private volatile boolean isCandleFinalizationInProgress = false;
    private volatile boolean isTradingAllowed = false;

    private final int candlePeriodSeconds = 300;
    private boolean isRunning = false;
    private final Map<String, Boolean> hasReceivedRealPrice = new ConcurrentHashMap<>();

    public RealTimeCandleBuilder(KiteConnect kiteConnect) {
        this.kiteConnect = kiteConnect;
        this.tickDataMap = new ConcurrentHashMap<>();
        this.currentCandleMap = new ConcurrentHashMap<>();
        this.completedCandlesMap = new ConcurrentHashMap<>();
        this.allTrackedInstruments = ConcurrentHashMap.newKeySet();
        this.scheduler = Executors.newScheduledThreadPool(3);
    }

    public boolean isRunning() { return isRunning; }

    private boolean isMarketOpen() {
        Calendar cal = Calendar.getInstance();
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        int minute = cal.get(Calendar.MINUTE);
        int current = hour * 60 + minute;
        int open = 9 * 60 + 15;
        int close = 15 * 60 + 30;
        return current >= open && current <= close;
    }

    public void start(String[] instruments) {
        if (isRunning) return;
        System.out.println("🚀 Starting RealTimeCandleBuilder for " + instruments.length + " instruments");
        isRunning = true;
        tickDataMap.clear();
        currentCandleMap.clear();
        completedCandlesMap.clear();
        allTrackedInstruments.clear();
        hasReceivedRealPrice.clear();
        allTrackedInstruments.addAll(Arrays.asList(instruments));
        scheduler.scheduleAtFixedRate(this::fetchAndProcessAllPrices, 0, 2, TimeUnit.SECONDS);
        scheduleCandleFinalizationAtBoundaries();
        System.out.println("✅ RealTimeCandleBuilder started - Continuously tracking " + allTrackedInstruments.size() + " instruments");
    }

    private void scheduleCandleFinalizationAtBoundaries() {
        Calendar now = Calendar.getInstance();
        int currentMinute = now.get(Calendar.MINUTE);
        int currentSecond = now.get(Calendar.SECOND);
        int minutesToNextBoundary = 5 - (currentMinute % 5);
        long secondsToNextBoundary = (minutesToNextBoundary * 60L) - currentSecond;
        if (secondsToNextBoundary <= 0) secondsToNextBoundary = 300;
        System.out.println("⏰ Next candle finalization at " + getNextBoundaryTime() + " (in " + secondsToNextBoundary + " seconds)");
        scheduler.scheduleAtFixedRate(() -> {
            if (!isMarketOpen()) return;
            synchronized(tradingWaitLock) { isTradingAllowed = false; }
            finalizeAllCandles();
        }, secondsToNextBoundary * 1000, 300 * 1000, TimeUnit.MILLISECONDS);
    }

    private String getNextBoundaryTime() {
        Calendar cal = Calendar.getInstance();
        int minutesToAdd = 5 - (cal.get(Calendar.MINUTE) % 5);
        cal.add(Calendar.MINUTE, minutesToAdd);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return new SimpleDateFormat("HH:mm:ss").format(cal.getTime());
    }

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

                List<String> instruments = new ArrayList<>(currentCandleMap.keySet());
                if (instruments.isEmpty()) {
                    System.out.println("📭 No active candles to finalize");
                    System.out.println("=".repeat(60));
                    return;
                }

                int ceCount = 0, peCount = 0, finalizedCount = 0;
                for (String inst : instruments) {
                    CandleData candle = currentCandleMap.get(inst);
                    if (candle != null && candle.getTimestamp().before(boundaryTime) &&
                            hasReceivedRealPrice.getOrDefault(inst, false) &&
                            candle.getHigh() > 0 && candle.getLow() < Double.MAX_VALUE) {
                        finalizeCandle(inst, candle, boundaryTime);
                        finalizedCount++;
                        if (inst.contains("CE")) ceCount++;
                        else if (inst.contains("PE")) peCount++;
                    } else {
                        System.out.println("   ⏭️ Skipping finalization for " + inst + " (candle too young or no data)");
                    }
                }

                if (isMarketOpen()) createNewCandlesAtBoundary(boundaryTime);
                System.out.println("✅ Finalized " + finalizedCount + " candles at boundary");
                System.out.println("   CE Options: " + ceCount + " | PE Options: " + peCount);
                System.out.println("=".repeat(60) + "\n");
            } catch (Exception e) {
                System.err.println("❌ Error in finalizeAllCandles: " + e.getMessage());
                e.printStackTrace();
            } finally {
                isCandleFinalizationInProgress = false;
                isTradingAllowed = true;
                tradingWaitLock.notifyAll();
                System.out.println("🔔 Notified waiting trading cycles that candles are finalized");
            }
        }
    }

    private void createNewCandlesAtBoundary(Date boundaryTime) {
        currentCandleMap.clear();
        Calendar nextStart = Calendar.getInstance();
        nextStart.setTime(boundaryTime);
        nextStart.set(Calendar.SECOND, 0);
        nextStart.set(Calendar.MILLISECOND, 0);
        if (!isMarketOpen()) return;
        for (String inst : allTrackedInstruments) {
            CandleData newCandle = new CandleData(inst, 0, 0, Double.MAX_VALUE, 0, 0, nextStart.getTime(), 0);
            currentCandleMap.put(inst, newCandle);
            hasReceivedRealPrice.put(inst, false);
        }
        System.out.println("🕯️ Created new candle placeholders for " + allTrackedInstruments.size() + " instruments at " +
                new SimpleDateFormat("HH:mm:ss").format(nextStart.getTime()));
    }

    public boolean waitForCandleFinalizationAndProceed() {
        synchronized(tradingWaitLock) {
            try {
                if (!isMarketOpen()) return false;
                Calendar cal = Calendar.getInstance();
                int minute = cal.get(Calendar.MINUTE);
                if (minute % 5 != 0) return true;
                if (!isCandleFinalizationInProgress && !isTradingAllowed) {
                    finalizeAllCandles();
                    return true;
                }
                if (isCandleFinalizationInProgress) {
                    System.out.println("⏳ Candle finalization in progress - waiting...");
                    long start = System.currentTimeMillis();
                    while (isCandleFinalizationInProgress && !isTradingAllowed) {
                        if (System.currentTimeMillis() - start > 30000) return false;
                        tradingWaitLock.wait(5000);
                    }
                    return true;
                }
                return isTradingAllowed;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    // Corrected fetchAndProcessAllPrices – no duplicate InterruptedException catch
    private void fetchAndProcessAllPrices() {
        if (allTrackedInstruments.isEmpty() || !isMarketOpen()) return;
        String[] arr = allTrackedInstruments.toArray(new String[0]);
        for (int i = 0; i < arr.length; i += 50) {
            int end = Math.min(arr.length, i + 50);
            String[] batch = Arrays.copyOfRange(arr, i, end);
            try {
                Map<String, Quote> quotes = kiteConnect.getQuote(batch);
                Date now = new Date();
                for (String inst : batch) {
                    Quote q = quotes.get(inst);
                    if (q != null && q.lastPrice > 0) {
                        processTick(inst, q.lastPrice, q.averagePrice, now);
                    }
                }
                if (end < arr.length) {
                    Thread.sleep(100);
                }
            } catch (KiteException | InterruptedException e) {
                // If interrupted, propagate interruption
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
                // For KiteException, just log and continue (silent fail)
            } catch (Exception e) {
                // Other exceptions are ignored to avoid stopping the loop
            }
        }
    }

    public void stop() {
        isRunning = false;
        scheduler.shutdown();
        try { if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) scheduler.shutdownNow(); }
        catch (InterruptedException e) { scheduler.shutdownNow(); }
    }

    // --------------------------------------------------------------
    // FIX #2 (corrected): Use previous candle's VWAP when current is zero
    // --------------------------------------------------------------
    public void processTick(String instrument, double price, double vwap, Date timestamp) {
        if (!allTrackedInstruments.contains(instrument)) allTrackedInstruments.add(instrument);

        synchronized(tickDataMap) {
            List<TickData> ticks = tickDataMap.computeIfAbsent(instrument, k -> new ArrayList<>());
            ticks.add(new TickData(price, vwap, timestamp));
            ticks.removeIf(tick -> (timestamp.getTime() - tick.getTimestamp().getTime()) > candlePeriodSeconds * 1000);
        }

        CandleData current = currentCandleMap.get(instrument);
        // Determine effective VWAP: if vwap > 0 use it, else try previous completed candle's VWAP
        double effectiveVwap = vwap;
        if (effectiveVwap <= 0) {
            CandleData prevCandle = getLastCompletedCandle(instrument);
            if (prevCandle != null && prevCandle.getVWAP() > 0) {
                effectiveVwap = prevCandle.getVWAP();
            } else {
                effectiveVwap = price; // ultimate fallback
            }
        }

        if (current == null) {
            current = new CandleData(instrument, price, price, price, price, 0, timestamp, effectiveVwap);
            currentCandleMap.put(instrument, current);
            hasReceivedRealPrice.put(instrument, true);
        } else {
            if (current.getOpen() == 0 && price > 0) {
                CandleData fixed = new CandleData(instrument, price,
                        Math.max(current.getHigh(), price),
                        Math.min(current.getLow(), price),
                        price, 0, current.getTimestamp(), effectiveVwap);
                currentCandleMap.put(instrument, fixed);
                hasReceivedRealPrice.put(instrument, true);
            } else {
                updateExistingCandle(instrument, current, price, effectiveVwap, timestamp);
                hasReceivedRealPrice.put(instrument, true);
            }
        }
    }

    private void updateExistingCandle(String instrument, CandleData current, double price, double vwap, Date timestamp) {
        double newHigh = Math.max(current.getHigh(), price);
        double newLow = Math.min(current.getLow(), price);
        double avgVwap = vwap;
        synchronized(tickDataMap) {
            List<TickData> ticks = tickDataMap.get(instrument);
            if (ticks != null && !ticks.isEmpty()) {
                double sum = 0;
                int count = 0;
                Calendar candleCal = Calendar.getInstance();
                candleCal.setTime(current.getTimestamp());
                int candleBlock = candleCal.get(Calendar.MINUTE) / 5;
                for (TickData tick : ticks) {
                    Calendar tickCal = Calendar.getInstance();
                    tickCal.setTime(tick.getTimestamp());
                    if ((tickCal.get(Calendar.MINUTE) / 5) == candleBlock) {
                        double tvwap = tick.getVwap();
                        sum += (tvwap > 0) ? tvwap : tick.getPrice();
                        count++;
                    }
                }
                if (count > 0) avgVwap = sum / count;
            }
        }
        CandleData updated = new CandleData(instrument, current.getOpen(), newHigh, newLow, price, 0, current.getTimestamp(), avgVwap);
        currentCandleMap.put(instrument, updated);
    }

    private void finalizeCandle(String instrument, CandleData candle, Date endTime) {
        double finalVWAP = candle.getVWAP();
        synchronized(tickDataMap) {
            List<TickData> ticks = tickDataMap.get(instrument);
            if (ticks != null && !ticks.isEmpty()) {
                double sum = 0;
                int count = 0;
                Calendar candleCal = Calendar.getInstance();
                candleCal.setTime(candle.getTimestamp());
                int candleBlock = candleCal.get(Calendar.MINUTE) / 5;
                for (TickData tick : ticks) {
                    Calendar tickCal = Calendar.getInstance();
                    tickCal.setTime(tick.getTimestamp());
                    if ((tickCal.get(Calendar.MINUTE) / 5) == candleBlock) {
                        double tvwap = tick.getVwap();
                        sum += (tvwap > 0) ? tvwap : tick.getPrice();
                        count++;
                    }
                }
                if (count > 0) finalVWAP = sum / count;
            }
        }

        // FIX #2: If still zero, use previous completed candle's VWAP
        if (finalVWAP <= 0) {
            CandleData prev = getLastCompletedCandle(instrument);
            if (prev != null && prev.getVWAP() > 0) {
                finalVWAP = prev.getVWAP();
            } else {
                finalVWAP = candle.getClose(); // last resort
            }
        }

        CandleData finalized = new CandleData(instrument, candle.getOpen(), candle.getHigh(), candle.getLow(),
                candle.getClose(), 0, candle.getTimestamp(), finalVWAP);
        CandleHistoryManager.getInstance().addCandle(instrument, finalized);

        List<CandleData> completed = completedCandlesMap.computeIfAbsent(instrument, k -> new ArrayList<>());
        completed.add(finalized);
        if (completed.size() > 50) completed.remove(0);

        // Remove ticks belonging to this candle
        synchronized(tickDataMap) {
            List<TickData> ticks = tickDataMap.get(instrument);
            if (ticks != null) {
                Calendar candleCal = Calendar.getInstance();
                candleCal.setTime(candle.getTimestamp());
                int candleBlock = candleCal.get(Calendar.MINUTE) / 5;
                ticks.removeIf(tick -> {
                    Calendar tc = Calendar.getInstance();
                    tc.setTime(tick.getTimestamp());
                    return (tc.get(Calendar.MINUTE) / 5) == candleBlock;
                });
            }
        }

        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm");
        SimpleDateFormat fullFormat = new SimpleDateFormat("HH:mm:ss");
        System.out.println("✅ Finalized 5-min Candle for " + instrument);
        System.out.println("   Time: " + timeFormat.format(candle.getTimestamp()) + " to " + fullFormat.format(endTime));
        System.out.println("   O:" + candle.getOpen() + " H:" + candle.getHigh() + " L:" + candle.getLow() +
                " C:" + candle.getClose() + " VWAP:" + String.format("%.2f", finalVWAP));
        double range = candle.getHigh() - candle.getLow();
        if (range > 0) {
            double closePos = (candle.getClose() - candle.getLow()) / range;
            System.out.println("   Stats: Range=" + String.format("%.2f", range) +
                    ", Close at " + String.format("%.2f", closePos * 100) + "% of range");
        } else {
            System.out.println("   Stats: Zero range candle (flat)");
        }
    }

    public CandleData getLastCompletedCandle(String instrument) {
        List<CandleData> candles = completedCandlesMap.get(instrument);
        return (candles != null && !candles.isEmpty()) ? candles.get(candles.size() - 1) : null;
    }

    public CandleData getPreviousCompletedCandle(String instrument) {
        List<CandleData> candles = completedCandlesMap.get(instrument);
        return (candles != null && candles.size() >= 2) ? candles.get(candles.size() - 2) : null;
    }

    public CandleData getCompletedCandleAtIndex(String instrument, int indexFromEnd) {
        List<CandleData> candles = completedCandlesMap.get(instrument);
        return (candles != null && candles.size() >= indexFromEnd) ? candles.get(candles.size() - indexFromEnd) : null;
    }

    public CandleData getCurrentCandle(String instrument) {
        CandleData current = currentCandleMap.get(instrument);
        if (current == null && isMarketOpen()) {
            synchronized(tradingWaitLock) {
                current = currentCandleMap.get(instrument);
                if (current == null) {
                    Calendar cal = Calendar.getInstance();
                    int boundaryMinute = (cal.get(Calendar.MINUTE) / 5) * 5;
                    cal.set(Calendar.MINUTE, boundaryMinute);
                    cal.set(Calendar.SECOND, 0);
                    cal.set(Calendar.MILLISECOND, 0);
                    current = new CandleData(instrument, 0, 0, Double.MAX_VALUE, 0, 0, cal.getTime(), 0);
                    currentCandleMap.put(instrument, current);
                    hasReceivedRealPrice.put(instrument, false);
                }
            }
        }
        return current;
    }

    public int getCompletedCandleCount(String instrument) {
        List<CandleData> candles = completedCandlesMap.get(instrument);
        return candles != null ? candles.size() : 0;
    }

    public void addInstrument(String instrument) {
        if (!allTrackedInstruments.contains(instrument)) {
            allTrackedInstruments.add(instrument);
            hasReceivedRealPrice.put(instrument, false);
        }
    }

    private static class TickData {
        private final double price, vwap;
        private final Date timestamp;
        TickData(double price, double vwap, Date timestamp) {
            this.price = price;
            this.vwap = vwap;
            this.timestamp = timestamp;
        }
        double getPrice() { return price; }
        double getVwap() { return vwap; }
        Date getTimestamp() { return timestamp; }
    }
}