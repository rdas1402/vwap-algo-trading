package com.trading.testutil;

import com.zerodhatech.models.Tick;
import com.zerodhatech.models.OHLC;

import java.util.Date;

public class TestDataGenerator {

    public static Tick createOptionTick(long instrumentToken, double lastPrice, double averagePrice, double volume) {
        Tick tick = new Tick();
        tick.setInstrumentToken(instrumentToken);
        tick.setLastTradedPrice(lastPrice);
        tick.setAverageTradePrice(averagePrice);
        tick.setLastTradedTime(new Date());
        tick.setVolumeTradedToday((long) volume);
        tick.setTotalBuyQuantity(500L);
        tick.setTotalSellQuantity(500L);
        tick.setOi(1000L);
        tick.setOpenInterestDayHigh(1200L);
        tick.setOpenInterestDayLow(800L);

        // Set OHLC data
        tick.setOpenPrice(lastPrice - 5);
        tick.setHighPrice(lastPrice + 10);
        tick.setLowPrice(lastPrice - 15);
        tick.setClosePrice(lastPrice);

        return tick;
    }

    public static Tick createNiftyTick(double lastPrice) {
        Tick tick = new Tick();
        tick.setInstrumentToken(256265L);
        tick.setLastTradedPrice(lastPrice);
        tick.setAverageTradePrice(lastPrice);
        tick.setLastTradedTime(new Date());
        tick.setVolumeTradedToday(1000000L);
        tick.setOpenPrice(lastPrice - 50);
        tick.setHighPrice(lastPrice + 100);
        tick.setLowPrice(lastPrice - 80);
        tick.setClosePrice(lastPrice);

        return tick;
    }

    public static String[] generateOptionSymbols(double basePrice, int count) {
        String[] symbols = new String[count * 2]; // CE and PE

        for (int i = 0; i < count; i++) {
            double strike = basePrice + (i * 50);
            symbols[i * 2] = "NFO:NIFTY25JAN" + (int)strike + "CE";
            symbols[i * 2 + 1] = "NFO:NIFTY25JAN" + (int)strike + "PE";
        }

        return symbols;
    }

    // Create test scenarios for VWAP crossover
    public static TestScenario createVWAPCrossoverScenario() {
        TestScenario scenario = new TestScenario();
        scenario.setDescription("VWAP Crossover - Buy Signal");
        scenario.setInitialPrice(95.0);  // Below VWAP
        scenario.setVwapPrice(100.0);
        scenario.setFinalPrice(105.0);   // Above VWAP (crossover)
        return scenario;
    }

    public static TestScenario createVWAPRejectionScenario() {
        TestScenario scenario = new TestScenario();
        scenario.setDescription("VWAP Rejection - No Signal");
        scenario.setInitialPrice(105.0); // Above VWAP
        scenario.setVwapPrice(100.0);
        scenario.setFinalPrice(95.0);    // Below VWAP (rejection)
        return scenario;
    }

    public static class TestScenario {
        private String description;
        private double initialPrice;
        private double vwapPrice;
        private double finalPrice;

        // Getters and setters
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public double getInitialPrice() { return initialPrice; }
        public void setInitialPrice(double initialPrice) { this.initialPrice = initialPrice; }
        public double getVwapPrice() { return vwapPrice; }
        public void setVwapPrice(double vwapPrice) { this.vwapPrice = vwapPrice; }
        public double getFinalPrice() { return finalPrice; }
        public void setFinalPrice(double finalPrice) { this.finalPrice = finalPrice; }
    }
}