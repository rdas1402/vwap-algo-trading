package com.trading.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.text.SimpleDateFormat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class AppConfig {
    private static final Properties properties = new Properties();
    private static final Set<String> holidays = new HashSet<>();
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd");

    /**
     * API Rate limiting configuration
     */
    private static final int API_CALLS_PER_MINUTE = 50; // Kite's rate limit is typically 60/min
    private static final long API_MIN_INTERVAL_MS = 60000 / API_CALLS_PER_MINUTE;
    private static long lastApiCallTime = 0;

    static {
        loadProperties();
        loadHolidays();
    }

    private static void loadProperties() {
        // 1. Try external config file (outside JAR)
        Path externalConfig = Paths.get(System.getProperty("user.home"), "vwap-algo-trading/config/application.properties");
        if (Files.exists(externalConfig)) {
            try (InputStream in = Files.newInputStream(externalConfig)) {
                properties.load(in);
                System.out.println("✅ Loaded configuration from: " + externalConfig);
                return;
            } catch (IOException e) {
                System.err.println("⚠️ Failed to load external config, falling back to classpath.");
            }
        }
        // 2. Fallback to classpath (inside JAR)
        try (InputStream in = AppConfig.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (in == null) throw new IOException("Resource not found");
            properties.load(in);
            System.out.println("✅ Loaded configuration from classpath");
        } catch (IOException ex) {
            ex.printStackTrace();
            throw new RuntimeException("Cannot load application.properties");
        }
    }

    private static void loadHolidays() {
        String holidaysStr = properties.getProperty("market.holidays", "");
        if (!holidaysStr.isEmpty()) {
            String[] holidayArray = holidaysStr.split(",");
            for (String holiday : holidayArray) {
                holidays.add(holiday.trim());
            }
            System.out.println("Loaded " + holidays.size() + " market holidays");
        } else {
            // Add default major holidays as fallback
            addDefaultHolidays();
        }
    }

    private static void addDefaultHolidays() {
        // Add common Indian market holidays for 2026
        holidays.add("2026-01-26"); // Republic Day
        holidays.add("2026-03-03"); // Holiday as mentioned
        holidays.add("2026-03-25"); // Holi
        holidays.add("2026-04-14"); // Dr. Ambedkar Jayanti
        holidays.add("2026-08-15"); // Independence Day
        holidays.add("2026-10-02"); // Gandhi Jayanti
        holidays.add("2026-11-14"); // Diwali
        holidays.add("2026-12-25"); // Christmas
        System.out.println("Using default market holidays");
    }

    // Existing configuration methods
    public static String getApiKey() {
        return properties.getProperty("zerodha.api.key");
    }

    public static String getAccessToken() {
        return properties.getProperty("zerodha.access.token");
    }

    public static String getBaseUrl() {
        return properties.getProperty("zerodha.api.baseurl");
    }

    public static String getTradingStartTime() {
        return properties.getProperty("trading.start.time", "09:15");
    }

    public static String getTradingEndTime() {
        return properties.getProperty("trading.end.time", "15:25");
    }

    public static int getTradingIntervalMinutes() {
        return Integer.parseInt(properties.getProperty("trading.interval.time", "5"));
    }

    public static double getMaxDailyLoss() {
        return Double.parseDouble(properties.getProperty("max.daily.loss", "10000"));
    }

    // VWAP Options Strategy Configuration
    public static double getVWAPOptionsTargetPrice() {
        return Double.parseDouble(properties.getProperty("vwap.options.target.price", "100.0"));
    }

    public static double getVWAPOptionsPriceTolerance() {
        return Double.parseDouble(properties.getProperty("vwap.options.price.tolerance", "30.0"));
    }

    public static int getVWAPOptionsLotSize() {
        return Integer.parseInt(properties.getProperty("vwap.options.lot.size", "50"));
    }

    public static int getVWAPOptionsMaxPositions() {
        return Integer.parseInt(properties.getProperty("vwap.options.max.positions", "2"));
    }

    public static double getVWAPOptionsStoplossMultiplier() {
        return Double.parseDouble(properties.getProperty("vwap.options.stoploss.multiplier", "2.0"));
    }

    // Buying Hours Configuration (for VWAP Strategy)
    public static String getBuyingStartTime() {
        return properties.getProperty("buying.start.time", "09:30");
    }

    public static String getBuyingEndTime() {
        return properties.getProperty("buying.end.time", "15:15");
    }

    // NEW: Holiday Management Methods

    /**
     * Check if a given date is a market holiday
     * @param date Calendar date to check
     * @return true if date is a holiday
     */
    public static boolean isHoliday(Calendar date) {
        String dateStr = DATE_FORMAT.format(date.getTime());
        return holidays.contains(dateStr);
    }

    /**
     * Check if a given date string (YYYY-MM-DD) is a market holiday
     * @param dateStr Date string in YYYY-MM-DD format
     * @return true if date is a holiday
     */
    public static boolean isHoliday(String dateStr) {
        return holidays.contains(dateStr);
    }

    /**
     * Adjust expiry date if it falls on a holiday
     * @param date Original expiry date
     * @return Adjusted date (previous working day if holiday)
     */
    public static Calendar adjustForHoliday(Calendar date) {
        Calendar adjustedDate = (Calendar) date.clone();

        while (isHoliday(adjustedDate)) {
//            System.out.println("Date " + DATE_FORMAT.format(adjustedDate.getTime()) +
//                    " is a holiday. Adjusting to previous day.");
            adjustedDate.add(Calendar.DATE, -1);
        }

        return adjustedDate;
    }

    /**
     * Get all market holidays
     * @return Unmodifiable set of holiday dates
     */
    public static Set<String> getHolidays() {
        return Collections.unmodifiableSet(holidays);
    }

    /**
     * Reload holidays from properties file
     */
    public static void reloadHolidays() {
        holidays.clear();
        loadHolidays();
    }

    /**
     * Check if today is a trading day (not a holiday and weekday)
     * @return true if today is a trading day
     */
    public static boolean isTradingDay() {
        Calendar today = Calendar.getInstance();
        int dayOfWeek = today.get(Calendar.DAY_OF_WEEK);

        // Check if weekend (Saturday or Sunday)
        if (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY) {
            return false;
        }

        // Check if holiday
        return !isHoliday(today);
    }

    /**
     * Get next trading day from given date
     * @param date Starting date
     * @return Next trading day (skipping holidays and weekends)
     */
    public static Calendar getNextTradingDay(Calendar date) {
        Calendar nextDay = (Calendar) date.clone();
        nextDay.add(Calendar.DATE, 1);

        while (!isTradingDay(nextDay)) {
            nextDay.add(Calendar.DATE, 1);
        }

        return nextDay;
    }

    /**
     * Check if a date is a trading day (not holiday and weekday)
     * @param date Date to check
     * @return true if it's a trading day
     */
    public static boolean isTradingDay(Calendar date) {
        int dayOfWeek = date.get(Calendar.DAY_OF_WEEK);

        // Check if weekend
        if (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY) {
            return false;
        }

        // Check if holiday
        return !isHoliday(date);
    }

    // Rate limiting methods
    public static synchronized void rateLimitApiCall() throws InterruptedException {
        long currentTime = System.currentTimeMillis();
        long timeSinceLastCall = currentTime - lastApiCallTime;

        if (timeSinceLastCall < API_MIN_INTERVAL_MS) {
            long sleepTime = API_MIN_INTERVAL_MS - timeSinceLastCall;
            Thread.sleep(sleepTime);
        }

        lastApiCallTime = System.currentTimeMillis();
    }

    /**
     * Get API rate limit interval in milliseconds
     */
    public static long getApiRateLimitInterval() {
        return API_MIN_INTERVAL_MS;
    }

    /**
     * Get API calls per minute
     */
    public static int getApiCallsPerMinute() {
        return API_CALLS_PER_MINUTE;
    }

    // AppConfig.java - Add these methods

    /**
     * Get target premium for option selection (default: 100)
     */
    public static double getTargetPremium() {
        try {
            return Double.parseDouble(properties.getProperty("strategy.target.premium", "100.0"));
        } catch (NumberFormatException e) {
            return 100.0;
        }
    }

    /**
     * Get premium tolerance for option selection (default: 50)
     */
    public static double getPremiumTolerance() {
        try {
            return Double.parseDouble(properties.getProperty("strategy.premium.tolerance", "50.0"));
        } catch (NumberFormatException e) {
            return 50.0;
        }
    }

    /**
     * Get premium for hammer reversal strategy (slightly lower for bottom fishing)
     */
    public static double getHammerTargetPremium() {
        try {
            return Double.parseDouble(properties.getProperty("strategy.hammer.target.premium", "100.0"));
        } catch (NumberFormatException e) {
            return 100.0;
        }
    }

    /**
     * Get premium for breakout retest strategy (slightly higher for momentum)
     */
    public static double getBreakoutTargetPremium() {
        try {
            return Double.parseDouble(properties.getProperty("strategy.breakout.target.premium", "100.0"));
        } catch (NumberFormatException e) {
            return 100.0;
        }
    }

    public static String getApiSecret() {
        return properties.getProperty("zerodha.api.secret");
    }
}