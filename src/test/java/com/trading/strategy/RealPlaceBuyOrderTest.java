package com.trading.strategy;

import com.zerodhatech.models.Order;
import com.zerodhatech.models.OrderParams;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.*;

/**
 * REAL TEST for placeBuyOrder method with ACTUAL order placement
 * Using specific symbol: NIFTY25D0226250CE
 * WARNING: This will place REAL orders with REAL money
 */
@DisplayName("Real PlaceBuyOrder Test - ACTUAL ORDERS")
public class RealPlaceBuyOrderTest {

    private VWAPOptionsStrategy strategy;
    private List<Object> placedPositions;

    @BeforeEach
    void setUp() {
        strategy = new VWAPOptionsStrategy();
        placedPositions = new ArrayList<>();
        System.out.println("🚀 INITIALIZED FOR REAL ORDER PLACEMENT");
    }

    @Test
    @Timeout(120)
    @DisplayName("REAL ORDER: Place Buy Order for NIFTY25D0226250CE")
    void testRealBuyOrderSpecificCE() throws Exception {
        System.out.println("🚀 REAL ORDER: Place Buy Order for NIFTY25D0226250CE");
        System.out.println("===================================================");

        // Use the specific symbol you provided
        String ceSymbol = "NFO:NIFTY25D0226250CE";
        double entryPrice = getCurrentPrice(ceSymbol);
        double triggerCandleLow = entryPrice * 0.95; // 5% below entry

        System.out.println("🎯 Placing REAL ORDER for your specified symbol:");
        System.out.println("   Symbol: " + ceSymbol);
        System.out.println("   Current Price: " + entryPrice);
        System.out.println("   Stop Loss Reference: " + triggerCandleLow);

        // Create REAL CandleData object
        CandleData triggerCandle = createCandleData(ceSymbol, entryPrice, triggerCandleLow);

        // Place REAL order
        placeRealBuyOrder(ceSymbol, entryPrice, triggerCandle, "CE");
    }

    @Test
    @Timeout(120)
    @DisplayName("REAL ORDER: Place Buy Order for Nearby PE")
    void testRealBuyOrderNearbyPE() throws Exception {
        System.out.println("🚀 REAL ORDER: Place Buy Order for Nearby PE");
        System.out.println("============================================");

        // Use nearby PE option for the same strike
        String peSymbol = "NFO:NIFTY25D0226250PE";
        double entryPrice = getCurrentPrice(peSymbol);
        double triggerCandleLow = entryPrice * 0.95; // 5% below entry

        System.out.println("🎯 Placing REAL ORDER for corresponding PE:");
        System.out.println("   Symbol: " + peSymbol);
        System.out.println("   Current Price: " + entryPrice);
        System.out.println("   Stop Loss Reference: " + triggerCandleLow);

        // Create REAL CandleData object
        CandleData triggerCandle = createCandleData(peSymbol, entryPrice, triggerCandleLow);

        // Place REAL order
        placeRealBuyOrder(peSymbol, entryPrice, triggerCandle, "PE");
    }

    @Test
    @Timeout(180)
    @DisplayName("REAL ORDERS: Multiple Strikes Around 226250")
    void testMultipleStrikeOrders() throws Exception {
        System.out.println("🚀 REAL ORDERS: Multiple Strikes Around 226250");
        System.out.println("==============================================");

        // Test multiple strikes around your specified strike (226250)
        double baseStrike = 226250;
        double[] strikes = {baseStrike - 100, baseStrike, baseStrike + 100};
        boolean[] optionTypes = {true, false}; // CE and PE

        for (double strike : strikes) {
            for (boolean isCall : optionTypes) {
                String symbol = buildOptionSymbol(strike, isCall);
                double entryPrice = getCurrentPrice(symbol);

                if (entryPrice <= 0) {
                    System.out.println("⚠️  Skipping " + symbol + " - No price data");
                    continue;
                }

                double triggerLow = entryPrice * 0.95; // 5% below entry

                System.out.println("\n🎯 Placing REAL Order: " + symbol);
                System.out.println("   Strike: " + strike + " | Type: " + (isCall ? "CE" : "PE"));
                System.out.println("   Current Price: " + entryPrice);
                System.out.println("   Stop Ref: " + triggerLow);

                CandleData triggerCandle = createCandleData(symbol, entryPrice, triggerLow);

                try {
                    placeRealBuyOrder(symbol, entryPrice, triggerCandle, isCall ? "CE" : "PE");
                    Thread.sleep(2000); // Delay between orders
                } catch (Exception e) {
                    System.err.println("❌ Failed for " + symbol + ": " + e.getMessage());
                    if (e.getCause() instanceof KiteException) {
                        KiteException ke = (KiteException) e.getCause();
                        System.err.println("   Kite Error: " + ke.message + " (Code: " + ke.code + ")");
                    }
                }
            }
        }
    }

