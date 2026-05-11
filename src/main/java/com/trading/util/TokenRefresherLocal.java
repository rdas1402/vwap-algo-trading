package com.trading.util;

import com.warrenstrange.googleauth.GoogleAuthenticator;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.User;
import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.Properties;

public class TokenRefresherLocal {

    public static void main(String[] args) throws Exception {
        // Check required environment variables
        String userId = System.getenv("ZERODHA_USER_ID");
        String password = System.getenv("ZERODHA_PASSWORD");
        String totpSecret = System.getenv("ZERODHA_TOTP_SECRET");

        if (userId == null || password == null || totpSecret == null) {
            System.err.println("❌ Missing environment variables. Please set:");
            System.err.println("   ZERODHA_USER_ID, ZERODHA_PASSWORD, ZERODHA_TOTP_SECRET");
            System.exit(1);
        }

        WebDriverManager.chromedriver().setup();

        ChromeOptions options = new ChromeOptions();
        // Keep visible for debugging
        options.addArguments("--headless");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--no-sandbox");

        WebDriver driver = new ChromeDriver(options);
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(30));

        try {
            String apiKey = "e85ngsrd7lekw61v";
            String loginUrl = new KiteConnect(apiKey).getLoginURL();
            driver.get(loginUrl);

            // --- USER ID ---
            By userIdLocator = By.cssSelector("input[name='userid'], input[name='user_id'], input#userid");
            WebElement userIdField = wait.until(ExpectedConditions.presenceOfElementLocated(userIdLocator));
            userIdField.sendKeys(userId);

            // --- PASSWORD ---
            By passwordLocator = By.cssSelector("input[name='password'], input[type='password']");
            WebElement passwordField = driver.findElement(passwordLocator);
            passwordField.sendKeys(password);

            // --- LOGIN BUTTON (XPath) ---
            By loginButtonXPath = By.xpath("//button[contains(text(),'Login')]");
            WebElement loginButton = wait.until(ExpectedConditions.elementToBeClickable(loginButtonXPath));
            loginButton.click();

            // --- TOTP FIELD ---
            By totpLocator = By.cssSelector("input[name='userotp'], input[name='otp'], input[placeholder*='OTP']");
            WebElement totpField = wait.until(ExpectedConditions.presenceOfElementLocated(totpLocator));

            GoogleAuthenticator gAuth = new GoogleAuthenticator();
            int code = gAuth.getTotpPassword(totpSecret);
            String totp = String.format("%06d", code);
            totpField.sendKeys(totp);

            // --- SUBMIT OTP BUTTON (XPath) ---
            By submitOtpButtonXPath = By.xpath("//button[contains(text(),'Submit')]");
            WebElement submitButton = wait.until(ExpectedConditions.elementToBeClickable(submitOtpButtonXPath));
            submitButton.click();

            // --- WAIT FOR REDIRECT AND CAPTURE request_token ---
            wait.until(ExpectedConditions.urlContains("request_token="));
            String currentUrl = driver.getCurrentUrl();
            String requestToken = extractRequestToken(currentUrl);
            System.out.println("✅ Extracted request_token: " + requestToken);

            // --- EXCHANGE FOR ACCESS TOKEN ---
            KiteConnect kite = new KiteConnect(apiKey);
            String apiSecret = "oqm11fvqp3rwdsvbwicaeo5jpahizvzk";
            User user = kite.generateSession(requestToken, apiSecret);

            // --- UPDATE application.properties ---
            updatePropertiesFile(user.accessToken);
            System.out.println("✅ Access token saved. New token: " + user.accessToken);

        } catch (KiteException e) {
            throw new RuntimeException(e);
        } finally {
            driver.quit();
        }
    }

    private static String extractRequestToken(String url) {
        if (url.contains("request_token=")) {
            String token = url.split("request_token=")[1];
            if (token.contains("&")) token = token.split("&")[0];
            return token;
        }
        throw new RuntimeException("request_token not found in URL: " + url);
    }

    private static void updatePropertiesFile(String accessToken) throws Exception {
        String path = "src/main/resources/application.properties";
        Properties props = new Properties();
        try (InputStream input = new FileInputStream(path)) {
            props.load(input);
        }
        props.setProperty("zerodha.access.token", accessToken);
        try (FileOutputStream output = new FileOutputStream(path)) {
            props.store(output, "Updated access token");
        }
    }
}