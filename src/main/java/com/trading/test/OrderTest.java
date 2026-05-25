// OrderTest.java - Complete working version with LIMIT orders
package com.trading.test;

import com.trading.config.AppConfig;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.kiteconnect.utils.Constants;
import com.zerodhatech.models.Order;
import com.zerodhatech.models.OrderParams;
import com.zerodhatech.models.Quote;

import java.util.Map;

public class OrderTest {

    private static KiteConnect kiteConnect;

    public static void main(String[] args) {
        try {
            System.out.println("🔧 ORDER PLACEMENT TEST");
            System.out.println("=".repeat(60));

            initializeKiteConnect();

            String testSymbol = "NFO:NIFTY2663023400PE";
            double currentPrice = getCurrentPrice(testSymbol);
            System.out.println("📊 Current price for " + testSymbol + ": " + currentPrice);

            if (currentPrice > 0) {
                // Test LIMIT order only (MARKET orders don't work)
                testLimitOrder(testSymbol, currentPrice);
            }

            testSymbolFormats();

        } catch (Exception e) {
            System.err.println("❌ Test failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void initializeKiteConnect() {
        try {
            kiteConnect = new KiteConnect(AppConfig.getApiKey());
            kiteConnect.setAccessToken(AppConfig.getAccessToken());

            try {
                java.lang.reflect.Method setRootMethod = kiteConnect.getClass().getMethod("setRoot", String.class);
                setRootMethod.invoke(kiteConnect, AppConfig.getBaseUrl());
            } catch (NoSuchMethodException e) {
                System.out.println("setRoot method not available");
            }

            System.out.println("✅ KiteConnect initialized");

        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize KiteConnect: " + e.getMessage(), e);
        }
    }

    private static double getCurrentPrice(String symbol) {
        try {
            String[] instruments = {symbol};
            Map<String, Quote> quotes = kiteConnect.getQuote(instruments);
            Quote quote = quotes.get(symbol);

            if (quote != null) {
                System.out.println("\n📊 Quote Details:");
                System.out.println("   Last Price: " + quote.lastPrice);
                System.out.println("   Volume: " + quote.volumeTradedToday);
                System.out.println("   OHLC - O:" + quote.ohlc.open +
                        " H:" + quote.ohlc.high +
                        " L:" + quote.ohlc.low +
                        " C:" + quote.ohlc.close);
                return quote.lastPrice;
            }
        } catch (Exception | KiteException e) {
            System.err.println("❌ Error getting price: " + e.getMessage());
        }
        return 0;
    }

    private static void testLimitOrder(String symbol, double marketPrice) {
        System.out.println("\n" + "🔨".repeat(20));
        System.out.println("TESTING LIMIT ORDER");
        System.out.println("🔨".repeat(20));

        int quantity = 65; // Minimum lot size
        String tradingSymbol = symbol.replace("NFO:", "");

        // Use current market price as limit price
        double limitPrice = marketPrice;

        System.out.println("📋 Order Details:");
        System.out.println("   Trading Symbol: " + tradingSymbol);
        System.out.println("   Exchange: NFO");
        System.out.println("   Quantity: " + quantity);
        System.out.println("   Limit Price: " + String.format("%.2f", limitPrice));
        System.out.println("   Order Type: LIMIT");

        try {
            OrderParams orderParams = new OrderParams();
            orderParams.exchange = "NFO";
            orderParams.tradingsymbol = tradingSymbol;
            orderParams.transactionType = Constants.TRANSACTION_TYPE_BUY;
            orderParams.quantity = quantity;
            orderParams.orderType = Constants.ORDER_TYPE_LIMIT;
            orderParams.price = limitPrice;
            orderParams.product = Constants.PRODUCT_MIS;
            orderParams.validity = Constants.VALIDITY_DAY;

            System.out.println("\n🚀 Placing LIMIT order...");
            Order order = kiteConnect.placeOrder(orderParams, Constants.VARIETY_REGULAR);

            if (order != null && order.orderId != null) {
                System.out.println("\n✅ LIMIT ORDER PLACED SUCCESSFULLY!");
                System.out.println("   Order ID: " + order.orderId);
                System.out.println("   Status: " + order.status);
            } else {
                System.err.println("\n❌ ORDER FAILED - Order object is null");
            }

        } catch (KiteException e) {
            System.err.println("\n❌ KITE EXCEPTION:");
            System.err.println("   Message: " + e.getMessage());
            System.err.println("   Code: " + e.code);
            if (e.code == 400) {
                System.err.println("\n💡 Troubleshooting:");
                System.err.println("   1. Check if you have sufficient funds");
                System.err.println("   2. Verify the instrument is tradable");
                System.err.println("   3. Check if market is open");
                System.err.println("   4. Verify quantity is multiple of lot size (50)");
            }
        } catch (Exception e) {
            System.err.println("\n❌ GENERAL EXCEPTION:");
            System.err.println("   Message: " + e.getMessage());
        }
    }

    private static void testSymbolFormats() {
        System.out.println("\n" + "🔍".repeat(20));
        System.out.println("SYMBOL FORMAT VALIDATION");
        System.out.println("🔍".repeat(20));

        String[] testSymbols = {
                "NFO:NIFTY2663023400PE",
                "NIFTY2663023400PE",
                "NFO:NIFTY2663023400PE",
                "NSE:NIFTY 50",
        };

        for (String symbol : testSymbols) {
            try {
                System.out.print("\nTesting: " + symbol + " -> ");
                String[] instruments = {symbol};
                Map<String, Quote> quotes = kiteConnect.getQuote(instruments);
                Quote quote = quotes.get(symbol);
                if (quote != null && quote.lastPrice > 0) {
                    System.out.println("✅ VALID (Price: " + quote.lastPrice + ")");
                } else {
                    System.out.println("❌ INVALID");
                }
            } catch (Exception | KiteException e) {
                System.out.println("❌ ERROR: " + e.getMessage());
            }
        }
    }
}