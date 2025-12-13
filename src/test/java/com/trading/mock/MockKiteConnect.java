package com.trading.mock;

import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Instrument;
import com.zerodhatech.models.Quote;
import com.zerodhatech.models.Order;
import com.zerodhatech.models.OrderParams;
import com.zerodhatech.models.OHLC;
import com.zerodhatech.models.MarketDepth;

import java.util.*;

public class MockKiteConnect extends KiteConnect {
    private final Map<String, Quote> mockQuoteData = new HashMap<>();
    private final List<Instrument> mockInstruments = new ArrayList<>();
    private final List<Order> placedOrders = new ArrayList<>();

    public MockKiteConnect(String apiKey) {
        super(apiKey);
        initializeMockData();
    }

    private void initializeMockData() {
        // Mock Nifty 50 quote
        Quote niftyQuote = new Quote();
        niftyQuote.lastPrice = 21500.0;
        niftyQuote.averagePrice = 21480.0;
        niftyQuote.volumeTradedToday = 1000000;
        niftyQuote.lastTradedQuantity = 100;
        niftyQuote.lastTradedTime = new Date();
        niftyQuote.timestamp = new Date();

        // Create OHLC
        niftyQuote.ohlc = new OHLC();
        niftyQuote.ohlc.open = 26250;
        niftyQuote.ohlc.high = 26200;
        niftyQuote.ohlc.low = 26150;
        niftyQuote.ohlc.close = 26180;

        niftyQuote.change = 50.0;
        niftyQuote.buyQuantity = 50000;
        niftyQuote.sellQuantity = 45000;
        niftyQuote.instrumentToken = 256265L;

        mockQuoteData.put("NSE:NIFTY 50", niftyQuote);

        // Mock option instruments
        createMockOptionInstruments();
        createMockOptionQuotes();
    }

    private void createMockOptionInstruments() {
        // Create mock CE options
        for (int i = -3; i <= 3; i++) {
            double strike = 21500 + (i * 50);

            // CE Option
            Instrument ceInstrument = new Instrument();
            ceInstrument.exchange = "NFO";
            ceInstrument.tradingsymbol = "NIFTY25JAN" + (int)strike + "CE";
            ceInstrument.instrument_token = 1000000L + (long)(i * 2);
            ceInstrument.strike = String.valueOf(strike);
            ceInstrument.instrument_type = "CE";
            mockInstruments.add(ceInstrument);

            // PE Option
            Instrument peInstrument = new Instrument();
            peInstrument.exchange = "NFO";
            peInstrument.tradingsymbol = "NIFTY25JAN" + (int)strike + "PE";
            peInstrument.instrument_token = 1000001L + (long)(i * 2);
            peInstrument.strike = String.valueOf(strike);
            peInstrument.instrument_type = "PE";
            mockInstruments.add(peInstrument);
        }
    }

    private void createMockOptionQuotes() {
        // Create mock quotes for options
        for (Instrument instrument : mockInstruments) {
            Quote quote = new Quote();
            double basePrice = 120.0 + (Math.random() * 50); // Random premium between 120-170
            quote.lastPrice = basePrice;
            quote.averagePrice = basePrice;
            quote.volumeTradedToday = 1000;
            quote.lastTradedQuantity = 50;
            quote.lastTradedTime = new Date();
            quote.timestamp = new Date();

            // Create OHLC
            quote.ohlc = new OHLC();
            quote.ohlc.open = basePrice - 10;
            quote.ohlc.high = basePrice + 15;
            quote.ohlc.low = basePrice - 20;
            quote.ohlc.close = basePrice;

            quote.change = 5.0;
            quote.buyQuantity = 250.0;
            quote.sellQuantity = 200.0;
            quote.instrumentToken = instrument.instrument_token;

            String symbol = instrument.exchange + ":" + instrument.tradingsymbol;
            mockQuoteData.put(symbol, quote);
        }
    }

    @Override
    public Map<String, Quote> getQuote(String[] instruments) throws KiteException {
        Map<String, Quote> result = new HashMap<>();

        for (String instrument : instruments) {
            if (mockQuoteData.containsKey(instrument)) {
                result.put(instrument, mockQuoteData.get(instrument));
            } else {
                // Return default quote for unknown instruments
                Quote defaultQuote = createDefaultQuote();
                result.put(instrument, defaultQuote);
            }
        }

        return result;
    }

    private Quote createDefaultQuote() {
        Quote quote = new Quote();
        quote.lastPrice = 100.0;
        quote.averagePrice = 98.0;
        quote.volumeTradedToday = 500;
        quote.lastTradedQuantity = 25;
        quote.lastTradedTime = new Date();
        quote.timestamp = new Date();

        quote.ohlc = new OHLC();
        quote.ohlc.open = 95.0;
        quote.ohlc.high = 105.0;
        quote.ohlc.low = 90.0;
        quote.ohlc.close = 100.0;

        quote.change = 2.0;
        quote.buyQuantity = 150.0;
        quote.sellQuantity = 120.0;
        quote.instrumentToken = 999999L;

        return quote;
    }

    @Override
    public List<Instrument> getInstruments(String exchange) throws KiteException {
        if ("NFO".equals(exchange)) {
            return new ArrayList<>(mockInstruments);
        }
        return new ArrayList<>();
    }

    @Override
    public Order placeOrder(OrderParams orderParams, String variety) throws KiteException {
        Order order = new Order();
        order.orderId = "TEST" + System.currentTimeMillis();
        order.tradingSymbol = orderParams.tradingsymbol;
        order.transactionType = orderParams.transactionType;
        order.quantity = String.valueOf(orderParams.quantity);
        order.product = orderParams.product;
        order.orderType = orderParams.orderType;
        order.status = "COMPLETE"; // Mock successful order

        placedOrders.add(order);
        System.out.println("📝 MOCK ORDER PLACED: " + order.orderId + " for " + order.tradingSymbol);
        System.out.println("   Type: " + order.transactionType + ", Qty: " + order.quantity);

        return order;
    }

    public List<Order> getPlacedOrders() {
        return new ArrayList<>(placedOrders);
    }

    public void updateQuote(String symbol, double newPrice) {
        if (mockQuoteData.containsKey(symbol)) {
            Quote quote = mockQuoteData.get(symbol);
            quote.lastPrice = newPrice;
            quote.averagePrice = newPrice;
            quote.ohlc.close = newPrice;

            // Update high/low if needed
            if (newPrice > quote.ohlc.high) {
                quote.ohlc.high = newPrice;
            }
            if (newPrice < quote.ohlc.low) {
                quote.ohlc.low = newPrice;
            }
        }
    }

    // Method to simulate price movements for testing
    public void simulatePriceMovement(String symbol, double newPrice) {
        updateQuote(symbol, newPrice);
        System.out.println("📊 SIMULATED PRICE UPDATE: " + symbol + " -> " + newPrice);
    }
}