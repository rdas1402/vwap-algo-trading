// AutoZerodhaLoginHelper.java
package com.trading.util;

import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.User;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.Properties;
import java.util.regex.*;

public class AutoZerodhaLoginHelper {

    private static final String API_KEY = "e85ngsrd7lekw61v";
    private static final String API_SECRET = "oqm11fvqp3rwdsvbwicaeo5jpahizvzk";
    private static final String PROPERTIES_FILE_PATH = System.getProperty("user.home") + "/vwap-algo-trading/config/application.properties";

    private static final String ZERODHA_USER_ID = System.getenv("ZERODHA_USER_ID");
    private static final String ZERODHA_PASSWORD = System.getenv("ZERODHA_PASSWORD");
    private static final String ZERODHA_PIN = System.getenv("ZERODHA_PIN");
    private static final String ZERODHA_TOTP_SECRET = System.getenv("ZERODHA_TOTP_SECRET");

    static {
        new File("logs").mkdirs();
        new File("screenshots").mkdirs();
    }

    public static void main(String[] args) {
        try {
            log("=".repeat(60));
            log("🚀 ZERODHA AUTOMATED LOGIN");
            log("=".repeat(60));

            if (ZERODHA_USER_ID == null || ZERODHA_PASSWORD == null ||
                    ZERODHA_PIN == null || ZERODHA_TOTP_SECRET == null) {
                log("❌ Missing environment variables!");
                System.exit(1);
            }

            boolean isTest = args.length > 0 && args[0].equals("test");
            if (!isTest) waitUntil1220AM();

            String accessToken = performLogin();

            if (accessToken != null) {
                updatePropertiesFile(accessToken);
                log("✅ Login successful with accessToken: "+ accessToken);
//                startTradingApplication();
                log("✅ Token saved. Trading will start at scheduled time.");
            }

        } catch (Exception | KiteException e) {
            log("❌ Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static String performLogin() throws Exception, KiteException {
        log("Starting login process...");

        ChromeOptions options = new ChromeOptions();
// Headless mode for EC2
        options.addArguments("--headless=new");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--disable-gpu");
        options.addArguments("--disable-blink-features=AutomationControlled");
        options.addArguments("--window-size=1920,1080");
        options.addArguments("--user-data-dir=/home/ec2-user/chrome-profile");
// Remove the maximized argument because headless doesn't support it
// options.addArguments("--start-maximized");

        WebDriver driver = new ChromeDriver(options);
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(30));

        try {
            KiteConnect kite = new KiteConnect(API_KEY);
            driver.get(kite.getLoginURL());
            log("1. Login page loaded");

            // User ID
            Thread.sleep(2000);
            WebElement userId = wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("input[type='text']")));
            userId.sendKeys(ZERODHA_USER_ID);
            log("2. User ID entered");

            // Password
            WebElement password = driver.findElement(By.cssSelector("input[type='password']"));
            password.sendKeys(ZERODHA_PASSWORD);
            log("3. Password entered");

            // Login button
            WebElement loginBtn = driver.findElement(By.xpath("//button[contains(text(),'Login')]"));
            loginBtn.click();
            log("4. Login button clicked");

            // TOTP screen
            Thread.sleep(3000);

            // Generate and enter TOTP
            String totp = TOTPGenerator.generateTOTP(ZERODHA_TOTP_SECRET);
            log("5. TOTP generated: " + totp);

            WebElement totpInput = wait.until(ExpectedConditions.presenceOfElementLocated(By.tagName("input")));
            totpInput.sendKeys(totp);
            log("6. TOTP entered");

            // Click Continue - this will trigger the redirect to localhost
//            WebElement continueBtn = driver.findElement(By.xpath("//button[contains(text(),'Continue')]"));
//            continueBtn.click();
//            log("7. Continue clicked - waiting for redirect...");

            // CRITICAL: Wait for the URL to change to localhost with request_token
            // We need to capture the URL before the page fails to load
            String requestToken = null;
            long startTime = System.currentTimeMillis();
            long timeout = 10000; // 10 seconds

            while (System.currentTimeMillis() - startTime < timeout && requestToken == null) {
                String currentUrl = driver.getCurrentUrl();
                log("   Checking URL: " + currentUrl);

                // Look for request_token in the URL
                if (currentUrl.contains("request_token=")) {
                    requestToken = extractToken(currentUrl);
                    log("✅ Found request_token in URL!");
                    break;
                }

                // Also check if we're on the localhost page (the URL will have the token)
                if (currentUrl.contains("localhost:8080")) {
                    requestToken = extractToken(currentUrl);
                    if (requestToken != null) break;
                }

                Thread.sleep(500);
            }

            if (requestToken == null) {
                // Take screenshot of what page we're on
                takeScreenshot(driver, "token_not_found");
                throw new Exception("Could not extract request_token. Current URL: " + driver.getCurrentUrl());
            }

            log("8. Request token obtained: " + maskToken(requestToken));

            // Generate access token
            User user = kite.generateSession(requestToken, API_SECRET);
            log("9. Session generated for user: " + user.userId);

            // Add these logs:
            log("========================================");
            log("🔑 ACCESS TOKEN GENERATED:");
            log("   Token: " + user.accessToken);
            log("   User ID: " + user.userId);
            log("========================================");

            return user.accessToken;

        } finally {
            Thread.sleep(2000);
            driver.quit();
            log("Browser closed");
        }
    }

    private static String extractToken(String url) {
        // Pattern to extract request_token from URL
        Pattern p = Pattern.compile("request_token=([a-zA-Z0-9]+)");
        Matcher m = p.matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private static void updatePropertiesFile(String token) throws IOException {
        Path path = Paths.get(PROPERTIES_FILE_PATH);
        Properties props = new Properties();

        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        }

        props.setProperty("zerodha.access.token", token);

        try (OutputStream out = Files.newOutputStream(path)) {
            props.store(out, "Updated at " + new java.util.Date());
        }

        log("✅ Properties file updated");
    }

    private static void startTradingApplication() {
        try {
            String cp = System.getProperty("java.class.path");
            new ProcessBuilder("java", "-cp", cp, "com.trading.TradingApplication")
                    .inheritIO()
                    .start();
            log("Trading application started");
        } catch (IOException e) {
            log("Could not start trading app: " + e.getMessage());
        }
    }

    private static void waitUntil1220AM() throws InterruptedException {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int hour = cal.get(java.util.Calendar.HOUR_OF_DAY);
        int minute = cal.get(java.util.Calendar.MINUTE);

        if (hour < 0 || (hour == 0 && minute < 20)) {
            java.util.Calendar target = java.util.Calendar.getInstance();
            target.set(java.util.Calendar.HOUR_OF_DAY, 0);
            target.set(java.util.Calendar.MINUTE, 20);
            target.set(java.util.Calendar.SECOND, 0);

            long wait = target.getTimeInMillis() - System.currentTimeMillis();
            if (wait > 0) {
                log("Waiting " + (wait / 60000) + " minutes until 12:20 AM");
                Thread.sleep(wait);
            }
        }
    }

    private static void takeScreenshot(WebDriver driver, String name) {
        if (driver == null) return;
        try {
            File screenshot = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
            String filename = "screenshots/" + name + "_" +
                    new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date()) + ".png";
            Files.copy(screenshot.toPath(), Paths.get(filename));
            log("📸 Screenshot saved: " + filename);
        } catch (Exception e) {
            log("Could not save screenshot: " + e.getMessage());
        }
    }

    private static void log(String msg) {
        String ts = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date());
        System.out.println("[" + ts + "] " + msg);
    }

    private static String maskToken(String token) {
        if (token == null || token.length() < 8) return "****";
        return token.substring(0, 4) + "..." + token.substring(token.length() - 4);
    }
}