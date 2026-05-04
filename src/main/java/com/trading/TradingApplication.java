// TradingApplication.java - FIXED
package com.trading;

import com.trading.config.AppConfig;
import com.trading.config.PnLManager;
import com.trading.strategy.TradingStrategyEngine;

import java.util.*;

public class TradingApplication {

    private static TradingStrategyEngine tradingEngine;
    private static Timer timer;
    private static boolean isTradingActive = false;
    private static boolean tradingDayEnded = false;

    private static final PnLManager pnlManager = PnLManager.getInstance();

    public static void main(String[] args) {
        try {
            System.out.println("🚀 Starting Automated Trading Strategy Engine...");

            // Initialize Trading Engine
            tradingEngine = new TradingStrategyEngine();

            // Reset daily P&L
            pnlManager.resetDailyPnL();

            long initialDelay = calculateInitialDelay();
            System.out.println("⏰ Initial delay: " + (initialDelay / 1000) + " seconds");

            int intervalMinutes = AppConfig.getTradingIntervalMinutes();
            timer = new Timer();
            timer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    executeTradingCycle();
                }
            }, initialDelay, (long) intervalMinutes * 60 * 1000);

            addShutdownHook();
            keepApplicationAlive();

        } catch (Exception e) {
            System.err.println("❌ Failed to start trading application: " + e.getMessage());
            e.printStackTrace();
            cleanup();
        }
    }

    private static void executeTradingCycle() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("🔄 STARTING TRADING CYCLE AT: " + new Date());
        System.out.println("=".repeat(80));

        if (tradingDayEnded) {
            System.out.println("🛑 Trading day already ended");
            return;
        }

        // FIXED: Uncommented - Only run trading cycles during market hours
        if (!isWithinTradingHours()) {
            System.out.println("⏸️ Outside trading hours (" + getCurrentTime() + ") - skipping cycle");
            System.out.println("   Trading hours: 9:15 AM to 3:30 PM");
            return;
        }

        if (pnlManager.isDailyLossLimitReached()) {
            System.out.println("🚫 Daily loss limit reached");
            return;
        }

        if (shouldEndTradingDayEarly()) {
            System.out.println("💰 ENDING TRADING DAY EARLY");
            tradingDayEnded = true;
            if (tradingEngine != null) {
                tradingEngine.stopTrading();
            }
            if (timer != null) {
                timer.cancel();
                timer = null;
            }
            return;
        }

        try {
            tradingEngine.executeTradingCycle();
            isTradingActive = true;
        } catch (Exception e) {
            System.err.println("❌ Error in trading cycle: " + e.getMessage());
            isTradingActive = false;
        }
    }

    private static String getCurrentTime() {
        Calendar cal = Calendar.getInstance();
        return String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE));
    }

    private static boolean shouldEndTradingDayEarly() {
        try {
            Calendar cal = Calendar.getInstance();
            int hour = cal.get(Calendar.HOUR_OF_DAY);
            int minute = cal.get(Calendar.MINUTE);

            boolean isAfter245PM = (hour > 14) || (hour == 14 && minute >= 45);
            if (!isAfter245PM) return false;

            double dailyPnL = pnlManager.getTotalDailyPnL();
            boolean isProfitable = dailyPnL > 0;
            boolean hasNoPositions = tradingEngine != null && tradingEngine.hasNoOpenPositions();

            return isProfitable && hasNoPositions;

        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isWithinTradingHours() {
        try {
            Calendar cal = Calendar.getInstance();
            int hour = cal.get(Calendar.HOUR_OF_DAY);
            int minute = cal.get(Calendar.MINUTE);
            int currentTimeInMinutes = hour * 60 + minute;

            // Trading hours: 9:15 AM to 3:30 PM
            int marketOpenTime = 9 * 60 + 15;  // 9:15 AM
            int marketCloseTime = 15 * 60 + 30; // 3:30 PM

            return currentTimeInMinutes >= marketOpenTime && currentTimeInMinutes <= marketCloseTime;

        } catch (Exception e) {
            return false;
        }
    }

    private static long calculateInitialDelay() {
        Calendar now = Calendar.getInstance();
        int currentMinute = now.get(Calendar.MINUTE);
        int intervalMinutes = AppConfig.getTradingIntervalMinutes();

        int minutesToNextBoundary = intervalMinutes - (currentMinute % intervalMinutes);
        if (minutesToNextBoundary == intervalMinutes) minutesToNextBoundary = 0;

        Calendar nextRun = Calendar.getInstance();
        nextRun.add(Calendar.MINUTE, minutesToNextBoundary);
        nextRun.set(Calendar.SECOND, 0);
        nextRun.set(Calendar.MILLISECOND, 0);

        long delay = nextRun.getTimeInMillis() - System.currentTimeMillis();

        // If delay is too short, add interval
        if (delay < 5000) {
            nextRun.add(Calendar.MINUTE, intervalMinutes);
            delay = nextRun.getTimeInMillis() - System.currentTimeMillis();
        }

        // If current time is before market open, delay until market opens
        Calendar now2 = Calendar.getInstance();
        int currentHour = now2.get(Calendar.HOUR_OF_DAY);
        int currentMinute2 = now2.get(Calendar.MINUTE);
        int currentTotal = currentHour * 60 + currentMinute2;
        int marketOpen = 9 * 60 + 15;

        if (currentTotal < marketOpen) {
            Calendar marketOpenCal = Calendar.getInstance();
            marketOpenCal.set(Calendar.HOUR_OF_DAY, 9);
            marketOpenCal.set(Calendar.MINUTE, 15);
            marketOpenCal.set(Calendar.SECOND, 0);
            marketOpenCal.set(Calendar.MILLISECOND, 0);
            delay = marketOpenCal.getTimeInMillis() - System.currentTimeMillis();
            System.out.println("⏰ Waiting until market opens at 9:15 AM");
        }

        return Math.max(delay, 1000);
    }

    private static void addShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n🛑 SHUTDOWN SIGNAL RECEIVED");
            cleanup();
        }));
    }

    private static void cleanup() {
        System.out.println("🧹 Cleaning up...");
        if (timer != null) {
            timer.cancel();
        }
        if (tradingEngine != null) {
            tradingEngine.stopTrading();
        }
        System.out.println("✅ Cleanup completed");
    }

    private static void keepApplicationAlive() {
        System.out.println("🎯 Application is running...");
        System.out.println("💡 Press Ctrl+C to stop");

        try {
            while (true) {
                Thread.sleep(300000); // Check every 5 minutes
                if (shouldAutoStop() || tradingDayEnded) {
                    System.out.println("🛑 Stopping application...");
                    cleanup();
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean shouldAutoStop() {
        try {
            Calendar cal = Calendar.getInstance();
            int hour = cal.get(Calendar.HOUR_OF_DAY);
            int minute = cal.get(Calendar.MINUTE);
            // Stop after 3:45 PM
            return hour > 15 || (hour == 15 && minute >= 45);
        } catch (Exception e) {
            return false;
        }
    }
}