// ZerodhaLoginHelper.java
package com.trading.util;

import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.User;

import java.io.IOException;
import java.util.Scanner;

public class ZerodhaLoginHelper {
    
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        
        String apiKey = "e85ngsrd7lekw61v";
        
        String apiSecret = "oqm11fvqp3rwdsvbwicaeo5jpahizvzk";
        
        try {
            KiteConnect kiteConnect = new KiteConnect(apiKey);
            
            // Generate fresh login URL
            String loginUrl = kiteConnect.getLoginURL();
            
            System.out.println("\n" + "=".repeat(60));
            System.out.println("🚀 ZERODHA LOGIN PROCESS");
            System.out.println("=".repeat(60));
            System.out.println("\n📋 STEP 1: OPEN THIS URL IN YOUR BROWSER (DO IT NOW):");
            System.out.println(loginUrl);
            System.out.println("\n📋 STEP 2: LOGIN WITH YOUR ZERODHA CREDENTIALS");
            System.out.println("📋 STEP 3: AFTER LOGIN, YOU'LL BE REDIRECTED");
            System.out.println("📋 STEP 4: COPY THE request_token FROM THE URL BAR");
            System.out.println("\n📍 The URL will look like:");
            System.out.println("https://localhost:8080/?action=login&status=success&request_token=XXXXXXXXXX");
            System.out.println("\n📍 Copy only the request_token value (the XXXXXXXXXX part)");
            System.out.println("\n📋 STEP 5: PASTE THE REQUEST TOKEN HERE:");
            System.out.print("➡️  Request Token: ");
            
            String requestToken = scanner.nextLine().trim();
            
            // Remove any extra characters if user copied full URL
            if (requestToken.contains("request_token=")) {
                requestToken = requestToken.substring(requestToken.indexOf("request_token=") + 14);
                if (requestToken.contains("&")) {
                    requestToken = requestToken.substring(0, requestToken.indexOf("&"));
                }
            }
            
            System.out.println("\n⏳ Generating access token...");
            
            // Generate access token
            User user = kiteConnect.generateSession(requestToken, apiSecret);
            
            System.out.println("\n" + "✅".repeat(30));
            System.out.println("🎉 LOGIN SUCCESSFUL!");
            System.out.println("✅".repeat(30));
            System.out.println("\n📊 YOUR CREDENTIALS:");
            System.out.println("┌─────────────────┬────────────────────────────────────────────┐");
            System.out.printf ("│ %-15s │ %-42s │\n", "Access Token", user.accessToken);
            System.out.printf ("│ %-15s │ %-42s │\n", "User ID", user.userId);
            System.out.printf ("│ %-15s │ %-42s │\n", "Public Token", user.publicToken);
            System.out.println("└─────────────────┴────────────────────────────────────────────┘");
            
            System.out.println("\n📝 UPDATE YOUR application.properties FILE:");
            System.out.println("```properties");
            System.out.println("zerodha.api.key=" + apiKey);
            System.out.println("zerodha.api.secret=" + apiSecret);
            System.out.println("zerodha.access.token=" + user.accessToken);
            System.out.println("```");
            
            System.out.println("\n💡 The access token is valid until you logout from Kite.");
            
        } catch (KiteException e) {
            System.err.println("\n❌ LOGIN FAILED!");
            System.err.println("Error: " + e.message);
            System.err.println("Code: " + e.code);
            
            if (e.code == 403) {
                System.err.println("\n🔍 Troubleshooting:");
                System.err.println("• Request token expired - generate a new one");
                System.err.println("• Invalid API Secret - check in Zerodha console");
                System.err.println("• Login not completed within time limit");
            }
        } catch (IOException e) {
            System.err.println("🌐 Network error: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("💥 Unexpected error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            scanner.close();
        }
    }
}