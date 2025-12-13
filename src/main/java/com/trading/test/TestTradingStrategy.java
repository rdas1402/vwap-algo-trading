//// TestTradingStrategy.java
//package com.trading.test;
//
//import com.trading.strategy.*;
//
//import java.util.*;
//
//public class TestTradingStrategy {
//
//    private TradingSignalGenerator signalGenerator;
//    private Map<String, Position> currentPositions;
//    private Map<String, List<CandleData>> historicalData;
//    private List<Double> vwapValues;
//    private double totalPnL;
//    private int currentCandleIndex;
//    private boolean positionOpen = false; // Track if any position is open
//
//    public TestTradingStrategy() {
//        this.signalGenerator = new TradingSignalGenerator();
//        this.currentPositions = new HashMap<>();
//        this.historicalData = new HashMap<>();
//        this.vwapValues = new ArrayList<>();
//        this.totalPnL = 0.0;
//        this.currentCandleIndex = 0;
//        this.positionOpen = false;
//    }
//
//    public void loadHistoricalData(String symbol, List<CandleData> candles, List<Double> vwapValues) {
//        historicalData.put(symbol, candles);
//        this.vwapValues = vwapValues;
//        System.out.println("Loaded " + candles.size() + " candles with " + vwapValues.size() + " VWAP values");
//    }
//
//    public void runBacktest() {
//        List<CandleData> niftyCandles = historicalData.get("NSE:NIFTY 50");
//        if (niftyCandles == null || niftyCandles.isEmpty()) {
//            System.out.println("No historical data loaded");
//            return;
//        }
//
//        if (vwapValues.size() != niftyCandles.size()) {
//            System.out.println("Warning: Candle count (" + niftyCandles.size() +
//                    ") doesn't match VWAP count (" + vwapValues.size() + ")");
//            int minSize = Math.min(niftyCandles.size(), vwapValues.size());
//            niftyCandles = niftyCandles.subList(0, minSize);
//        }
//
//        System.out.println("\n=== STARTING BACKTEST WITH PRE-CALCULATED VWAP ===");
//        System.out.println("Total candles: " + niftyCandles.size());
//        System.out.println("Date range: " + niftyCandles.get(0).getTimestamp() + " to " +
//                niftyCandles.get(niftyCandles.size()-1).getTimestamp());
//
//        // Start from the beginning since we have pre-calculated VWAP
//        for (int i = 0; i < niftyCandles.size(); i++) {
//            currentCandleIndex = i;
//            CandleData currentCandle = niftyCandles.get(i);
//            double currentVWAP = getVWAPForCandle(i);
//
//            System.out.println("\n--- Candle " + (i + 1) + " | " + currentCandle.getTimestamp() + " ---");
//            System.out.printf("OHLC: %.2f/%.2f/%.2f/%.2f | VWAP: %.2f%n",
//                    currentCandle.getOpen(), currentCandle.getHigh(),
//                    currentCandle.getLow(), currentCandle.getClose(), currentVWAP);
//
//            // Generate market data for current candle
//            Map<String, Object> marketData = createMarketData(currentCandle, currentVWAP);
//
//            // 1. First check for exits from existing positions
//            boolean exitedPosition = checkAndExitPositions(marketData, currentCandle, currentVWAP, i);
//
//            // 2. Only if no position is open, check for new entries
//            if (!positionOpen) {
//                List<TradingSignal> signals = generateVWAPSignals(currentCandle, currentVWAP, i);
//
//                // Execute signals if we have no open positions
//                for (TradingSignal signal : signals) {
//                    executeTestSignal(signal, marketData, currentVWAP, currentCandle);
//                    break; // Only take one signal per candle
//                }
//            }
//
//            // Print current status every 5 candles for better visibility
//            if (i % 5 == 0) {
//                printTestStatus();
//            }
//        }
//
//        // Close all remaining positions at the last candle
//        if (!niftyCandles.isEmpty()) {
//            closeAllTestPositions(niftyCandles.get(niftyCandles.size()-1).getClose());
//        }
//
//        System.out.println("\n=== BACKTEST COMPLETED ===");
//        System.out.printf("Total P&L: %.2f%n", totalPnL);
//        System.out.println("Final Positions: " + currentPositions.size());
//        printPerformanceSummary();
//    }
//
//    private boolean checkAndExitPositions(Map<String, Object> marketData, CandleData currentCandle,
//                                          double currentVWAP, int currentIndex) {
//        if (!positionOpen) {
//            return false;
//        }
//
//        boolean exitedAnyPosition = false;
//        Iterator<Map.Entry<String, Position>> iterator = currentPositions.entrySet().iterator();
//
//        while (iterator.hasNext()) {
//            Map.Entry<String, Position> entry = iterator.next();
//            Position position = entry.getValue();
//            String tradingSymbol = entry.getKey();
//
//            try {
//                double currentPrice = getCurrentPriceForSymbol(tradingSymbol, marketData);
//                boolean shouldExit = false;
//                String exitReason = "";
//
//                // Check standard SL/Target conditions
//                if (position.getSignalType() == SignalType.BUY) {
//                    if (currentCandle.getClose() <= position.getStopLoss()) {
//                        shouldExit = true;
//                        exitReason = "Stop Loss Hit";
//                    } else if (currentCandle.getClose() >= position.getTarget()) {
//                        shouldExit = true;
//                        exitReason = "Target Achieved";
//                    }
//                } else {
//                    if (currentCandle.getClose() >= position.getStopLoss()) {
//                        shouldExit = true;
//                        exitReason = "Stop Loss Hit";
//                    } else if (currentCandle.getClose() <= position.getTarget()) {
//                        shouldExit = true;
//                        exitReason = "Target Achieved";
//                    }
//                }
//
//                // Check VWAP reversal condition (NEW REQUIREMENT #2)
//                if (!shouldExit && currentIndex > 0) {
//                    List<CandleData> niftyCandles = historicalData.get("NSE:NIFTY 50");
//                    CandleData previousCandle = niftyCandles.get(currentIndex - 1);
//                    double previousVWAP = getVWAPForCandle(currentIndex - 1);
//
//                    if (position.getSignalType() == SignalType.BUY) {
//                        // For CALL position: Exit if price closes below VWAP (reversal)
//                        if (currentCandle.getClose() < currentVWAP && previousCandle.getClose() >= previousVWAP) {
//                            shouldExit = true;
//                            exitReason = "VWAP Reversal (Price closed below VWAP)";
//                        }
//                    } else {
//                        // For PUT position: Exit if price closes above VWAP (reversal)
//                        if (currentCandle.getClose() > currentVWAP && previousCandle.getClose() <= previousVWAP) {
//                            shouldExit = true;
//                            exitReason = "VWAP Reversal (Price closed above VWAP)";
//                        }
//                    }
//                }
//
//                if (shouldExit) {
//                    double pnl = calculateTestPnl(position, currentCandle.getClose());
//                    totalPnL += pnl;
//                    iterator.remove();
//                    positionOpen = false; // Reset position flag
//                    exitedAnyPosition = true;
//
//                    String outcome = pnl >= 0 ? "PROFIT" : "LOSS";
//                    System.out.printf("<<< TRADE EXIT: %s | %s | Reason: %s | P&L: %.2f | Total: %.2f%n",
//                            outcome, tradingSymbol, exitReason, pnl, totalPnL);
//                }
//
//            } catch (Exception e) {
//                System.err.println("Error managing test position: " + e.getMessage());
//            }
//        }
//
//        return exitedAnyPosition;
//    }
//
//    private double getVWAPForCandle(int index) {
//        if (index >= 0 && index < vwapValues.size()) {
//            return vwapValues.get(index);
//        }
//        return 0.0;
//    }
//
//    private Map<String, Object> createMarketData(CandleData currentCandle, double vwap) {
//        Map<String, Object> marketData = new HashMap<>();
//
//        // Create mock quote data for NIFTY
//        Map<String, Object> niftyData = new HashMap<>();
//        niftyData.put("lastPrice", currentCandle.getClose());
//        niftyData.put("open", currentCandle.getOpen());
//        niftyData.put("high", currentCandle.getHigh());
//        niftyData.put("low", currentCandle.getLow());
//        niftyData.put("close", currentCandle.getClose());
//        niftyData.put("volume", currentCandle.getVolume());
//        niftyData.put("vwap", vwap);
//
//        marketData.put("NSE:NIFTY 50", niftyData);
//
//        // Create option data (synthetic - based on Nifty price)
//        Map<String, Object> callData = new HashMap<>();
//        Map<String, Object> putData = new HashMap<>();
//
//        double optionPrice = 100.0 + (currentCandle.getClose() - 24300.0); // Adjust based on your price range
//        callData.put("lastPrice", optionPrice);
//        putData.put("lastPrice", optionPrice);
//
//        marketData.put("NIFTY_CALL_NEAR_100", callData);
//        marketData.put("NIFTY_PUT_NEAR_100", putData);
//
//        return marketData;
//    }
//
//    private List<TradingSignal> generateVWAPSignals(CandleData currentCandle, double currentVWAP, int currentIndex) {
//        List<TradingSignal> signals = new ArrayList<>();
//
//        // Need at least one previous candle for crossover detection
//        if (currentIndex == 0) {
//            return signals;
//        }
//
//        List<CandleData> niftyCandles = historicalData.get("NSE:NIFTY 50");
//        CandleData previousCandle = niftyCandles.get(currentIndex - 1);
//        double previousVWAP = getVWAPForCandle(currentIndex - 1);
//
//        double currentClose = currentCandle.getClose();
//        double previousClose = previousCandle.getClose();
//
//        // VWAP Crossover Strategy
//        boolean crossedAboveVWAP = (previousClose <= previousVWAP) && (currentClose > currentVWAP);
//        boolean crossedBelowVWAP = (previousClose >= previousVWAP) && (currentClose < currentVWAP);
//
//        if (crossedAboveVWAP) {
//            System.out.println(">>> VWAP BUY SIGNAL: Price crossed above VWAP");
//            signals.add(new TradingSignal("NIFTY_CALL_NEAR_100", SignalType.BUY, currentClose));
//        } else if (crossedBelowVWAP) {
//            System.out.println(">>> VWAP SELL SIGNAL: Price crossed below VWAP");
//            signals.add(new TradingSignal("NIFTY_PUT_NEAR_100", SignalType.SELL, currentClose));
//        }
//
//        return signals;
//    }
//
//    private void executeTestSignal(TradingSignal signal, Map<String, Object> marketData,
//                                   double vwap, CandleData currentCandle) {
//        try {
//            // REQUIREMENT #3: Don't take new position if one is already open
//            if (positionOpen) {
//                System.out.println(">>> SKIPPING ENTRY: Position already open");
//                return;
//            }
//
//            String tradingSymbol = signal.getTradingSymbol();
//            double entryPrice = signal.getPrice();
//
//            //Uncomment
//            //int quantity = calculateTestPositionSize(entryPrice);
//
//            int quantity = 1;
//
//            if (quantity < 1) {
//                return;
//            }
//
//            // Create and store position
//            Position position = new Position();
//
//            // For option signals, use synthetic option symbols
//            if (tradingSymbol.contains("CALL")) {
//                position.setTradingSymbol("TEST_CALL_" + currentCandleIndex);
//            } else if (tradingSymbol.contains("PUT")) {
//                position.setTradingSymbol("TEST_PUT_" + currentCandleIndex);
//            } else {
//                position.setTradingSymbol(tradingSymbol);
//            }
//
//            position.setOrderId("TEST_" + System.currentTimeMillis());
//            position.setEntryPrice(entryPrice);
//            position.setQuantity(quantity);
//            position.setSignalType(signal.getSignalType());
//
//            // Set stop loss and target using VWAP-based logic
//            setVWAPBasedStopLossAndTarget(position, signal, currentCandle);
//
//            currentPositions.put(position.getTradingSymbol(), position);
//            positionOpen = true; // Set position flag
//
//            System.out.printf(">>> TRADE ENTRY: %s | %s | Qty: %d | Entry: %.2f | SL: %.2f | Target: %.2f%n",
//                    signal.getSignalType(), position.getTradingSymbol(), quantity,
//                    entryPrice, position.getStopLoss(), position.getTarget());
//
//        } catch (Exception e) {
//            System.err.println("Error executing test signal: " + e.getMessage());
//        }
//    }
//
//    private void setVWAPBasedStopLossAndTarget(Position position, TradingSignal signal, CandleData currentCandle) {
//        if (signal.getSignalType() == SignalType.BUY) {
//            // For CALL options: Stop loss at bottom of the candle that crossed VWAP
//            position.setStopLoss(currentCandle.getLow());
//            // Target: Risk-to-reward ratio of 1:2
//            double risk = position.getEntryPrice() - position.getStopLoss();
//            position.setTarget(position.getEntryPrice() + (risk * 2));
//        } else {
//            // For PUT options: Stop loss at top of the candle that crossed VWAP
//            position.setStopLoss(currentCandle.getHigh());
//            // Target: Risk-to-reward ratio of 1:2
//            double risk = position.getStopLoss() - position.getEntryPrice();
//            position.setTarget(position.getEntryPrice() - (risk * 2));
//        }
//    }
//
//    private double getCurrentPriceForSymbol(String tradingSymbol, Map<String, Object> marketData) {
//        if (tradingSymbol.contains("CALL") || tradingSymbol.contains("PUT")) {
//            Object optionData = marketData.get("NIFTY_CALL_NEAR_100");
//            return extractTestPriceFromMarketData(optionData);
//        } else {
//            Object niftyData = marketData.get("NSE:NIFTY 50");
//            return extractTestPriceFromMarketData(niftyData);
//        }
//    }
//
//    private double calculateTestPnl(Position position, double exitPrice) {
//        if (position.getSignalType() == SignalType.BUY) {
//            return (exitPrice - position.getEntryPrice()) * position.getQuantity();
//        } else {
//            return (position.getEntryPrice() - exitPrice) * position.getQuantity();
//        }
//    }
//
//    private double extractTestPriceFromMarketData(Object marketData) {
//        if (marketData instanceof Map) {
//            Map<?, ?> dataMap = (Map<?, ?>) marketData;
//            Object price = dataMap.get("lastPrice");
//            if (price instanceof Number) {
//                return ((Number) price).doubleValue();
//            }
//        }
//        return 0.0;
//    }
//
//    private int calculateTestPositionSize(double entryPrice) {
//        double riskAmount = 1000.0; // Increased for your price range
//        double stopLossPercentage = 0.002; // 0.2% stop loss for your range
//
//        double riskPerUnit = entryPrice * stopLossPercentage;
//
//        if (riskPerUnit == 0) return 1;
//
//        int quantity = (int) Math.floor(riskAmount / riskPerUnit);
//        return Math.max(quantity, 1);
//    }
//
//    private void closeAllTestPositions(double lastPrice) {
//        for (Position position : new ArrayList<>(currentPositions.values())) {
//            double pnl = calculateTestPnl(position, lastPrice);
//            totalPnL += pnl;
//            currentPositions.remove(position.getTradingSymbol());
//
//            System.out.printf("!!! FINAL EXIT: %s | P&L: %.2f%n",
//                    position.getTradingSymbol(), pnl);
//        }
//        positionOpen = false;
//    }
//
//    private void printTestStatus() {
//        System.out.printf("=== STATUS UPDATE: Open Positions: %d | Running P&L: %.2f%n",
//                currentPositions.size(), totalPnL);
//    }
//
//    private void printPerformanceSummary() {
//        System.out.println("\n=== PERFORMANCE SUMMARY ===");
//        System.out.printf("Total P&L: %.2f%n", totalPnL);
//        System.out.println("Final Open Positions: " + currentPositions.size());
//        System.out.println("VWAP Data Points Used: " + vwapValues.size());
//        System.out.println("Position Open Flag: " + positionOpen);
//    }
//
//    public double getTotalPnL() {
//        return totalPnL;
//    }
//
//    public int getCurrentPositionsCount() {
//        return currentPositions.size();
//    }
//}