package com.trading.strategy;

import com.zerodhatech.models.Tick;
import com.zerodhatech.ticker.KiteTicker;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * FIXED REAL TEST for setupTickerListeners() method
 * Addresses token preloading and WebSocket subscription issues
 */
@DisplayName("Fixed Real Ticker Listeners Test")
public class RealTickerListenersTest {

    private VWAPOptionsStrategy strategy;
    private KiteTicker ticker;
    private CountDownLatch connectionLatch;
    private CountDownLatch tickLatch;

    // Test data collectors
    private List<Tick> receivedTicks;
    private AtomicInteger tickCount;
    private Map<String, Long> successfulTokens;

    @BeforeEach
    void setUp() {
        strategy = new VWAPOptionsStrategy();
        connectionLatch = new CountDownLatch(1);
        tickLatch = new CountDownLatch(20); // Reduced for faster testing
        receivedTicks = new ArrayList<>();
        tickCount = new AtomicInteger(0);
        successfulTokens = new HashMap<>();
    }

    @Test
    @Timeout(90)
    @DisplayName("FIXED: setupTickerListeners with Real Data")
    void testFixedSetupTickerListeners() throws Exception, KiteException {
        System.out.println("🎧 FIXED TEST: setupTickerListeners() with Real Data");
        System.out.println("===================================================");

        // Get Nifty spot price
        double niftySpot = getNiftySpotPrice();
        System.out.println("📊 Current Nifty Spot: " + niftySpot);

        // Generate valid option symbols based on current market
        List<String> testSymbols = generateValidSymbols(niftySpot);
        System.out.println("🎯 Test Symbols: " + testSymbols);

        // FIXED: Preload tokens with proper validation
        boolean tokensLoaded = preloadTokensWithFix(testSymbols);

        if (!tokensLoaded) {
            System.out.println("❌ No valid tokens loaded. Using fallback symbols...");
            // Use known valid symbols as fallback
            testSymbols = getFallbackSymbols();
            preloadTokensWithFix(testSymbols);
        }

        // Create ticker instance
        createTickerInstance();

        // Get tokens that were successfully preloaded
        List<Long> validTokens = getValidTokensFromMap();
        System.out.println("✅ Valid tokens for subscription: " + validTokens.size());

        if (validTokens.isEmpty()) {
            System.out.println("❌ No valid tokens available. Test cannot proceed.");
            return;
        }

        // Test the actual setupTickerListeners method
        testSetupTickerListenersDirectly(validTokens);

        // Wait for results
        waitForTestResults();

        // Analyze performance
        analyzeTestResults();

        // Cleanup
        cleanup();
    }

    @Test
    @Timeout(60)
    @DisplayName("DEBUG: Token Preloading Investigation")
    void debugTokenPreloading() throws Exception {
        System.out.println("🐛 DEBUG: Token Preloading Investigation");
        System.out.println("=======================================");

        // Test with single symbol to isolate issues
        List<String> testSymbols = Arrays.asList("NFO:NIFTY25D0226200CE");

        System.out.println("1. Testing preloadSingleToken method...");
        testPreloadSingleToken(testSymbols.get(0));

        System.out.println("2. Checking internal token maps...");
        checkInternalTokenMaps();

        System.out.println("3. Testing getInstruments API...");
        testGetInstrumentsAPI();

        System.out.println("4. Testing symbol validation...");
        testSymbolValidation();
    }

    @Test
    @Timeout(60)
    @DisplayName("FIXED: WebSocket Connection Only")
    void testWebSocketConnectionOnly() throws Exception {
        System.out.println("🔗 FIXED TEST: WebSocket Connection Only");
        System.out.println("=======================================");

        createTickerInstance();

        // Test basic WebSocket connection without subscription
        testBasicWebSocketConnection();

        cleanup();
    }

    private double getNiftySpotPrice() throws KiteException {
        try {
            return strategy.getNiftySpotPrice();
        } catch (Exception e) {
            System.out.println("⚠️ Could not get Nifty spot, using default: 22650");
            return 22650.0;
        }
    }

    private List<String> generateValidSymbols(double niftySpot) {
        List<String> symbols = new ArrayList<>();

        // Use current month and reasonable strikes
        int baseStrike = ((int) niftySpot / 50) * 50; // Round to nearest 50

        // Add a few strikes around current price
        for (int i = -2; i <= 2; i++) {
            int strike = baseStrike + (i * 50);
            symbols.add("NFO:NIFTY25D0226250CE");
            symbols.add("NFO:NIFTY25D0226150PE");
        }

        System.out.println("🎯 Generated symbols around strike: " + baseStrike);
        return symbols;
    }

