package com.trading.strategy;

import com.zerodhatech.models.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.*;

/**
 * TEST with PRE-POPULATED HISTORICAL DATA
 * Simulates multiple cycles to build historical data for VWAP crossover testing
 */
@DisplayName("Historical Data Population Test")
public class HistoricalDataTest {

    private VWAPOptionsStrategy strategy;
    private Object batchVWAPAnalyzer;
    private Object historicalDataManager;

    @BeforeEach
    void setUp() throws Exception {
        strategy = new VWAPOptionsStrategy();
        
        // Get access to BatchVWAPAnalyzer and HistoricalDataManager
        Field vwapAnalyzerField = VWAPOptionsStrategy.class.getDeclaredField("vwapAnalyzer");
        vwapAnalyzerField.setAccessible(true);
        batchVWAPAnalyzer = vwapAnalyzerField.get(strategy);

        Field historicalDataManagerField = batchVWAPAnalyzer.getClass().getDeclaredField("historicalDataManager");
        historicalDataManagerField.setAccessible(true);
        historicalDataManager = historicalDataManagerField.get(batchVWAPAnalyzer);

        System.out.println("🚀 INITIALIZED WITH HISTORICAL DATA ACCESS");
    }

    @Test
    @Timeout(180)
    @DisplayName("POPULATE HISTORICAL DATA: Simulate Multiple Cycles")
    void testPopulateHistoricalData() throws Exception {
        System.out.println("📊 POPULATING HISTORICAL DATA FOR VWAP TESTING");
        System.out.println("==============================================");

        String ceSymbol = "NFO:NIFTY25D0226250CE";
        String peSymbol = "NFO:NIFTY25D0226250PE";

        // Simulate historical data for last 3 cycles (15 minutes)
        System.out.println("\n🔄 SIMULATING PAST 3 CYCLES OF HISTORICAL DATA...");

        // CYCLE 1: 15 minutes ago - Both below VWAP
        simulateHistoricalCycle(ceSymbol, 99.50, 101.20, "15 mins ago - Below VWAP");
        simulateHistoricalCycle(peSymbol, 106.80, 108.90, "15 mins ago - Below VWAP");

        // CYCLE 2: 10 minutes ago - Still below VWAP  
        simulateHistoricalCycle(ceSymbol, 100.20, 101.80, "10 mins ago - Below VWAP");
        simulateHistoricalCycle(peSymbol, 107.20, 109.10, "10 mins ago - Below VWAP");

        // CYCLE 3: 5 minutes ago - CE still below, PE crosses above
        simulateHistoricalCycle(ceSymbol, 101.50, 102.00, "5 mins ago - CE Below VWAP");
        simulateHistoricalCycle(peSymbol, 109.60, 109.30, "5 mins ago - PE Above VWAP");

        // CURRENT CYCLE: Now - Test crossover conditions
        System.out.println("\n🎯 CURRENT CYCLE: Testing VWAP Crossover Logic");
        testCurrentCycleWithHistoricalData(ceSymbol, peSymbol);
    }

    @Test
    @Timeout(120)
    @DisplayName("VWAP CROSSOVER SCENARIOS: Different Market Conditions")
    void testVWAPCrossoverScenarios() throws Exception {
        System.out.println("🎯 VWAP CROSSOVER SCENARIOS TEST");
        System.out.println("================================");

        String symbol = "NFO:NIFTY25D0226250PE";

        // Scenario 1: Perfect Crossover (Should trigger BUY)
        testCrossoverScenario(symbol, 
            Arrays.asList(106.5, 107.2, 108.8),  // Historical prices (all below VWAP)
            Arrays.asList(108.9, 109.1, 109.3),  // Historical VWAPs
            110.2, 109.8,                        // Current: Price > VWAP
            "PERFECT CROSSOVER - BUY SIGNAL", true);

        // Scenario 2: No Crossover (Price always above VWAP)
        testCrossoverScenario(symbol,
            Arrays.asList(110.5, 111.2, 111.8),  // Historical prices (all above VWAP)
            Arrays.asList(109.9, 110.1, 110.3),  // Historical VWAPs  
            112.2, 111.8,                        // Current: Price > VWAP but previous was also above
            "NO CROSSOVER - No Signal", false);

        // Scenario 3: False Crossover (Current below VWAP)
        testCrossoverScenario(symbol,
            Arrays.asList(106.5, 107.2, 108.8),  // Historical prices (all below VWAP)
            Arrays.asList(108.9, 109.1, 109.3),  // Historical VWAPs
            109.2, 109.8,                        // Current: Price < VWAP
            "FALSE CROSSOVER - No Signal", false);
    }

