package com.trading;

import com.trading.config.AppConfig;
import com.trading.config.PnLManager;
import com.trading.strategy.VWAPOptionsStrategy;

import java.util.*;

public class TradingApplication {

    private static VWAPOptionsStrategy vwapOptionsStrategy;
    private static Timer timer;
    private static final long startTime = System.currentTimeMillis();
    private static boolean isTradingActive = false;
    private static boolean tradingDayEnded = false; // NEW: Track if trading day ended early

    // P&L manager instance
    private static final PnLManager pnlManager = PnLManager.getInstance();

    public static void main(String[] args) {
        try {
            System.out.println("🚀 Starting Automated Trading Strategy with Real-time Candle Analysis...");

            // Initialize VWAP Options Strategy
            vwapOptionsStrategy = new VWAPOptionsStrategy();

            // Reset daily P&L at application start
            pnlManager.resetDailyPnL();

            long initialDelay = calculateInitialDelay();
            System.out.println("⏰ Initial delay: " + (initialDelay / 1000) + " seconds");
            System.out.println("🕐 First execution at: " + new Date(System.currentTimeMillis() + initialDelay));

            int intervalMinutes = AppConfig.getTradingIntervalMinutes();
            timer = new Timer();
            timer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    startVWAPOptionsStrategy();
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

    private static void startVWAPOptionsStrategy() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("🔄 STARTING VWAP OPTIONS STRATEGY AT: " + new Date());
        System.out.println("🎯 NOW WITH REAL-TIME CANDLE ANALYSIS & VWAP REVERSAL PATTERNS");
        System.out.println("=".repeat(80));

        // STEP 0: Check if trading day already ended early
        if (tradingDayEnded) {
            System.out.println("🛑 Trading day ended early (profitable by 2:45 PM with no positions)");
            System.out.println("=".repeat(80) + "\n");
            return;
        }

        // STEP 1: Check trading hours first
        if (!isWithinTradingHours()) {
            System.out.println("⏸️ Outside trading hours. Skipping cycle...");
            System.out.println("=".repeat(80) + "\n");
            return;
        }

        // STEP 2: Check if daily loss limit reached
        if (pnlManager.isDailyLossLimitReached()) {
            System.out.println("🚫 Daily loss limit reached. Skipping cycle...");
            System.out.println("=".repeat(80) + "\n");
            return;
        }

        // NEW: Check if we should end trading day early (profitable by 2:45 PM)
        if (shouldEndTradingDayEarly()) {
            System.out.println("💰 ENDING TRADING DAY EARLY - In profit by 2:45 PM with no open positions");
            System.out.println("📊 Daily P&L: ₹" + String.format("%.2f", pnlManager.getTotalDailyPnL()));

            // Mark trading day as ended
            tradingDayEnded = true;

            // Stop VWAP strategy
            if (vwapOptionsStrategy != null) {
                vwapOptionsStrategy.stopVWAPOptionsTrading();
                isTradingActive = false;
            }

            // Stop timer
            if (timer != null) {
                timer.cancel();
                timer = null;
            }

            System.out.println("✅ Trading day ended successfully");
            System.out.println("=".repeat(80) + "\n");
            return;
        }

        try {
            // STEP 3: Execute single batch trading cycle
            System.out.println("🎯 EXECUTING TRADING CYCLE WITH REAL-TIME ANALYSIS...");
            vwapOptionsStrategy.executeTradingCycle();
            isTradingActive = true;

            // STEP 4: Display status from main application
            displayMarketStatus();

        } catch (Exception e) {
            System.err.println("❌ Error in trading cycle: " + e.getMessage());
            e.printStackTrace();
            isTradingActive = false;
        }

        System.out.println("✅ Trading cycle completed.");
        System.out.println("⏰ Next execution in " + AppConfig.getTradingIntervalMinutes() + " minutes.");
        System.out.println("=".repeat(80) + "\n");
    }

    // NEW: Check if we should end trading day early
    private static boolean shouldEndTradingDayEarly() {
        try {
            Calendar cal = Calendar.getInstance();
            int hour = cal.get(Calendar.HOUR_OF_DAY);
            int minute = cal.get(Calendar.MINUTE);

            // Check if it's 2:45 PM or later
            boolean isAfter245PM = (hour > 14) || (hour == 14 && minute >= 45);

            if (!isAfter245PM) {
                return false;
            }

            // Check if we have overall profit
            double dailyPnL = pnlManager.getTotalDailyPnL();
            boolean isProfitable = dailyPnL > 0;

            // Check if VWAP strategy has no open positions
            boolean hasNoPositions = true;
            if (vwapOptionsStrategy != null) {
                // We'll need to add a method to check positions in VWAPOptionsStrategy
                hasNoPositions = vwapOptionsStrategy.hasNoOpenPositions();
            }

            if (isProfitable && hasNoPositions) {
                System.out.println("📊 Early Trading Day End Conditions Met:");
                System.out.println("   - Time: " + hour + ":" + String.format("%02d", minute) + " PM (≥ 2:45 PM)");
                System.out.println("   - Daily P&L: ₹" + String.format("%.2f", dailyPnL) + " (Profitable)");
                System.out.println("   - Open Positions: " + (!hasNoPositions ? "Yes" : "No"));
                return true;
            }

            return false;

        } catch (Exception e) {
            System.err.println("❌ Error checking early trading end conditions: " + e.getMessage());
            return false;
        }
    }

    private static void displayMarketStatus() {
        System.out.println("📈 MARKET STATUS:");
        System.out.println("   - Trading Hours: " + (isWithinTradingHours() ? "OPEN ✅" : "CLOSED 🔒"));
        System.out.println("   - VWAP Strategy: " + (isTradingActive ? "ACTIVE 🎯" : "INACTIVE"));
        System.out.println("   - Trading Allowed: " + (isTradingAllowed() ? "YES ✅" : "NO ❌"));
        System.out.println("   - Daily P&L: ₹" + String.format("%.2f", pnlManager.getTotalDailyPnL()));
        System.out.println("   - Max Daily Loss: ₹" + AppConfig.getMaxDailyLoss());

        if (pnlManager.isDailyLossLimitReached()) {
            System.out.println("   ⚠️  DAILY LOSS LIMIT REACHED - Trading Stopped");
        }
    }

    private static boolean isWithinTradingHours() {
        try {
            Calendar cal = Calendar.getInstance();
            int hour = cal.get(Calendar.HOUR_OF_DAY);
            int minute = cal.get(Calendar.MINUTE);

            String startTime = AppConfig.getTradingStartTime();
            String endTime = AppConfig.getTradingEndTime();

            if (startTime.contains(":") && endTime.contains(":")) {
                String[] startParts = startTime.split(":");
                String[] endParts = endTime.split(":");

                int startHour = Integer.parseInt(startParts[0]);
                int startMinute = Integer.parseInt(startParts[1]);
                int endHour = Integer.parseInt(endParts[0]);
                int endMinute = Integer.parseInt(endParts[1]);

                int currentTimeInMinutes = hour * 60 + minute;
                int startTimeInMinutes = startHour * 60 + startMinute;
                int endTimeInMinutes = endHour * 60 + endMinute;

                return currentTimeInMinutes >= startTimeInMinutes && currentTimeInMinutes <= endTimeInMinutes;
            }

            // Default trading hours: 9:15 AM to 3:30 PM
            return (hour >= 9 && minute >= 15) && (hour < 15 || (hour == 15 && minute <= 30));

        } catch (Exception e) {
            System.err.println("Error checking trading hours: " + e.getMessage());
            Calendar cal = Calendar.getInstance();
            int hour = cal.get(Calendar.HOUR_OF_DAY);
            int minute = cal.get(Calendar.MINUTE);
            return (hour >= 9 && minute >= 15) && (hour < 15 || (hour == 15 && minute <= 30));
        }
    }

    private static boolean isTradingAllowed() {
        return isWithinTradingHours() && !pnlManager.isDailyLossLimitReached();
    }

    private static long calculateInitialDelay() {
        Calendar now = Calendar.getInstance();
        int currentMinute = now.get(Calendar.MINUTE);
        int currentSecond = now.get(Calendar.SECOND);
        int currentMillisecond = now.get(Calendar.MILLISECOND);

        int intervalMinutes = AppConfig.getTradingIntervalMinutes();

        // Calculate minutes to next 5-minute boundary
        int minutesToNextBoundary = intervalMinutes - (currentMinute % intervalMinutes);

        // If exactly at boundary, set to next boundary
        if (minutesToNextBoundary == intervalMinutes) {
            minutesToNextBoundary = 0;
        }

        Calendar nextRun = Calendar.getInstance();
        nextRun.add(Calendar.MINUTE, minutesToNextBoundary);
        nextRun.set(Calendar.SECOND, 0);
        nextRun.set(Calendar.MILLISECOND, 0);

        long delay = nextRun.getTimeInMillis() - System.currentTimeMillis();

        // If delay is very small (less than 5 seconds), wait for next boundary
        if (delay < 5000) {
            nextRun.add(Calendar.MINUTE, intervalMinutes);
            delay = nextRun.getTimeInMillis() - System.currentTimeMillis();
        }

        System.out.println("⏰ Next execution aligned to: " + nextRun.getTime());
        System.out.println("   Current time: " + now.getTime());
        System.out.println("   Delay: " + (delay / 1000) + " seconds");

        return Math.max(delay, 1000); // At least 1 second delay
    }

    private static void addShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n" + "=".repeat(80));
            System.out.println("🛑 SHUTDOWN SIGNAL RECEIVED - CLEANING UP");
            System.out.println("=".repeat(80));
            cleanup();
            System.out.println("✅ Trading application stopped successfully.");
            System.out.println("=".repeat(80));
        }));
    }

    private static void cleanup() {
        System.out.println("🧹 Cleaning up resources...");

        // Stop timer first
        if (timer != null) {
            timer.cancel();
            System.out.println("   - Trading timer stopped");
        }

        // Close all open positions in VWAP strategy
        if (vwapOptionsStrategy != null && isTradingActive) {
            System.out.println("   - Closing all open positions in VWAP Strategy...");
            vwapOptionsStrategy.stopVWAPOptionsTrading();
            isTradingActive = false;
            System.out.println("   - VWAP Options Strategy stopped");
        }

        System.out.println("✅ Cleanup completed");
    }

    private static void keepApplicationAlive() {
        System.out.println("🎯 Application is running...");
        System.out.println("💡 Press Ctrl+C to stop the application");
        System.out.println("⏰ Trading interval: " + AppConfig.getTradingIntervalMinutes() + " minutes");
        System.out.println("🕒 Trading hours: " + AppConfig.getTradingStartTime() + " to " + AppConfig.getTradingEndTime());
        System.out.println("💰 Max Daily Loss: ₹" + AppConfig.getMaxDailyLoss());

        // Status timer for heartbeat - runs every 5 minutes
        Timer statusTimer = new Timer();
        int heartbeatInterval = AppConfig.getTradingIntervalMinutes() * 60 * 1000;

        statusTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                // Display heartbeat
                displayHeartbeat();

                // Check if we need to stop trading due to end of trading hours
                if (isTradingActive && !isWithinTradingHours()) {
                    System.out.println("🕒 Trading hours ended. Stopping VWAP Strategy...");
                    if (vwapOptionsStrategy != null) {
                        vwapOptionsStrategy.stopVWAPOptionsTrading();
                        isTradingActive = false;
                    }
                }

                // NEW: Check if we should end trading day early
                if (isTradingActive && !tradingDayEnded && shouldEndTradingDayEarly()) {
                    System.out.println("💰 ENDING TRADING DAY EARLY - In profit by 2:45 PM with no open positions");
                    tradingDayEnded = true;

                    if (vwapOptionsStrategy != null) {
                        vwapOptionsStrategy.stopVWAPOptionsTrading();
                        isTradingActive = false;
                    }

                    if (timer != null) {
                        timer.cancel();
                        timer = null;
                    }
                }
            }
        }, heartbeatInterval, heartbeatInterval); // Every 5 minutes

        try {
            // Keep main thread alive
            while (true) {
                Thread.sleep(300000); // Sleep for 5 minutes

                // Auto stop at end of day
                if (shouldAutoStop()) {
                    System.out.println("🕒 End of trading day detected. Stopping application...");
                    cleanup();
                    break;
                }

                // NEW: Also break if trading day ended early
                if (tradingDayEnded) {
                    System.out.println("💰 Trading day ended early (profitable by 2:45 PM). Stopping application...");
                    cleanup();
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("⏹️  Application interrupted");
        } finally {
            statusTimer.cancel();
        }
    }

    private static void displayHeartbeat() {
        System.out.println("\n❤️  APPLICATION HEARTBEAT - " + new Date());
        System.out.println("   - Uptime: " + getUptime() + " minutes");
        System.out.println("   - Trading Hours: " + (isWithinTradingHours() ? "✅ OPEN" : "❌ CLOSED"));
        System.out.println("   - VWAP Strategy: " + (isTradingActive ? "✅ ACTIVE" : "⏸️ INACTIVE"));
        System.out.println("   - Daily P&L: ₹" + String.format("%.2f", pnlManager.getTotalDailyPnL()));

        // NEW: Show early trading end status
        if (tradingDayEnded) {
            System.out.println("   - Early Trading End: ✅ ACTIVE (Profitable by 2:45 PM)");
        }

        System.out.println("   - Next Execution: in " + getTimeToNextExecution() + " minutes");

        if (!isTradingAllowed()) {
            System.out.println("   ⚠️  TRADING NOT ALLOWED - " +
                    (!isWithinTradingHours() ? "Outside trading hours" : "Daily limit reached"));
        }

        System.out.println("   " + "-".repeat(50));
    }

    private static long getUptime() {
        return (System.currentTimeMillis() - startTime) / (60 * 1000);
    }

    private static long getTimeToNextExecution() {
        Calendar now = Calendar.getInstance();
        int currentMinute = now.get(Calendar.MINUTE);
        int intervalMinutes = AppConfig.getTradingIntervalMinutes();
        int minutesToNext = intervalMinutes - (currentMinute % intervalMinutes);
        return minutesToNext == intervalMinutes ? 0 : minutesToNext;
    }

    private static boolean shouldAutoStop() {
        try {
            Calendar cal = Calendar.getInstance();
            int hour = cal.get(Calendar.HOUR_OF_DAY);
            int minute = cal.get(Calendar.MINUTE);

            // Stop application after market close (after 3:30 PM)
            return hour > 15 || (hour == 15 && minute >= 00);
        } catch (Exception e) {
            return false;
        }
    }
}