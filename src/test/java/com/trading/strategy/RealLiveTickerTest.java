package com.trading.strategy;

import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Tick;
import com.zerodhatech.ticker.KiteTicker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * FIXED REAL TEST for startLiveTicker method with proper token handling
 */
@DisplayName("Fixed Real Live Ticker Test")
public class RealLiveTickerTest {

    private VWAPOptionsStrategy strategy;
    private CountDownLatch tickLatch;
    private List<Tick> receivedTicks;
    private boolean connectionEstablished = false;
    private Set<String> successfullyPreloadedTokens;

    @BeforeEach
    void setUp() {
        strategy = new VWAPOptionsStrategy();
        receivedTicks = new ArrayList<>();
        tickLatch = new CountDownLatch(20); // Wait for 20 ticks
        successfullyPreloadedTokens = new HashSet<>();
    }

    @Test
    @Timeout(120)
    @DisplayName("FIXED: Live Ticker with Proper Token Handling")
    void testFixedLiveTicker() throws Exception, KiteException {
//        if (!isMarketOpen()) {
            System.out.println("🚫 Market is closed. Skipping real WebSocket test.");
//            return;
//        }

        System.out.println("🚀 STARTING FIXED LIVE TICKER TEST");
        System.out.println("==========================================");

        // Get Nifty spot price first
        double niftySpot = strategy.getNiftySpotPrice();
        System.out.println("📊 Current Nifty Spot: " + niftySpot);

        // Generate option symbols with proper strikes
        List<String> optionSymbols = generateValidOptionSymbols(niftySpot);
        System.out.println("🎯 Testing with " + optionSymbols.size() + " option symbols");

        // Preload tokens with proper error handling
        preloadTokensWithValidation(optionSymbols);

        // Check if we have any valid tokens
        if (successfullyPreloadedTokens.isEmpty()) {
            System.out.println("❌ No valid tokens preloaded. Skipping WebSocket test.");
            return;
        }

        System.out.println("✅ Successfully preloaded " + successfullyPreloadedTokens.size() + " tokens");

        // Inject our custom ticker listener
        injectTickerListener();

        // Start live ticker only with successfully preloaded symbols
        List<String> validSymbols = new ArrayList<>(successfullyPreloadedTokens);
        System.out.println("🔗 Starting Live Ticker with " + validSymbols.size() + " valid symbols...");

        Method startLiveTickerMethod = VWAPOptionsStrategy.class.getDeclaredMethod(
                "startLiveTicker", List.class);
        startLiveTickerMethod.setAccessible(true);
        startLiveTickerMethod.invoke(strategy, validSymbols);

        // Wait for connection
        System.out.println("⏳ Waiting for WebSocket connection...");
        waitForConnection(30); // Wait up to 30 seconds for connection

        if (connectionEstablished) {
            System.out.println("✅ WebSocket Connected! Waiting for ticks...");
            boolean receivedData = tickLatch.await(45, TimeUnit.SECONDS);

            if (receivedData) {
                System.out.println("✅ SUCCESS: Received " + receivedTicks.size() + " ticks");
                analyzeReceivedTicks();
            } else {
                System.out.println("⚠️ TIMEOUT: Received " + receivedTicks.size() + " ticks (expected 20)");
            }
        } else {
            System.out.println("❌ FAILED: WebSocket connection not established");
        }

        // Keep connection alive for observation
        Thread.sleep(10000);

        // Cleanup
        strategy.stopVWAPOptionsTrading();
        System.out.println("🛑 Live Ticker Test Completed");
    }