    @Test
    @Timeout(120)
    @DisplayName("REAL ORDER: Direct Market Order for NIFTY25D0226250CE")
    void testDirectMarketOrder() throws Exception {
        System.out.println("🎯 REAL ORDER: Direct Market Order for NIFTY25D0226250CE");
        System.out.println("=======================================================");

        String symbol = "NFO:NIFTY25D0226250CE";
        String tradingSymbol = symbol.replace("NFO:", "");

        System.out.println("📦 Placing DIRECT MARKET ORDER for: " + tradingSymbol);

        // Get current price
        double currentPrice = getCurrentPrice(symbol);
        System.out.println("💰 Current Market Price: " + currentPrice);

        // Get KiteConnect instance
        Field kiteConnectField = VWAPOptionsStrategy.class.getDeclaredField("kiteConnect");
        kiteConnectField.setAccessible(true);
        Object kiteConnect = kiteConnectField.get(strategy);

        // Prepare order parameters - MARKET order
        OrderParams orderParams = createOrderParams(tradingSymbol, 25); // Small quantity

        System.out.println("📋 Order Details:");
        printOrderParams(orderParams);

        // Place REAL order
        Method placeOrderMethod = kiteConnect.getClass().getMethod("placeOrder", OrderParams.class, String.class);
        Order order = (Order) placeOrderMethod.invoke(kiteConnect, orderParams, "regular");

        if (order != null && order.orderId != null) {
            System.out.println("✅ REAL MARKET ORDER PLACED SUCCESSFULLY!");
            printOrderDetails(order);
        } else {
            System.out.println("❌ MARKET ORDER FAILED - No order ID returned");
        }
    }

    @Test
    @Timeout(120)
    @DisplayName("REAL ORDER: Test VWAP Strategy Entry")
    void testVWAPStrategyEntry() throws Exception {
        System.out.println("🎯 REAL ORDER: Test VWAP Strategy Entry");
        System.out.println("======================================");

        String symbol = "NFO:NIFTY25D0226250CE";
        double currentPrice = getCurrentPrice(symbol);

        System.out.println("💰 Current Market Price for " + symbol + ": " + currentPrice);

        // Test different VWAP-based entry scenarios
        testVWAPEntryScenario(symbol, currentPrice, "At Current Market Price");
        testVWAPEntryScenario(symbol, currentPrice * 0.99, "1% Below Market (Limit)");
        testVWAPEntryScenario(symbol, currentPrice * 1.01, "1% Above Market (Breakout)");
    }

    @Test
    @Timeout(120)
    @DisplayName("REAL ORDER: Batch Orders - CE and PE")
    void testBatchCEandPE() throws Exception {
        System.out.println("🚀 REAL ORDER: Batch Orders - CE and PE");
        System.out.println("======================================");

        String[] symbols = {
                "NFO:NIFTY25D0226250CE",
                "NFO:NIFTY25D0226250PE"
        };

        for (String symbol : symbols) {
            double currentPrice = getCurrentPrice(symbol);

            if (currentPrice <= 0) {
                System.out.println("⚠️  Skipping " + symbol + " - No price data");
                continue;
            }

            double entryPrice = currentPrice;
            double triggerLow = entryPrice * 0.95;
            String optionType = symbol.contains("CE") ? "CE" : "PE";

            System.out.println("\n🎯 Placing " + optionType + " Order: " + symbol);
            System.out.println("   Current Price: " + currentPrice);
            System.out.println("   Stop Loss: " + triggerLow);

            CandleData triggerCandle = createCandleData(symbol, entryPrice, triggerLow);

            try {
                placeRealBuyOrder(symbol, entryPrice, triggerCandle, optionType);
                Thread.sleep(3000); // Longer delay between batch orders
            } catch (Exception e) {
                System.err.println("❌ Failed for " + symbol + ": " + e.getMessage());
            }
        }
    }

