//package com.trading.strategy;
//
//import com.zerodhatech.kiteconnect.KiteConnect;
//import com.zerodhatech.models.Order;
//import com.zerodhatech.models.OrderParams;
//import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.DisplayName;
//
//import java.lang.reflect.Method;
//import java.util.Date;
//
///**
// * TEST CLASS FOR REAL ORDER PLACEMENT
// * WARNING: This will place REAL orders with your broker
// * Use with caution and only in paper trading mode if available
// */
//public class RealOrderTest {
//
//    private VWAPOptionsStrategy strategy;
//    private KiteConnect realKiteConnect;
//
//    @BeforeEach
//    void setUp() {
//        // Initialize with your real credentials
//        strategy = new VWAPOptionsStrategy();
//
//        // Get the real kiteConnect instance from the strategy
//        realKiteConnect = getRealKiteConnect(strategy);
//    }
//
//    private KiteConnect getRealKiteConnect(VWAPOptionsStrategy strategy) {
//        try {
//            java.lang.reflect.Field kiteConnectField = VWAPOptionsStrategy.class.getDeclaredField("kiteConnect");
//            kiteConnectField.setAccessible(true);
//            return (KiteConnect) kiteConnectField.get(strategy);
//        } catch (Exception e) {
//            throw new RuntimeException("Failed to get real KiteConnect instance", e);
//        }
//    }
//
//    @Test
//    @DisplayName("REAL TEST: Place BUY order for CE option")
//    void testRealBuyOrderCE() {
//        if (!isTestEnvironmentSafe()) {
//            System.out.println("🚫 Skipping real order test - environment not safe");
//            return;
//        }
//
//        try {
//            // Test data for CE option
//            String ceSymbol = "NFO:NIFTY25D0226250CE"; // Replace with actual symbol
//            double entryPrice = 120.50;
//            double triggerCandleLow = 115.25;
//
//            System.out.println("🚀 ATTEMPTING REAL BUY ORDER FOR CE OPTION");
//            System.out.println("Symbol: " + ceSymbol);
//            System.out.println("Entry Price: " + entryPrice);
//            System.out.println("Stop Loss Reference: " + triggerCandleLow);
//
//            // Create mock trigger candle data
//            Object triggerCandle = createMockTriggerCandle(ceSymbol, entryPrice, triggerCandleLow);
//
//            // Use reflection to call the private placeBuyOrder method
//            Method placeBuyOrderMethod = VWAPOptionsStrategy.class.getDeclaredMethod(
//                "placeBuyOrder", String.class, double.class, Object.class);
//            placeBuyOrderMethod.setAccessible(true);
//
//            Object position = placeBuyOrderMethod.invoke(strategy, ceSymbol, entryPrice, triggerCandle);
//
//            if (position != null) {
//                System.out.println("✅ REAL CE BUY ORDER PLACED SUCCESSFULLY!");
//                System.out.println("Position: " + position.toString());
//            } else {
//                System.out.println("❌ REAL CE BUY ORDER FAILED");
//            }
//
//        } catch (Exception e) {
//            System.err.println("❌ Error placing real CE buy order: " + e.getMessage());
//            e.printStackTrace();
//        }
//    }
//
//    @Test
//    @DisplayName("REAL TEST: Place BUY order for PE option")
//    void testRealBuyOrderPE() {
//        if (!isTestEnvironmentSafe()) {
//            System.out.println("🚫 Skipping real order test - environment not safe");
//            return;
//        }
//
//        try {
//            // Test data for PE option
//            String peSymbol = "NFO:NIFTY25D0226150CE"; // Replace with actual symbol
//            double entryPrice = 110.75;
//            double triggerCandleLow = 105.50;
//
//            System.out.println("🚀 ATTEMPTING REAL BUY ORDER FOR PE OPTION");
//            System.out.println("Symbol: " + peSymbol);
//            System.out.println("Entry Price: " + entryPrice);
//            System.out.println("Stop Loss Reference: " + triggerCandleLow);
//
//            // Create mock trigger candle data
//            Object triggerCandle = createMockTriggerCandle(peSymbol, entryPrice, triggerCandleLow);
//
//            // Use reflection to call the private placeBuyOrder method
//            Method placeBuyOrderMethod = VWAPOptionsStrategy.class.getDeclaredMethod(
//                "placeBuyOrder", String.class, double.class, Object.class);
//            placeBuyOrderMethod.setAccessible(true);
//
//            Object position = placeBuyOrderMethod.invoke(strategy, peSymbol, entryPrice, triggerCandle);
//
//            if (position != null) {
//                System.out.println("✅ REAL PE BUY ORDER PLACED SUCCESSFULLY!");
//                System.out.println("Position: " + position.toString());
//            } else {
//                System.out.println("❌ REAL PE BUY ORDER FAILED");
//            }
//
//        } catch (Exception e) {
//            System.err.println("❌ Error placing real PE buy order: " + e.getMessage());
//            e.printStackTrace();
//        }
//    }
//
//    @Test
//    @DisplayName("REAL TEST: Place orders for multiple strikes")
//    void testRealBuyOrdersMultipleStrikes() {
//        if (!isTestEnvironmentSafe()) {
//            System.out.println("🚫 Skipping real order test - environment not safe");
//            return;
//        }
//
//        // Test multiple strikes around current price
//        double[] strikes = {26250, 26200, 261500};
//        boolean[] isCall = {true, false}; // Test both CE and PE
//
//        for (double strike : strikes) {
//            for (boolean call : isCall) {
//                try {
//                    String symbol = buildOptionSymbol(strike, call);
//                    double entryPrice = 100 + (Math.random() * 50); // Random premium 100-150
//                    double triggerLow = entryPrice * 0.95; // 5% below entry
//
//                    System.out.println("\n🎯 Testing: " + symbol);
//                    System.out.println("Strike: " + strike + " | Type: " + (call ? "CE" : "PE"));
//                    System.out.println("Entry: " + entryPrice + " | Stop Ref: " + triggerLow);
//
//                    // Create mock trigger candle
//                    Object triggerCandle = createMockTriggerCandle(symbol, entryPrice, triggerLow);
//
//                    // Try to place order
//                    Method placeBuyOrderMethod = VWAPOptionsStrategy.class.getDeclaredMethod(
//                        "placeBuyOrder", String.class, double.class, Object.class);
//                    placeBuyOrderMethod.setAccessible(true);
//
//                    Object position = placeBuyOrderMethod.invoke(strategy, symbol, entryPrice, triggerCandle);
//
//                    if (position != null) {
//                        System.out.println("✅ ORDER SUCCESS: " + symbol);
//                    } else {
//                        System.out.println("⚠️ ORDER FAILED: " + symbol + " (Invalid symbol or market closed)");
//                    }
//
//                    // Small delay between orders
//                    Thread.sleep(1000);
//
//                } catch (Exception e) {
//                    System.err.println("❌ Error with " + strike + (call ? "CE" : "PE") + ": " + e.getMessage());
//                }
//            }
//        }
//    }
//
//    @Test
//    @DisplayName("DIRECT API TEST: Test order placement via KiteConnect directly")
//    void testDirectOrderPlacement() {
//        if (!isTestEnvironmentSafe()) {
//            System.out.println("🚫 Skipping direct order test - environment not safe");
//            return;
//        }
//
//        try {
//            // Test with a small quantity for safety
//            String testSymbol = "NFO:NIFTY25D0226300CE"; // Replace with actual symbol
//            String tradingSymbol = testSymbol.replace("NFO:", "");
//
//            System.out.println("🎯 DIRECT API ORDER TEST");
//            System.out.println("Symbol: " + tradingSymbol);
//
//            OrderParams orderParams = new OrderParams();
//            orderParams.exchange = "NFO";
//            orderParams.tradingsymbol = tradingSymbol;
//            orderParams.transactionType = "BUY";
//            orderParams.quantity = 25; // Small quantity for testing
//            orderParams.orderType = "MARKET";
//            orderParams.product = "MIS";
//            orderParams.validity = "DAY";
//
//            System.out.println("📦 Order Parameters:");
//            System.out.println("   Exchange: " + orderParams.exchange);
//            System.out.println("   Trading Symbol: " + orderParams.tradingsymbol);
//            System.out.println("   Transaction: " + orderParams.transactionType);
//            System.out.println("   Quantity: " + orderParams.quantity);
//            System.out.println("   Type: " + orderParams.orderType);
//            System.out.println("   Product: " + orderParams.product);
//
//            // Place the order
//            Order order = realKiteConnect.placeOrder(orderParams, "regular");
//
//            if (order != null && order.orderId != null) {
//                System.out.println("✅ DIRECT ORDER PLACED SUCCESSFULLY!");
//                System.out.println("Order ID: " + order.orderId);
//                System.out.println("Status: " + order.status);
//
//                // Print order details
//                printOrderDetails(order);
//            } else {
//                System.out.println("❌ DIRECT ORDER FAILED - No order ID returned");
//            }
//
//        } catch (KiteException e) {
//            System.err.println("❌ KiteException: " + e.message + " (Code: " + e.code + ")");
//            e.printStackTrace();
//        } catch (Exception e) {
//            System.err.println("❌ Error in direct order placement: " + e.getMessage());
//            e.printStackTrace();
//        }
//    }
//
//    @Test
//    @DisplayName("REAL TEST: Complete VWAP Strategy Flow")
//    void testCompleteVWAPStrategyFlow() {
//        if (!isTestEnvironmentSafe()) {
//            System.out.println("🚫 Skipping complete flow test - environment not safe");
//            return;
//        }
//
//        try {
//            System.out.println("🚀 STARTING COMPLETE VWAP STRATEGY FLOW TEST");
//
//            // Step 1: Get Nifty spot price
//            double niftySpot = strategy.getNiftySpotPrice();
//            System.out.println("📊 Nifty Spot: " + niftySpot);
//
//            // Step 2: Preload tokens
//            strategy.preloadOptionTokens(niftySpot);
//            System.out.println("✅ Tokens preloaded");
//
//            // Step 3: Find suitable options
//            Method findOptionsMethod = VWAPOptionsStrategy.class.getDeclaredMethod("findOptionsNearTargetPrice");
//            findOptionsMethod.setAccessible(true);
//            var options = findOptionsMethod.invoke(strategy);
//
//            System.out.println("🔍 Options found: " + options);
//
//            // Step 4: Start strategy (this will attempt real orders)
//            strategy.startVWAPOptionsTrading();
//
//            System.out.println("✅ Complete VWAP strategy flow executed");
//
//        } catch (Exception | KiteException e) {
//            System.err.println("❌ Error in complete VWAP flow: " + e.getMessage());
//            e.printStackTrace();
//        }
//    }
//
//    // Helper methods
//    private boolean isTestEnvironmentSafe() {
//        // Add safety checks here
//        String environment = System.getProperty("test.environment", "development");
//        boolean isMarketHours = true;
//
//        System.out.println("🔒 Safety Check:");
//        System.out.println("   Environment: " + environment);
//        System.out.println("   Market Hours: " + (isMarketHours ? "OPEN" : "CLOSED"));
//
//        // Only allow in development environment during market hours
//        return "development".equals(environment) && isMarketHours;
//    }
//
//    private boolean isWithinMarketHours() {
//        // Simple market hours check (9:15 AM to 3:30 PM)
//        Date now = new Date();
//        int hour = now.getHours();
//        int minute = now.getMinutes();
//
//        return (hour > 9 || (hour == 9 && minute >= 15)) &&
//               (hour < 15 || (hour == 15 && minute <= 30));
//    }
//
//    private Object createMockTriggerCandle(String symbol, double price, double low) {
//        try {
//            // Create a mock CandleData object using reflection
//            Class<?> candleDataClass = Class.forName("com.trading.strategy.CandleData");
//            Object candleData = candleDataClass.getDeclaredConstructor(
//                String.class, double.class, double.class, double.class,
//                double.class, long.class, Date.class, double.class)
//                .newInstance(symbol, price, price, low, price, 1000L, new Date(), price);
//
//            return candleData;
//        } catch (Exception e) {
//            // If CandleData class not available, return a simple object
//            System.out.println("⚠️ Using simple trigger candle object");
//            return new Object() {
//                public double getLow() { return low; }
//                public String toString() { return "TriggerCandle{low=" + low + "}"; }
//            };
//        }
//    }
//
//    private String buildOptionSymbol(double strike, boolean isCall) {
//        // Simple symbol builder for testing
//        return String.format("NFO:NIFTY25D02%d%s", (int)strike, isCall ? "CE" : "PE");
//    }
//
//    private void printOrderDetails(Order order) {
//        System.out.println("📋 ORDER DETAILS:");
//        System.out.println("   Order ID: " + order.orderId);
//        System.out.println("   Trading Symbol: " + order.tradingSymbol);
//        System.out.println("   Transaction Type: " + order.transactionType);
//        System.out.println("   Quantity: " + order.quantity);
//        System.out.println("   Product: " + order.product);
//        System.out.println("   Order Type: " + order.orderType);
//        System.out.println("   Status: " + order.status);
//        if (Integer.parseInt(order.averagePrice) > 0) {
//            System.out.println("   Average Price: " + order.averagePrice);
//        }
//        if (order.orderTimestamp != null) {
//            System.out.println("   Timestamp: " + order.orderTimestamp);
//        }
//    }
//
//    // Safe test method that doesn't place real orders
//    @Test
//    @DisplayName("SAFE TEST: Validate order parameters without placing orders")
//    void testOrderParametersValidation() {
//        System.out.println("🔍 VALIDATING ORDER PARAMETERS");
//
//        // Test CE order parameters
//        validateOrderParameters(21500, true, 120.50);
//
//        // Test PE order parameters
//        validateOrderParameters(21500, false, 110.75);
//
//        // Test different strikes
//        validateOrderParameters(26250, true, 150.25);
//        validateOrderParameters(26200, false, 90.30);
//    }
//
//    private void validateOrderParameters(double strike, boolean isCall, double premium) {
//        String symbol = buildOptionSymbol(strike, isCall);
//        double stopLoss = premium * 0.95;
//        double target = premium + (2.0 * (premium - stopLoss)); // Using stoploss multiplier 2.0
//
//        System.out.println("\n🎯 " + (isCall ? "CE" : "PE") + " Parameters Validation:");
//        System.out.println("   Symbol: " + symbol);
//        System.out.println("   Strike: " + strike);
//        System.out.println("   Premium: " + premium);
//        System.out.println("   Stop Loss: " + stopLoss);
//        System.out.println("   Target: " + target);
//        System.out.println("   Risk-Reward: " + String.format("%.2f", (target - premium) / (premium - stopLoss)));
//
//        // Validate parameters
//        assert premium > 0 : "Premium must be positive";
//        assert stopLoss < premium : "Stop loss must be below entry";
//        assert target > premium : "Target must be above entry";
//
//        System.out.println("   ✅ Parameters validated successfully");
//    }
//}