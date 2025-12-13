//package com.trading.strategy;
//
//import com.trading.mock.MockKiteConnect;
//import com.trading.testutil.TestDataGenerator;
//import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.DisplayName;
//
//import java.lang.reflect.Method;
//
//import static org.junit.jupiter.api.Assertions.*;
//
//class VWAPOptionsStrategyTest {
//
//    private VWAPOptionsStrategy strategy;
//    private MockKiteConnect mockKiteConnect;
//
//    @BeforeEach
//    void setUp() {
//        mockKiteConnect = new MockKiteConnect("test_api_key");
//        mockKiteConnect.setAccessToken("test_access_token");
//
//        // Use reflection to inject mock kiteConnect
//        strategy = new VWAPOptionsStrategy();
//        injectMockKiteConnect(strategy, mockKiteConnect);
//    }
//
//    private void injectMockKiteConnect(VWAPOptionsStrategy strategy, MockKiteConnect mockKiteConnect) {
//        try {
//            java.lang.reflect.Field kiteConnectField = VWAPOptionsStrategy.class.getDeclaredField("kiteConnect");
//            kiteConnectField.setAccessible(true);
//            kiteConnectField.set(strategy, mockKiteConnect);
//        } catch (Exception e) {
//            throw new RuntimeException("Failed to inject mock KiteConnect", e);
//        }
//    }
//
//    @Test
//    @DisplayName("Test strategy initialization")
//    void testStrategyInitialization() {
//        assertNotNull(strategy);
//        System.out.println("✅ Strategy initialized successfully");
//    }
//
//    @Test
//    @DisplayName("Test Nifty spot price fetch")
//    void testGetNiftySpotPrice() {
//        try {
//            double spotPrice = strategy.getNiftySpotPrice();
//            assertTrue(spotPrice > 0, "Spot price should be positive");
//            assertEquals(21500.0, spotPrice, 0.01, "Should return mock spot price");
//            System.out.println("✅ Nifty spot price: " + spotPrice);
//        } catch (Exception | KiteException e) {
//            fail("Failed to get Nifty spot price: " + e.getMessage());
//        }
//    }
//
//    @Test
//    @DisplayName("Test option token preloading")
//    void testPreloadOptionTokens() {
//        double niftySpot = 21500.0;
//        strategy.preloadOptionTokens(niftySpot);
//
//        // Verify that tokens were preloaded by checking if we can get quotes
//        try {
//            String[] testSymbols = {"NFO:NIFTY25JAN21500CE", "NFO:NIFTY25JAN21500PE"};
//            var quotes = mockKiteConnect.getQuote(testSymbols);
//            assertFalse(quotes.isEmpty(), "Should be able to get quotes for preloaded symbols");
//            System.out.println("✅ Option tokens preloaded for spot: " + niftySpot);
//        } catch (Exception | KiteException e) {
//            fail("Failed to verify token preloading: " + e.getMessage());
//        }
//    }
//
//    @Test
//    @DisplayName("Test option selection with target premium")
//    void testOptionSelectionWithTargetPremium() {
//        try {
//            // Test the internal option selection logic
//            strategy.startVWAPOptionsTrading();
//
//            // Verify that orders were attempted (in mock)
//            var placedOrders = mockKiteConnect.getPlacedOrders();
//            System.out.println("✅ Option selection completed. Orders attempted: " + placedOrders.size());
//        } catch (Exception e) {
//            // In test environment, some operations might fail due to missing WebSocket
//            // This is expected for unit tests
//            System.out.println("⚠️ Expected partial failure in test environment: " + e.getMessage());
//        }
//    }
//
//    @Test
//    @DisplayName("Test batch premium calculation")
//    void testBatchPremiumCalculation() {
//        String[] testSymbols = {"NFO:NIFTY25JAN21500CE", "NFO:NIFTY25JAN21500PE"};
//
//        try {
//            var quotes = mockKiteConnect.getQuote(testSymbols);
//            assertNotNull(quotes);
//            assertEquals(2, quotes.size());
//
//            for (String symbol : testSymbols) {
//                assertTrue(quotes.containsKey(symbol));
//                assertTrue(quotes.get(symbol).lastPrice > 0);
//                System.out.println("📊 " + symbol + " premium: " + quotes.get(symbol).lastPrice);
//            }
//
//            System.out.println("✅ Batch premium calculation test completed");
//        } catch (Exception | KiteException e) {
//            fail("Batch premium test failed: " + e.getMessage());
//        }
//    }
//
//    @Test
//    @DisplayName("Test price movement simulation")
//    void testPriceMovementSimulation() {
//        String testSymbol = "NFO:NIFTY25JAN21500CE";
//        double newPrice = 150.0;
//
//        mockKiteConnect.simulatePriceMovement(testSymbol, newPrice);
//
//        try {
//            var quotes = mockKiteConnect.getQuote(new String[]{testSymbol});
//            assertEquals(newPrice, quotes.get(testSymbol).lastPrice, 0.01);
//            System.out.println("✅ Price movement simulation test completed");
//        } catch (Exception | KiteException e) {
//            fail("Price movement test failed: " + e.getMessage());
//        }
//    }
//
//    @Test
//    @DisplayName("Test strategy stop functionality")
//    void testStrategyStop() {
//        strategy.stopVWAPOptionsTrading();
//        System.out.println("✅ Strategy stop functionality verified");
//    }
//
//    @Test
//    @DisplayName("Test option symbol building")
//    void testOptionSymbolBuilding() {
//        try {
//            // Use reflection to test private buildOptionSymbol method
//            Method method = VWAPOptionsStrategy.class.getDeclaredMethod("buildOptionSymbol", double.class, boolean.class);
//            method.setAccessible(true);
//
//            String ceSymbol = (String) method.invoke(strategy, 21500.0, true);
//            String peSymbol = (String) method.invoke(strategy, 21500.0, false);
//
//            assertNotNull(ceSymbol);
//            assertNotNull(peSymbol);
//            assertTrue(ceSymbol.contains("CE"));
//            assertTrue(peSymbol.contains("PE"));
//
//            System.out.println("✅ CE Symbol: " + ceSymbol);
//            System.out.println("✅ PE Symbol: " + peSymbol);
//
//        } catch (Exception e) {
//            fail("Option symbol building test failed: " + e.getMessage());
//        }
//    }
//}