    private void placeRealBuyOrder(String symbol, double entryPrice, CandleData triggerCandle, String optionType) throws Exception {
        System.out.println("🔧 Placing REAL " + optionType + " Buy Order...");

        // Get the private placeBuyOrder method with correct signature
        Method placeBuyOrderMethod = VWAPOptionsStrategy.class.getDeclaredMethod(
                "placeBuyOrder", String.class, double.class, CandleData.class);
        placeBuyOrderMethod.setAccessible(true);

        // Get current market price for verification
        double currentPrice = getCurrentPrice(symbol);
        System.out.println("💰 Current Market Price: " + currentPrice);
        System.out.println("🎯 Target Entry Price: " + entryPrice);

        // Calculate expected position parameters
        int expectedQuantity = getLotSize();
        double expectedStopLoss = triggerCandle.getLow();
        double expectedTarget = calculateTarget(entryPrice, expectedStopLoss);

        System.out.println("📊 Expected Position:");
        System.out.println("   Quantity: " + expectedQuantity);
        System.out.println("   Stop Loss: " + expectedStopLoss);
        System.out.println("   Target: " + expectedTarget);
        System.out.println("   Risk: " + (entryPrice - expectedStopLoss));
        System.out.println("   Reward: " + (expectedTarget - entryPrice));

        // Place REAL order
        Object position = placeBuyOrderMethod.invoke(strategy, symbol, entryPrice, triggerCandle);

        if (position != null) {
            System.out.println("✅ REAL " + optionType + " BUY ORDER PLACED SUCCESSFULLY!");
            placedPositions.add(position);
            printPositionDetails(position);

            // Store the position in strategy's currentPositions
            Field currentPositionsField = VWAPOptionsStrategy.class.getDeclaredField("currentPositions");
            currentPositionsField.setAccessible(true);
            Map<String, Object> currentPositions = (Map<String, Object>) currentPositionsField.get(strategy);
            currentPositions.put(symbol, position);

        } else {
            System.out.println("❌ " + optionType + " BUY ORDER FAILED - Position is null");
        }
    }

    private void testVWAPEntryScenario(String symbol, double entryPrice, String scenario) throws Exception {
        System.out.println("\n🔧 Testing VWAP Entry Scenario: " + scenario);

        double triggerLow = entryPrice * 0.95;
        CandleData triggerCandle = createCandleData(symbol, entryPrice, triggerLow);

        System.out.println("   Entry Price: " + entryPrice);
        System.out.println("   Stop Loss: " + triggerLow);
        System.out.println("   VWAP Reference: " + entryPrice); // Using entry as VWAP for test

        try {
            placeRealBuyOrder(symbol, entryPrice, triggerCandle, "CE");
            Thread.sleep(2000);
        } catch (Exception e) {
            System.err.println("❌ Failed for scenario '" + scenario + "': " + e.getMessage());
        }
    }

    // Helper methods
    private CandleData createCandleData(String symbol, double price, double low) {
        return new CandleData(
                symbol,
                price,           // open
                price + 2.0,     // high (slightly above)
                low,             // low (stop loss reference)
                price,           // close
                1500,            // volume
                new Date(),
                price            // vwap (using price as VWAP for testing)
        );
    }

    private String buildOptionSymbol(double strike, boolean isCall) {
        // Build symbol in format: NFO:NIFTY25D02{strike}{CE/PE}
        return String.format("NFO:NIFTY25D02%d%s", (int)strike, isCall ? "CE" : "PE");
    }

    private double getCurrentPrice(String symbol) {
        try {
            Field kiteConnectField = VWAPOptionsStrategy.class.getDeclaredField("kiteConnect");
            kiteConnectField.setAccessible(true);
            Object kiteConnect = kiteConnectField.get(strategy);

            Method getQuoteMethod = kiteConnect.getClass().getMethod("getQuote", String[].class);
            Map<?, ?> quotes = (Map<?, ?>) getQuoteMethod.invoke(kiteConnect, (Object) new String[]{symbol});

            if (quotes.containsKey(symbol)) {
                Object quote = quotes.get(symbol);
                Field lastPriceField = quote.getClass().getField("lastPrice");
                double price = (Double) lastPriceField.get(quote);
                System.out.println("   📊 Live price for " + symbol + ": " + price);
                return price;
            }
        } catch (Exception e) {
            System.out.println("⚠️  Could not get current price for " + symbol + ": " + e.getMessage());
        }
        return 0.0;
    }

    private int getLotSize() {
        try {
            // Get lot size from AppConfig
            Class<?> appConfigClass = Class.forName("com.trading.config.AppConfig");
            Method getLotSizeMethod = appConfigClass.getMethod("getVWAPOptionsLotSize");
            return (Integer) getLotSizeMethod.invoke(null);
        } catch (Exception e) {
            return 50; // Default lot size
        }
    }

    private double calculateTarget(double entryPrice, double stopLoss) {
        try {
            // Get multiplier from AppConfig
            Class<?> appConfigClass = Class.forName("com.trading.config.AppConfig");
            Method getMultiplierMethod = appConfigClass.getMethod("getVWAPOptionsStoplossMultiplier");
            double multiplier = (Double) getMultiplierMethod.invoke(null);

            return entryPrice + (multiplier * (entryPrice - stopLoss));
        } catch (Exception e) {
            return entryPrice + (2.0 * (entryPrice - stopLoss)); // Default 2x multiplier
        }
    }