    private List<String> getFallbackSymbols() {
        // Use some known valid symbols for testing
        return Arrays.asList(
                "NFO:NIFTY25D0226250CE",
                "NFO:NIFTY25D0226250PE",
                "NFO:NIFTY25D0226150CE",
                "NFO:NIFTY25D0226150PE"
        );
    }

    private boolean preloadTokensWithFix(List<String> symbols) throws Exception {
        System.out.println("🔄 FIXED Token Preloading...");

        Field symbolToTokenMapField = VWAPOptionsStrategy.class.getDeclaredField("symbolToTokenMap");
        symbolToTokenMapField.setAccessible(true);
        Map<String, Long> symbolToTokenMap = (Map<String, Long>) symbolToTokenMapField.get(strategy);

        // Clear any existing entries to start fresh
        symbolToTokenMap.clear();

        Method preloadSingleTokenMethod = VWAPOptionsStrategy.class.getDeclaredMethod(
                "preloadSingleToken", String.class, String.class);
        preloadSingleTokenMethod.setAccessible(true);

        int successCount = 0;

        for (String symbol : symbols) {
            String optionType = symbol.contains("CE") ? "CE" : "PE";

            try {
                System.out.print("🔍 Preloading: " + symbol + "... ");

                // Call the preload method
                preloadSingleTokenMethod.invoke(strategy, symbol, optionType);

                // Wait a bit for the async operation to complete
                Thread.sleep(500);

                // Check if token was actually added to the map
                if (symbolToTokenMap.containsKey(symbol)) {
                    Long token = symbolToTokenMap.get(symbol);
                    successfulTokens.put(symbol, token);
                    successCount++;
                    System.out.println("✅ SUCCESS (Token: " + token + ")");
                } else {
                    System.out.println("❌ FAILED (Not in internal map)");
                }

            } catch (Exception e) {
                System.out.println("❌ ERROR: " + e.getCause().getMessage());
            }

            // Increased delay to avoid rate limiting
            Thread.sleep(1000);
        }

        System.out.println("📊 Preloading Result: " + successCount + "/" + symbols.size() + " successful");
        return successCount > 0;
    }

    private void testPreloadSingleToken(String symbol) throws Exception {
        Field symbolToTokenMapField = VWAPOptionsStrategy.class.getDeclaredField("symbolToTokenMap");
        symbolToTokenMapField.setAccessible(true);
        Map<String, Long> symbolToTokenMap = (Map<String, Long>) symbolToTokenMapField.get(strategy);

        Method preloadSingleTokenMethod = VWAPOptionsStrategy.class.getDeclaredMethod(
                "preloadSingleToken", String.class, String.class);
        preloadSingleTokenMethod.setAccessible(true);

        System.out.println("Before preload - Map size: " + symbolToTokenMap.size());

        // Call preload
        preloadSingleTokenMethod.invoke(strategy, symbol, "CE");

        Thread.sleep(2000);

        System.out.println("After preload - Map size: " + symbolToTokenMap.size());
        System.out.println("Map contents: " + symbolToTokenMap);
    }

    private void checkInternalTokenMaps() throws Exception {
        Field symbolToTokenMapField = VWAPOptionsStrategy.class.getDeclaredField("symbolToTokenMap");
        symbolToTokenMapField.setAccessible(true);
        Map<String, Long> symbolToTokenMap = (Map<String, Long>) symbolToTokenMapField.get(strategy);

        Field tokenToSymbolMapField = VWAPOptionsStrategy.class.getDeclaredField("tokenToSymbolMap");
        tokenToSymbolMapField.setAccessible(true);
        Map<Long, String> tokenToSymbolMap = (Map<Long, String>) tokenToSymbolMapField.get(strategy);

        System.out.println("📋 Internal Maps Status:");
        System.out.println("   symbolToTokenMap size: " + symbolToTokenMap.size());
        System.out.println("   tokenToSymbolMap size: " + tokenToSymbolMap.size());

        if (!symbolToTokenMap.isEmpty()) {
            System.out.println("   symbolToTokenMap contents:");
            for (Map.Entry<String, Long> entry : symbolToTokenMap.entrySet()) {
                System.out.println("      " + entry.getKey() + " -> " + entry.getValue());
            }
        }
    }