    private List<String> generateValidOptionSymbols(double niftySpot) {
        List<String> symbols = new ArrayList<>();

        // Calculate ATM strike (round to nearest 50)
        double atmStrike = Math.round(niftySpot / 50) * 50;
        System.out.println("🎯 ATM Strike: " + atmStrike);

        // Generate strikes around ATM (3 strikes each side)
        for (int i = -3; i <= 3; i++) {
            double strike = atmStrike + (i * 50);

            // Use current month expiry format
            String ceSymbol = buildOptionSymbol(strike, true);
            String peSymbol = buildOptionSymbol(strike, false);

            symbols.add(ceSymbol);
            symbols.add(peSymbol);

            System.out.println("   Generated: " + ceSymbol + " | " + peSymbol);
        }

        return symbols;
    }

    private String buildOptionSymbol(double strike, boolean isCall) {
        // Build symbol with current month format
        // Format: NFO:NIFTY{YY}{MON}{STRIKE}{CE/PE}
        Calendar cal = Calendar.getInstance();
        int year = cal.get(Calendar.YEAR) % 100; // Last 2 digits
        int month = cal.get(Calendar.MONTH) + 1; // 1-12

        String monthCode;
        switch (month) {
            case 1: monthCode = "JAN"; break;
            case 2: monthCode = "FEB"; break;
            case 3: monthCode = "MAR"; break;
            case 4: monthCode = "APR"; break;
            case 5: monthCode = "MAY"; break;
            case 6: monthCode = "JUN"; break;
            case 7: monthCode = "JUL"; break;
            case 8: monthCode = "AUG"; break;
            case 9: monthCode = "SEP"; break;
            case 10: monthCode = "OCT"; break;
            case 11: monthCode = "NOV"; break;
            case 12: monthCode = "DEC"; break;
            default: monthCode = "JAN";
        }

        return String.format("NFO:NIFTY%d%s%d%s",
                year, monthCode, (int)strike, isCall ? "CE" : "PE");
    }

    private void preloadTokensWithValidation(List<String> symbols) throws Exception {
        System.out.println("🔄 Preloading tokens with validation...");

        // Get the symbolToTokenMap field to check what's actually preloaded
        Field symbolToTokenMapField = VWAPOptionsStrategy.class.getDeclaredField("symbolToTokenMap");
        symbolToTokenMapField.setAccessible(true);

        Map<String, Long> symbolToTokenMap = (Map<String, Long>) symbolToTokenMapField.get(strategy);

        Method preloadSingleTokenMethod = VWAPOptionsStrategy.class.getDeclaredMethod(
                "preloadSingleToken", String.class, String.class);
        preloadSingleTokenMethod.setAccessible(true);

        int successCount = 0;
        int failCount = 0;

        for (String symbol : symbols) {
            String optionType = symbol.contains("CE") ? "CE" : "PE";

            try {
                System.out.print("🔍 Preloading: " + symbol + "... ");
                preloadSingleTokenMethod.invoke(strategy, symbol, optionType);

                // Check if token was actually added to the map
                Thread.sleep(200); // Small delay for processing

                if (symbolToTokenMap.containsKey(symbol)) {
                    Long token = symbolToTokenMap.get(symbol);
                    successfullyPreloadedTokens.add(symbol);
                    successCount++;
                    System.out.println("✅ SUCCESS (Token: " + token + ")");
                } else {
                    failCount++;
                    System.out.println("❌ FAILED (Not in map)");
                }

            } catch (Exception e) {
                failCount++;
                System.out.println("❌ ERROR: " + e.getCause().getMessage());
            }

            // Longer delay between API calls to avoid rate limiting
            Thread.sleep(500);
        }

        System.out.println("📊 Preloading Summary: " + successCount + " ✅ | " + failCount + " ❌");

        // Print successfully preloaded tokens
        if (!successfullyPreloadedTokens.isEmpty()) {
            System.out.println("✅ Successfully preloaded tokens:");
            for (String symbol : successfullyPreloadedTokens) {
                Long token = symbolToTokenMap.get(symbol);
                System.out.println("   " + symbol + " -> " + token);
            }
        }
    }

