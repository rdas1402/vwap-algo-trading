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
import java.util.List;
import java.util.Properties;
import java.util.regex.*;

public class AutoZerodhaLoginHelper {

    private static final String PROPERTIES_FILE_PATH = System.getProperty("user.home") + "/vwap-algo-trading/config/application.properties";
    private static final Properties APP_PROPERTIES = loadApplicationProperties();
    private static final String API_KEY = requireProperty("zerodha.api.key");
    private static final String API_SECRET = requireProperty("zerodha.api.secret");

    private static final String ZERODHA_USER_ID = System.getenv("ZERODHA_USER_ID");
    private static final String ZERODHA_PASSWORD = System.getenv("ZERODHA_PASSWORD");
    private static final String ZERODHA_PIN = System.getenv("ZERODHA_PIN");
    private static final String ZERODHA_TOTP_SECRET = System.getenv("ZERODHA_TOTP_SECRET");
    private static final By USER_ID_LOCATOR = By.cssSelector("input[name='userid'], input[name='user_id'], input#userid, input[type='text']");
    private static final By PASSWORD_LOCATOR = By.cssSelector("input[name='password'], input[type='password']");
    private static final By LOGIN_BUTTON_LOCATOR = By.xpath("//button[contains(normalize-space(),'Login')]");
    private static final By TOTP_LOCATOR = By.cssSelector("input[name='userotp'], input[name='otp'], input[autocomplete='one-time-code'], input[inputmode='numeric'], input[type='tel'], input[type='number']");
    private static final By CONTINUE_BUTTON_LOCATOR = By.xpath("//button[contains(normalize-space(),'Continue') or contains(normalize-space(),'Submit')]");
    private static final int FIELD_INTERACTION_RETRIES = 3;

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
                validateAccessToken(accessToken);
                log("✅ Login successful and token validated");
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
        options.addArguments("--user-data-dir=" + System.getProperty("user.home") + "/chrome-profile");
// Remove the maximized argument because headless doesn't support it
// options.addArguments("--start-maximized");