    private OrderParams createOrderParams(String tradingSymbol, int quantity) {
        OrderParams orderParams = new OrderParams();
        orderParams.exchange = "NFO";
        orderParams.tradingsymbol = tradingSymbol;
        orderParams.transactionType = "BUY";
        orderParams.quantity = quantity;
        orderParams.orderType = "MARKET";
        orderParams.product = "MIS";
        orderParams.validity = "DAY";
        return orderParams;
    }

    private void printOrderParams(OrderParams orderParams) {
        System.out.println("   Exchange: " + orderParams.exchange);
        System.out.println("   Trading Symbol: " + orderParams.tradingsymbol);
        System.out.println("   Transaction: " + orderParams.transactionType);
        System.out.println("   Quantity: " + orderParams.quantity);
        System.out.println("   Order Type: " + orderParams.orderType);
        System.out.println("   Product: " + orderParams.product);
        System.out.println("   Validity: " + orderParams.validity);
    }

    private void printOrderDetails(Order order) {
        System.out.println("📋 ORDER DETAILS:");
        System.out.println("   Order ID: " + order.orderId);
        System.out.println("   Trading Symbol: " + order.tradingSymbol);
        System.out.println("   Transaction Type: " + order.transactionType);
        System.out.println("   Quantity: " + order.quantity);
        System.out.println("   Product: " + order.product);
        System.out.println("   Order Type: " + order.orderType);
        System.out.println("   Status: " + order.status);
        if (Integer.parseInt(order.averagePrice) > 0) {
            System.out.println("   Average Price: " + order.averagePrice);
        }
        if (order.orderTimestamp != null) {
            System.out.println("   Timestamp: " + order.orderTimestamp);
        }
    }

    private void printPositionDetails(Object position) throws Exception {
        System.out.println("📊 POSITION DETAILS:");

        Class<?> positionClass = position.getClass();

        try {
            Method getTradingSymbol = positionClass.getMethod("getTradingSymbol");
            Method getEntryPrice = positionClass.getMethod("getEntryPrice");
            Method getQuantity = positionClass.getMethod("getQuantity");
            Method getStopLoss = positionClass.getMethod("getStopLoss");
            Method getTarget = positionClass.getMethod("getTarget");
            Method getOrderId = positionClass.getMethod("getOrderId");
            Method getSignalType = positionClass.getMethod("getSignalType");

            System.out.println("   Symbol: " + getTradingSymbol.invoke(position));
            System.out.println("   Entry Price: " + getEntryPrice.invoke(position));
            System.out.println("   Quantity: " + getQuantity.invoke(position));
            System.out.println("   Stop Loss: " + getStopLoss.invoke(position));
            System.out.println("   Target: " + getTarget.invoke(position));
            System.out.println("   Order ID: " + getOrderId.invoke(position));
            System.out.println("   Signal Type: " + getSignalType.invoke(position));

        } catch (Exception e) {
            System.out.println("   Position: " + position.toString());
        }
    }

    @Test
    @DisplayName("FINAL SUMMARY: All Real Orders Placed")
    void finalSummary() {
        System.out.println("\n📊 FINAL REAL ORDERS SUMMARY");
        System.out.println("===========================");
        System.out.println("Total REAL Positions Created: " + placedPositions.size());
        System.out.println("Execution Time: " + new Date());
        System.out.println("Primary Symbol: NIFTY25D0226250CE");

        if (!placedPositions.isEmpty()) {
            System.out.println("\n✅ SUCCESSFULLY PLACED ORDERS:");
            for (Object position : placedPositions) {
                try {
                    Class<?> positionClass = position.getClass();
                    Method getTradingSymbol = positionClass.getMethod("getTradingSymbol");
                    Method getOrderId = positionClass.getMethod("getOrderId");
                    Method getEntryPrice = positionClass.getMethod("getEntryPrice");
                    Method getQuantity = positionClass.getMethod("getQuantity");

                    String symbol = (String) getTradingSymbol.invoke(position);
                    String orderId = (String) getOrderId.invoke(position);
                    double entryPrice = (Double) getEntryPrice.invoke(position);
                    int quantity = (Integer) getQuantity.invoke(position);

                    System.out.println("   📈 " + symbol +
                            " | Order: " + orderId +
                            " | Price: " + entryPrice +
                            " | Qty: " + quantity);
                } catch (Exception e) {
                    System.out.println("   📈 Position: " + position.toString());
                }
            }
        } else {
            System.out.println("❌ No positions were created - Check if market is open and symbols are valid");
        }
    }
}