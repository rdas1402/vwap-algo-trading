// AccessTokenGenerator.java
package com.trading.util;

import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.User;

import java.io.IOException;
import java.util.Scanner;

public class AccessTokenGenerator {

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        // Your API credentials
        String apiKey = "e85ngsrd7lekw61v";

        String apiSecret = "oqm11fvqp3rwdsvbwicaeo5jpahizvzk";

        String requestToken = "dc4phKzIQlI151Oj0hJfsvYWXpn1Geqz";

        try {
            // Create KiteConnect instance
            KiteConnect kiteConnect = new KiteConnect(apiKey);

            // Generate session using request token AND api secret
            User user = kiteConnect.generateSession(requestToken, apiSecret);

            // Get the access token
            String accessToken = user.accessToken;

            System.out.println("\n=== ACCESS TOKEN GENERATED SUCCESSFULLY ===");
            System.out.println("Access Token: " + accessToken);
            System.out.println("User ID: " + user.userId);
            System.out.println("Public Token: " + user.publicToken);
            System.out.println("Login Time: " + user.loginTime);
            System.out.println("\nUpdate your application.properties with:");
            System.out.println("zerodha.access.token=" + accessToken);

        } catch (KiteException e) {
            System.err.println("KiteException: " + e.getMessage());
            System.err.println("Error Code: " + e.code);
            System.err.println("Error Message: " + e.message);

            if (e.code == 403) {
                System.err.println("\nPossible reasons:");
                System.err.println("1. Invalid API Secret");
                System.err.println("2. Request token expired (valid for few minutes only)");
                System.err.println("3. Incorrect API Key");
            }
        } catch (IOException e) {
            System.err.println("IOException: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("Unexpected error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            scanner.close();
        }
    }
}