    @Test
    @Timeout(150)
    @DisplayName("REAL MARKET DATA: Populate with Live Data")
    void testPopulateWithRealMarketData() throws Exception {
        System.out.println("📊 POPULATING WITH REAL MARKET DATA");
        System.out.println("===================================");

        String ceSymbol = "NFO:NIFTY25D0226250CE";
        String peSymbol = "NFO:NIFTY25D0226250PE";

        // Get current real market data
        double ceCurrentPrice = getRealMarketPrice(ceSymbol);
        double peCurrentPrice = getRealMarketPrice(peSymbol);

        System.out.println("💰 CURRENT REAL MARKET PRICES:");
        System.out.println("   " + ceSymbol + ": " + ceCurrentPrice);
        System.out.println("   " + peSymbol + ": " + peCurrentPrice);

        if (ceCurrentPrice > 0 && peCurrentPrice > 0) {
            // Simulate historical data leading to current prices
            simulateRealisticHistoricalData(ceSymbol, ceCurrentPrice);
            simulateRealisticHistoricalData(peSymbol, peCurrentPrice);

            // Test crossover analysis with real data
            testRealDataCrossoverAnalysis(ceSymbol, peSymbol);
        } else {
            System.out.println("❌ Cannot get real market data - market may be closed");
        }
    }

    private void simulateHistoricalCycle(String symbol, double price, double vwap, String timeframe) throws Exception {
        System.out.println("   📅 " + timeframe + " - " + symbol);
        System.out.println("      Price: " + price + " | VWAP: " + vwap + 
                         " | Status: " + (price < vwap ? "📉 BELOW VWAP" : "📈 ABOVE VWAP"));

        // Update historical data
        Method updateMethod = historicalDataManager.getClass().getMethod(
            "updateHistoricalData", String.class, double.class, double.class);
        updateMethod.invoke(historicalDataManager, symbol, price, vwap);

        // Small delay to simulate time passing
        Thread.sleep(100);
    }

    private void testCurrentCycleWithHistoricalData(String ceSymbol, String peSymbol) throws Exception {
        // Current data that should trigger crossovers based on historical context
        Map<String, Double[]> currentData = new HashMap<>();
        
        // CE: Historical shows below VWAP, current above VWAP → SHOULD CROSSOVER
        currentData.put(ceSymbol, new Double[]{103.5, 102.8}); // Price: 103.5, VWAP: 102.8
        
        // PE: Historical shows mixed, current above VWAP → CHECK LOGIC
        currentData.put(peSymbol, new Double[]{110.5, 109.8}); // Price: 110.5, VWAP: 109.8

        for (Map.Entry<String, Double[]> entry : currentData.entrySet()) {
            String symbol = entry.getKey();
            Double[] data = entry.getValue();
            double currentPrice = data[0];
            double currentVWAP = data[1];

            System.out.println("\n🔍 TESTING: " + symbol);
            System.out.println("   Current Price: " + currentPrice + " | VWAP: " + currentVWAP);

            // Update with current data
            simulateHistoricalCycle(symbol, currentPrice, currentVWAP, "CURRENT");

            // Check if we have sufficient historical data
            Method hasSufficientDataMethod = historicalDataManager.getClass().getMethod("hasSufficientData", String.class);
            boolean hasData = (Boolean) hasSufficientDataMethod.invoke(historicalDataManager, symbol);

            System.out.println("   Sufficient Historical Data: " + (hasData ? "✅ YES" : "❌ NO"));

            if (hasData) {
                // Get previous candle for analysis
                Method getPreviousCandleMethod = historicalDataManager.getClass().getMethod("getPreviousCandle", String.class);
                Object previousCandle = getPreviousCandleMethod.invoke(historicalDataManager, symbol);

                if (previousCandle != null) {
                    analyzeCrossoverLogic(symbol, previousCandle, currentPrice, currentVWAP);
                }
            }
        }
    }

