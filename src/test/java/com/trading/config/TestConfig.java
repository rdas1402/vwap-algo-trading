package com.trading.config;

import java.util.Properties;

public class TestConfig {
    private static final Properties testProperties = new Properties();
    
    static {
        // Test-specific configuration
        testProperties.setProperty("zerodha.api.key", "test_api_key");
        testProperties.setProperty("zerodha.api.secret", "test_api_secret");
        testProperties.setProperty("zerodha.access.token", "test_access_token");
        testProperties.setProperty("zerodha.api.baseurl", "https://testapi.kite.trade");
        
        // Trading configuration for testing
        testProperties.setProperty("trading.risk.per.trade", "1000");
        testProperties.setProperty("trading.stop.loss.percentage", "0.1");
        testProperties.setProperty("trading.max.positions", "2");
        testProperties.setProperty("trading.lot.size", "50");
        
        // VWAP Options specific
        testProperties.setProperty("vwap.options.target.price", "100.0");
        testProperties.setProperty("vwap.options.price.tolerance", "20.0");
        testProperties.setProperty("vwap.options.lot.size", "50");
        testProperties.setProperty("vwap.options.max.positions", "2");
        testProperties.setProperty("vwap.options.stoploss.multiplier", "2.0");
    }
    
    public static String getTestProperty(String key) {
        return testProperties.getProperty(key);
    }
}