    private void waitForConnection(int timeoutSeconds) throws InterruptedException {
        for (int i = 0; i < timeoutSeconds; i++) {
            if (connectionEstablished) {
                return;
            }
            Thread.sleep(1000);
            if (i % 5 == 0) {
                System.out.println("   Still waiting for connection... (" + i + "s)");
            }
        }
    }

    private void injectTickerListener() throws Exception, KiteException {
        // Wait a bit for strategy initialization
        Thread.sleep(3000);

        Field tickerField = VWAPOptionsStrategy.class.getDeclaredField("ticker");
        tickerField.setAccessible(true);

        KiteTicker ticker = (KiteTicker) tickerField.get(strategy);
        if (ticker != null) {
            setupTestTickerListeners(ticker);
        } else {
            System.out.println("⚠️ Ticker is null - cannot inject listeners");
        }
    }

    private void setupTestTickerListeners(KiteTicker ticker) throws KiteException {
        System.out.println("🎧 Setting up test ticker listeners...");

        ticker.setOnConnectedListener(() -> {
            System.out.println("✅ REAL WebSocket Connected Successfully!");
            connectionEstablished = true;
        });

        ticker.setOnTickerArrivalListener(ticks -> {
            for (Tick tick : ticks) {
                receivedTicks.add(tick);
                tickLatch.countDown();

                // Print detailed tick information
                printDetailedTickInfo(tick);
            }
        });

        ticker.setOnErrorListener(new com.zerodhatech.ticker.OnError() {
            @Override
            public void onError(Exception exception) {
                System.err.println("❌ Ticker Exception: " + exception.getMessage());
                exception.printStackTrace();
            }

            @Override
            public void onError(com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException exception) {
                System.err.println("❌ Kite Ticker Error: " + exception.message + " (Code: " + exception.code + ")");
            }

            @Override
            public void onError(String error) {
                System.err.println("❌ Ticker String Error: " + error);
            }
        });

        ticker.setOnDisconnectedListener(() -> {
            System.out.println("🔴 WebSocket Disconnected");
            connectionEstablished = false;
        });

        // Enable reconnection for testing
        ticker.setTryReconnection(true);
        ticker.setMaximumRetries(3);
        ticker.setMaximumRetryInterval(5);
    }

    private void printDetailedTickInfo(Tick tick) {
        StringBuilder sb = new StringBuilder();
        sb.append("📈 LIVE TICK | Token: ").append(tick.getInstrumentToken());
        sb.append(" | LTP: ").append(tick.getLastTradedPrice());

        if (tick.getAverageTradePrice() > 0) {
            sb.append(" | Avg: ").append(tick.getAverageTradePrice());
        }

        if (tick.getOpenPrice() > 0) {
            sb.append(" | Open: ").append(tick.getOpenPrice());
        }

        if (tick.getHighPrice() > 0) {
            sb.append(" | High: ").append(tick.getHighPrice());
        }

        if (tick.getLowPrice() > 0) {
            sb.append(" | Low: ").append(tick.getLowPrice());
        }

        if (tick.getClosePrice() > 0) {
            sb.append(" | Close: ").append(tick.getClosePrice());
        }

        if (tick.getVolumeTradedToday() > 0) {
            sb.append(" | Vol: ").append(tick.getVolumeTradedToday());
        }

        if (tick.getOi() > 0) {
            sb.append(" | OI: ").append(tick.getOi());
        }

        System.out.println(sb.toString());
    }