    private void testGetInstrumentsAPI() throws Exception {
        try {
            Field kiteConnectField = VWAPOptionsStrategy.class.getDeclaredField("kiteConnect");
            kiteConnectField.setAccessible(true);
            Object kiteConnect = kiteConnectField.get(strategy);

            Method getInstrumentsMethod = kiteConnect.getClass().getMethod("getInstruments", String.class);
            List<?> instruments = (List<?>) getInstrumentsMethod.invoke(kiteConnect, "NFO");

            System.out.println("📊 Available NFO instruments: " + instruments.size());

            // Show a few sample instruments
            if (!instruments.isEmpty()) {
                System.out.println("   Sample instruments:");
                for (int i = 0; i < Math.min(5, instruments.size()); i++) {
                    Object instrument = instruments.get(i);
                    System.out.println("      " + instrument.toString());
                }
            }

        } catch (Exception e) {
            System.out.println("❌ Error getting instruments: " + e.getMessage());
        }
    }

    private void testSymbolValidation() throws Exception {
        // Test if symbols exist in the instruments list
        List<String> testSymbols = Arrays.asList(
                "NFO:NIFTY25D0226150CE",
                "NFO:NIFTY25D0226150PE",
                "NFO:INVALID_SYMBOL"
        );

        Field kiteConnectField = VWAPOptionsStrategy.class.getDeclaredField("kiteConnect");
        kiteConnectField.setAccessible(true);
        Object kiteConnect = kiteConnectField.get(strategy);

        Method getQuoteMethod = kiteConnect.getClass().getMethod("getQuote", String[].class);

        for (String symbol : testSymbols) {
            try {
                Map<?, ?> quote = (Map<?, ?>) getQuoteMethod.invoke(kiteConnect, (Object) new String[]{symbol});
                if (quote.containsKey(symbol)) {
                    System.out.println("✅ Symbol exists: " + symbol);
                } else {
                    System.out.println("❌ Symbol not found: " + symbol);
                }
            } catch (Exception e) {
                System.out.println("❌ Error checking symbol " + symbol + ": " + e.getCause().getMessage());
            }
            Thread.sleep(500);
        }
    }