    private void testCrossoverScenario(String symbol, List<Double> historicalPrices, List<Double> historicalVWAPs,
                                     double currentPrice, double currentVWAP, String scenario, boolean expectedCrossover) throws Exception {
        System.out.println("\n🎯 SCENARIO: " + scenario);
        System.out.println("   Expected: " + (expectedCrossover ? "🎯 BUY SIGNAL" : "⏸️ NO SIGNAL"));

        // Clear existing data
        Method clearMethod = historicalDataManager.getClass().getMethod("clearData");
        clearMethod.invoke(historicalDataManager);

        // Populate historical data
        for (int i = 0; i < historicalPrices.size(); i++) {
            simulateHistoricalCycle(symbol, historicalPrices.get(i), historicalVWAPs.get(i), 
                                  "Historical " + (i+1));
        }

        // Add current data
        simulateHistoricalCycle(symbol, currentPrice, currentVWAP, "CURRENT");

        // Test crossover logic
        Method hasSufficientDataMethod = historicalDataManager.getClass().getMethod("hasSufficientData", String.class);
        boolean hasData = (Boolean) hasSufficientDataMethod.invoke(historicalDataManager, symbol);

        if (hasData) {
            Method getPreviousCandleMethod = historicalDataManager.getClass().getMethod("getPreviousCandle", String.class);
            Object previousCandle = getPreviousCandleMethod.invoke(historicalDataManager, symbol);

            boolean actualCrossover = analyzeCrossoverLogic(symbol, previousCandle, currentPrice, currentVWAP);

            // Verify result
            if (actualCrossover == expectedCrossover) {
                System.out.println("   ✅ TEST PASSED: Expected " + expectedCrossover + ", Got " + actualCrossover);
            } else {
                System.out.println("   ❌ TEST FAILED: Expected " + expectedCrossover + ", Got " + actualCrossover);
            }
        }
    }

    private boolean analyzeCrossoverLogic(String symbol, Object previousCandle, double currentPrice, double currentVWAP) throws Exception {
        if (previousCandle == null) {
            System.out.println("   ❌ No previous candle data");
            return false;
        }

        // Extract previous candle data
        Class<?> candleClass = previousCandle.getClass();
        Method getCloseMethod = candleClass.getMethod("getClose");
        Method getVWAPMethod = candleClass.getMethod("getVWAP");

        double previousPrice = (Double) getCloseMethod.invoke(previousCandle);
        double previousVWAP = (Double) getVWAPMethod.invoke(previousCandle);

        // VWAP Crossover Logic
        boolean previousWasBelowVWAP = previousPrice < previousVWAP;
        boolean currentCrossedAboveVWAP = currentPrice > currentVWAP;
        boolean crossoverDetected = previousWasBelowVWAP && currentCrossedAboveVWAP;

        System.out.println("   📊 CROSSOVER ANALYSIS:");
        System.out.println("      Previous: Price(" + previousPrice + ") " + 
                         (previousWasBelowVWAP ? "<" : ">=") + " VWAP(" + previousVWAP + ")");
        System.out.println("      Current:  Price(" + currentPrice + ") " + 
                         (currentCrossedAboveVWAP ? ">" : "<=") + " VWAP(" + currentVWAP + ")");
        System.out.println("      Crossover: " + (crossoverDetected ? "🎯 DETECTED" : "⏸️ NOT DETECTED"));

        return crossoverDetected;
    }

    private void simulateRealisticHistoricalData(String symbol, double currentPrice) throws Exception {
        // Generate realistic historical data based on current price
        Random random = new Random();
        
        // Simulate last 3 candles with realistic price movements
        for (int i = 3; i >= 1; i--) {
            double timeFactor = i * 0.02; // More variation for older data
            double historicalPrice = currentPrice * (1 - timeFactor + (random.nextDouble() * 0.04 - 0.02));
            double historicalVWAP = historicalPrice * (1 + (random.nextDouble() * 0.03 - 0.015));
            
            simulateHistoricalCycle(symbol, historicalPrice, historicalVWAP, i + " cycles ago");
        }

        // Add current data
        double currentVWAP = currentPrice * (1 + (random.nextDouble() * 0.02 - 0.01));
        simulateHistoricalCycle(symbol, currentPrice, currentVWAP, "CURRENT");
    }