        WebDriver driver = new ChromeDriver(options);
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(30));

        try {
            KiteConnect kite = new KiteConnect(API_KEY);
            driver.get(kite.getLoginURL());
            log("1. Login page loaded");
            waitForDocumentReady(driver, wait);

            // User ID
            typeIntoField(driver, wait, USER_ID_LOCATOR, ZERODHA_USER_ID, "User ID");
            log("2. User ID entered");

            // Password
            typeIntoField(driver, wait, PASSWORD_LOCATOR, ZERODHA_PASSWORD, "Password");
            log("3. Password entered");

            // Login button
            clickElement(driver, wait, LOGIN_BUTTON_LOCATOR, "Login button");
            log("4. Login button clicked");

            waitForDocumentReady(driver, wait);
            waitForVisibleElement(wait, TOTP_LOCATOR, "TOTP input");

            // Generate and enter TOTP
            String totp = TOTPGenerator.generateTOTP(ZERODHA_TOTP_SECRET);
            log("5. TOTP generated: " + totp);

            typeIntoField(driver, wait, TOTP_LOCATOR, totp, "TOTP");
            log("6. TOTP entered");

            if (isElementPresent(driver, CONTINUE_BUTTON_LOCATOR)) {
                clickElement(driver, wait, CONTINUE_BUTTON_LOCATOR, "Continue button");
                log("7. Continue clicked");
            }

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
            log("   Token: " + maskToken(user.accessToken));
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

    private static void waitForDocumentReady(WebDriver driver, WebDriverWait wait) {
        wait.until(webDriver -> {
            Object readyState = ((JavascriptExecutor) driver).executeScript("return document.readyState");
            return "complete".equals(readyState) || "interactive".equals(readyState);
        });
    }

    private static WebElement waitForVisibleElement(WebDriverWait wait, By locator, String label) {
        try {
            return wait.until(ExpectedConditions.visibilityOfElementLocated(locator));
        } catch (TimeoutException e) {
            throw new TimeoutException("Timed out waiting for " + label + " using locator " + locator, e);
        }
    }

    private static void typeIntoField(WebDriver driver, WebDriverWait wait, By locator, String value, String label) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= FIELD_INTERACTION_RETRIES; attempt++) {
            try {
                WebElement element = waitForVisibleElement(wait, locator, label);
                scrollIntoView(driver, element);
                wait.until(ExpectedConditions.elementToBeClickable(element));
                focusElement(driver, element);
                clearField(driver, element);
                element.sendKeys(value);
                if (hasFieldValue(element)) {
                    return;
                }
                throw new ElementNotInteractableException(label + " field did not accept input");
            } catch (StaleElementReferenceException | ElementNotInteractableException | TimeoutException e) {
                lastFailure = new RuntimeException(label + " interaction failed on attempt " + attempt + "/" + FIELD_INTERACTION_RETRIES + ": " + e.getMessage(), e);
                sleepQuietly(750L);
            }
        }
        throw lastFailure != null ? lastFailure : new RuntimeException("Failed to interact with " + label);
    }

    private static void clickElement(WebDriver driver, WebDriverWait wait, By locator, String label) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= FIELD_INTERACTION_RETRIES; attempt++) {
            try {
                WebElement element = waitForVisibleElement(wait, locator, label);
                scrollIntoView(driver, element);
                wait.until(ExpectedConditions.elementToBeClickable(element));
                element.click();
                return;
            } catch (StaleElementReferenceException | ElementNotInteractableException | TimeoutException e) {
                lastFailure = new RuntimeException(label + " click failed on attempt " + attempt + "/" + FIELD_INTERACTION_RETRIES + ": " + e.getMessage(), e);
                WebElement fallbackElement = firstVisibleElement(driver, locator);
                if (fallbackElement != null) {
                    try {
                        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", fallbackElement);
                        return;
                    } catch (RuntimeException ignored) {
                        // Retry through the normal path after a short pause.
                    }
                }
                sleepQuietly(750L);
            }
        }
        throw lastFailure != null ? lastFailure : new RuntimeException("Failed to click " + label);
    }

    private static WebElement firstVisibleElement(WebDriver driver, By locator) {
        List<WebElement> elements = driver.findElements(locator);
        for (WebElement element : elements) {
            try {
                if (element.isDisplayed()) {
                    return element;
                }
            } catch (StaleElementReferenceException ignored) {
                // Keep scanning visible candidates.
            }
        }
        return null;
    }

    private static boolean isElementPresent(WebDriver driver, By locator) {
        return firstVisibleElement(driver, locator) != null;
    }

    private static void scrollIntoView(WebDriver driver, WebElement element) {
        ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'center',inline:'center'});", element);
    }

    private static void focusElement(WebDriver driver, WebElement element) {
        ((JavascriptExecutor) driver).executeScript("arguments[0].focus();", element);
    }

    private static void clearField(WebDriver driver, WebElement element) {
        try {
            element.clear();
        } catch (InvalidElementStateException ignored) {
            // Fall through to JavaScript clearing.
        }
        ((JavascriptExecutor) driver).executeScript("arguments[0].value='';", element);
    }

    private static boolean hasFieldValue(WebElement element) {
        String currentValue = element.getAttribute("value");
        return currentValue != null && !currentValue.isBlank();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void validateAccessToken(String accessToken) throws IOException {
        java.net.HttpURLConnection connection =
                (java.net.HttpURLConnection) new java.net.URL("https://api.kite.trade/user/profile").openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("X-Kite-Version", "3");
        connection.setRequestProperty("Authorization", "token " + API_KEY + ":" + accessToken);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);

        int statusCode = connection.getResponseCode();
        if (statusCode != 200) {
            String responseBody = readResponseBody(connection);
            throw new IOException("Token validation failed with HTTP " + statusCode + ": " + responseBody);
        }
    }

    private static String readResponseBody(java.net.HttpURLConnection connection) throws IOException {
        InputStream stream = connection.getErrorStream() != null ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) {
            return "";
        }
        try (InputStream in = stream) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static Properties loadApplicationProperties() {
        Properties props = new Properties();
        Path path = Paths.get(PROPERTIES_FILE_PATH);
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load application properties from " + path + ": " + e.getMessage(), e);
        }
        return props;
    }

    private static String requireProperty(String key) {
        String value = APP_PROPERTIES.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required property: " + key);
        }
        return value.trim();
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