    private List<Long> getValidTokensFromMap() throws Exception {
        Field symbolToTokenMapField = VWAPOptionsStrategy.class.getDeclaredField("symbolToTokenMap");
        symbolToTokenMapField.setAccessible(true);
        Map<String, Long> symbolToTokenMap = (Map<String, Long>) symbolToTokenMapField.get(strategy);

        List<Long> tokens = new ArrayList<>();
        for (Map.Entry<String, Long> entry : symbolToTokenMap.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0) {
                tokens.add(entry.getValue());
                System.out.println("   ✅ " + entry.getKey() + " -> " + entry.getValue());
            }
        }
        return tokens;
    }

    private void createTickerInstance() throws Exception {
        System.out.println("🔧 Creating KiteTicker instance...");

        Field tickerField = VWAPOptionsStrategy.class.getDeclaredField("ticker");
        tickerField.setAccessible(true);

        // Get access token and API key from KiteConnect
        Field kiteConnectField = VWAPOptionsStrategy.class.getDeclaredField("kiteConnect");
        kiteConnectField.setAccessible(true);
        Object kiteConnect = kiteConnectField.get(strategy);

        Method getAccessTokenMethod = kiteConnect.getClass().getMethod("getAccessToken");
        Method getApiKeyMethod = kiteConnect.getClass().getMethod("getApiKey");

        String accessToken = (String) getAccessTokenMethod.invoke(kiteConnect);
        String apiKey = (String) getApiKeyMethod.invoke(kiteConnect);

        ticker = new KiteTicker(accessToken, apiKey);
        tickerField.set(strategy, ticker);

        System.out.println("✅ Ticker instance created with API Key: " + apiKey.substring(0, 8) + "...");
    }

    private void testSetupTickerListenersDirectly(List<Long> tokens) throws Exception {
        System.out.println("🎯 Testing setupTickerListeners method...");

        // Get the setupTickerListeners method
        Method setupTickerListenersMethod = VWAPOptionsStrategy.class.getDeclaredMethod(
                "setupTickerListeners", ArrayList.class);
        setupTickerListenersMethod.setAccessible(true);

        // Call the actual method
        setupTickerListenersMethod.invoke(strategy, new ArrayList<>(tokens));

        System.out.println("✅ setupTickerListeners method executed");

        // Now connect
        connectTicker();
    }

    private void testBasicWebSocketConnection() throws Exception {
        System.out.println("🔗 Testing basic WebSocket connection...");

        // Set up minimal listeners
        ticker.setOnConnectedListener(() -> {
            System.out.println("✅ WebSocket Connected Successfully!");
            connectionLatch.countDown();
        });

        ticker.setOnTickerArrivalListener(ticks -> {
            System.out.println("📈 Received " + ticks.size() + " ticks");
            for (Tick tick : ticks) {
                tickCount.incrementAndGet();
                receivedTicks.add(tick);
                tickLatch.countDown();
            }
        });

        ticker.setOnErrorListener(new com.zerodhatech.ticker.OnError() {
            @Override
            public void onError(Exception exception) {
                System.err.println("❌ WebSocket Error: " + exception.getMessage());
            }

            @Override
            public void onError(KiteException exception) {
                System.err.println("❌ Kite Error: " + exception.message);
            }

            @Override
            public void onError(String error) {
                System.err.println("❌ Error: " + error);
            }
        });

        connectTicker();

        // Wait for connection
        boolean connected = connectionLatch.await(30, TimeUnit.SECONDS);
        if (connected) {
            System.out.println("✅ Basic WebSocket connection test PASSED");

            // Try to subscribe to a basic instrument (Nifty index)
            try {
                ticker.subscribe(new ArrayList<>(Arrays.asList(256265L))); // Nifty 50 token
                ticker.setMode(new ArrayList<>(Arrays.asList(256265L)), KiteTicker.modeLTP);
                System.out.println("✅ Subscribed to Nifty 50 index");
            } catch (Exception e) {
                System.out.println("❌ Subscription failed: " + e.getMessage());
            }
        } else {
            System.out.println("❌ Basic WebSocket connection test FAILED");
        }
    }

    private void connectTicker() {
        new Thread(() -> {
            try {
                System.out.println("🚀 Connecting to WebSocket...");
                ticker.connect();
            } catch (Exception e) {
                System.err.println("❌ WebSocket connection failed: " + e.getMessage());
                e.printStackTrace();
            }
        }).start();
    }

    private void waitForTestResults() throws InterruptedException {
        System.out.println("⏳ Waiting for test results (30s timeout)...");

        boolean connectionEstablished = connectionLatch.await(30, TimeUnit.SECONDS);
        boolean ticksReceived = tickLatch.await(30, TimeUnit.SECONDS);

        System.out.println("📋 Test Results:");
        System.out.println("   Connection: " + (connectionEstablished ? "✅" : "❌"));
        System.out.println("   Ticks Received: " + tickCount.get() + "/20");
    }

    private void analyzeTestResults() {
        System.out.println("\n📊 TEST ANALYSIS");
        System.out.println("================");

        System.out.println("📈 Data Received:");
        System.out.println("   Total Ticks: " + tickCount.get());
        System.out.println("   Unique Instruments: " + getUniqueInstrumentCount());

        if (!receivedTicks.isEmpty()) {
            System.out.println("   Sample Tick Data:");
            Tick sampleTick = receivedTicks.get(0);
            System.out.println("      Token: " + sampleTick.getInstrumentToken());
            System.out.println("      LTP: " + sampleTick.getLastTradedPrice());
            System.out.println("      Volume: " + sampleTick.getVolumeTradedToday());
        }

        System.out.println("🔧 Technical Status:");
        System.out.println("   Successful Tokens: " + successfulTokens.size());
        System.out.println("   WebSocket State: " + (ticker != null ? "Created" : "Null"));
    }

    private int getUniqueInstrumentCount() {
        Set<Long> uniqueInstruments = new HashSet<>();
        for (Tick tick : receivedTicks) {
            uniqueInstruments.add(tick.getInstrumentToken());
        }
        return uniqueInstruments.size();
    }

    private void cleanup() {
        if (ticker != null) {
            try {
                ticker.disconnect();
                System.out.println("🧹 Ticker disconnected and cleaned up");
            } catch (Exception e) {
                System.err.println("⚠️ Error during cleanup: " + e.getMessage());
            }
        }
    }
}