    private void testRealDataCrossoverAnalysis(String ceSymbol, String peSymbol) throws Exception {
        System.out.println("\n🔍 REAL DATA CROSSOVER ANALYSIS");
        
        List<String> instruments = Arrays.asList(ceSymbol, peSymbol);
        
        Method analyzeMethod = batchVWAPAnalyzer.getClass().getMethod("analyzeVWAPCrossovers", List.class);
        @SuppressWarnings("unchecked")
        Map<String, Boolean> results = (Map<String, Boolean>) analyzeMethod.invoke(batchVWAPAnalyzer, instruments);

        System.out.println("📊 REAL DATA ANALYSIS RESULTS:");
        for (Map.Entry<String, Boolean> entry : results.entrySet()) {
            String symbol = entry.getKey();
            boolean crossover = entry.getValue();
            
            System.out.println("   " + symbol + ": " + 
                (crossover ? "🎯 BUY SIGNAL - EXECUTE TRADE" : "⏸️ NO SIGNAL - WAIT"));
            
            if (crossover) {
                System.out.println("   🚀 READY FOR ORDER EXECUTION!");
            }
        }
    }

    private double getRealMarketPrice(String symbol) throws Exception {
        try {
            Field kiteConnectField = VWAPOptionsStrategy.class.getDeclaredField("kiteConnect");
            kiteConnectField.setAccessible(true);
            Object kiteConnect = kiteConnectField.get(strategy);

            Method getQuoteMethod = kiteConnect.getClass().getMethod("getQuote", String[].class);
            Map<?, ?> quotes = (Map<?, ?>) getQuoteMethod.invoke(kiteConnect, (Object) new String[]{symbol});

            if (quotes.containsKey(symbol)) {
                Object quote = quotes.get(symbol);
                Field lastPriceField = quote.getClass().getField("lastPrice");
                return (Double) lastPriceField.get(quote);
            }
        } catch (Exception e) {
            System.out.println("⚠️  Could not get real market price for " + symbol);
        }
        return 0.0;
    }

    @Test
    @DisplayName("HISTORICAL DATA VERIFICATION")
    void verifyHistoricalData() throws Exception {
        System.out.println("\n📊 HISTORICAL DATA VERIFICATION");
        System.out.println("==============================");

        String[] testSymbols = {"NFO:NIFTY25D0226250CE", "NFO:NIFTY25D0226250PE"};

        for (String symbol : testSymbols) {
            System.out.println("\n🔍 CHECKING: " + symbol);
            
            Method hasSufficientDataMethod = historicalDataManager.getClass().getMethod("hasSufficientData", String.class);
            boolean hasData = (Boolean) hasSufficientDataMethod.invoke(historicalDataManager, symbol);
            
            System.out.println("   Sufficient Data: " + (hasData ? "✅ YES" : "❌ NO"));

            if (hasData) {
                // Print historical data
                Method getPreviousCandleMethod = historicalDataManager.getClass().getMethod("getPreviousCandle", String.class);
                Object previousCandle = getPreviousCandleMethod.invoke(historicalDataManager, symbol);
                
                if (previousCandle != null) {
                    Class<?> candleClass = previousCandle.getClass();
                    Method getCloseMethod = candleClass.getMethod("getClose");
                    Method getVWAPMethod = candleClass.getMethod("getVWAP");
                    
                    double prevPrice = (Double) getCloseMethod.invoke(previousCandle);
                    double prevVWAP = (Double) getVWAPMethod.invoke(previousCandle);
                    
                    System.out.println("   Previous Candle: Price=" + prevPrice + ", VWAP=" + prevVWAP);
                    System.out.println("   Previous Status: " + (prevPrice < prevVWAP ? "📉 BELOW VWAP" : "📈 ABOVE VWAP"));
                }
            }
        }
    }
}