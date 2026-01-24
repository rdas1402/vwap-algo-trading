package com.trading.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class AppConfig {
    private static final Properties properties = new Properties();

    /**
     * API Rate limiting configuration
     */
    private static final int API_CALLS_PER_MINUTE = 50; // Kite's rate limit is typically 60/min
    private static final long API_MIN_INTERVAL_MS = 60000 / API_CALLS_PER_MINUTE;
    private static long lastApiCallTime = 0;

    static {
        try (InputStream input = AppConfig.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (input == null) {
                System.out.println("Sorry, unable to find application.properties");
            }
            properties.load(input);
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

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
        return Double.parseDouble(properties.getProperty("max.daily.loss"));
    }

    // VWAP Options Strategy Configuration
    public static double getVWAPOptionsTargetPrice() {
        return Double.parseDouble(properties.getProperty("vwap.options.target.price", "100.0"));
    }

    public static double getVWAPOptionsPriceTolerance() {
        return Double.parseDouble(properties.getProperty("vwap.options.price.tolerance", "30.0"));
    }

    public static int getVWAPOptionsLotSize() {
        return Integer.parseInt(properties.getProperty("vwap.options.lot.size"));
    }

    public static int getVWAPOptionsMaxPositions() {
        return Integer.parseInt(properties.getProperty("vwap.options.max.positions", "2"));
    }

    public static double getVWAPOptionsStoplossMultiplier() {
        return Double.parseDouble(properties.getProperty("vwap.options.stoploss.multiplier", "2.0"));
    }

    // Buying Hours Configuration (for VWAP Strategy)
    public static String getBuyingStartTime() {
        return properties.getProperty("buying.start.time");
    }

    public static String getBuyingEndTime() {
        return properties.getProperty("buying.end.time", "15:15");
    }

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
}