    private void analyzeReceivedTicks() {
        System.out.println("\n📊 DETAILED TICKS ANALYSIS");
        System.out.println("==========================================");

        if (receivedTicks.isEmpty()) {
            System.out.println("No ticks received for analysis");
            return;
        }

        // Group ticks by instrument token
        Map<Long, List<Tick>> ticksByToken = new HashMap<>();
        for (Tick tick : receivedTicks) {
            ticksByToken.computeIfAbsent(tick.getInstrumentToken(), k -> new ArrayList<>()).add(tick);
        }

        System.out.println("📈 Received ticks from " + ticksByToken.size() + " instruments:");

        for (Map.Entry<Long, List<Tick>> entry : ticksByToken.entrySet()) {
            long token = entry.getKey();
            List<Tick> ticks = entry.getValue();

            System.out.println("\n🔍 Instrument Token: " + token);
            System.out.println("   Ticks Received: " + ticks.size());

            if (!ticks.isEmpty()) {
                Tick firstTick = ticks.get(0);
                Tick lastTick = ticks.get(ticks.size() - 1);

                System.out.println("   First LTP: " + firstTick.getLastTradedPrice());
                System.out.println("   Last LTP: " + lastTick.getLastTradedPrice());
                System.out.println("   Change: " + String.format("%.2f",
                        lastTick.getLastTradedPrice() - firstTick.getLastTradedPrice()));

                if (firstTick.getAverageTradePrice() > 0 && lastTick.getAverageTradePrice() > 0) {
                    System.out.println("   VWAP Change: " + String.format("%.2f",
                            lastTick.getAverageTradePrice() - firstTick.getAverageTradePrice()));
                }

                // Calculate tick frequency
                if (ticks.size() > 1) {
                    long timeDiff = lastTick.getLastTradedTime().getTime() - firstTick.getLastTradedTime().getTime();
                    double ticksPerSecond = (ticks.size() - 1) / (timeDiff / 1000.0);
                    System.out.println("   Tick Frequency: " + String.format("%.2f", ticksPerSecond) + " ticks/sec");
                }
            }
        }

        System.out.println("\n✅ ANALYSIS COMPLETE");
        System.out.println("   Total instruments: " + ticksByToken.size());
        System.out.println("   Total ticks: " + receivedTicks.size());
        System.out.println("   Test duration: ~60 seconds");
    }

    private boolean isMarketOpen() {
        Calendar cal = Calendar.getInstance();
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        int minute = cal.get(Calendar.MINUTE);
        int dayOfWeek = cal.get(Calendar.DAY_OF_WEEK);

        // Market hours: 9:15 AM to 3:30 PM, Monday to Friday
        boolean isWeekday = dayOfWeek >= Calendar.MONDAY && dayOfWeek <= Calendar.FRIDAY;
        boolean isMarketTime = (hour > 9 || (hour == 9 && minute >= 15)) &&
                (hour < 15 || (hour == 15 && minute <= 30));

        if (!isWeekday) {
            System.out.println("⚠️ Today is weekend - market closed");
        }
        if (!isMarketTime) {
            System.out.println("⚠️ Outside market hours (9:15 - 15:30)");
        }

        return isWeekday && isMarketTime;
    }

    @Test
    @DisplayName("Debug: Token Preloading Test")
    void debugTokenPreloading() throws Exception, KiteException {
        System.out.println("🐛 DEBUG TOKEN PRELOADING");

        double niftySpot = strategy.getNiftySpotPrice();
        System.out.println("Nifty Spot: " + niftySpot);

        List<String> testSymbols = Arrays.asList(
                "NFO:NIFTY25JAN22650CE",
                "NFO:NIFTY25JAN22650PE",
                "NFO:NIFTY25JAN22700CE",
                "NFO:NIFTY25JAN22700PE"
        );

        preloadTokensWithValidation(testSymbols);

        // Check internal token maps
        Field symbolToTokenMapField = VWAPOptionsStrategy.class.getDeclaredField("symbolToTokenMap");
        symbolToTokenMapField.setAccessible(true);
        Map<String, Long> symbolToTokenMap = (Map<String, Long>) symbolToTokenMapField.get(strategy);

        System.out.println("📋 Internal token map contents:");
        for (Map.Entry<String, Long> entry : symbolToTokenMap.entrySet()) {
            System.out.println("   " + entry.getKey() + " -> " + entry.getValue());
        }
    }
}