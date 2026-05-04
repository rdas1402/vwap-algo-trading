// TOTPGenerator.java
package com.trading.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * TOTP (Time-based One-Time Password) Generator for Zerodha 2FA
 * Implements RFC 6238 compatible with Google Authenticator
 */
public class TOTPGenerator {

    private static final int TIME_STEP = 30; // 30 seconds
    private static final int TOTP_LENGTH = 6; // 6-digit code
    private static final String HMAC_ALGORITHM = "HmacSHA1";

    /**
     * Generate TOTP from base32 secret
     * @param base32Secret The base32 encoded secret key
     * @return 6-digit TOTP code
     */
    public static String generateTOTP(String base32Secret) {
        try {
            // Clean the secret - remove spaces, convert to uppercase
            String cleanSecret = base32Secret.replaceAll("\\s+", "").toUpperCase();
            log("🔑 Using secret (first 4 chars): " + cleanSecret.substring(0, Math.min(4, cleanSecret.length())) + "...");

            byte[] secret = Base32Decoder.decode(cleanSecret);
            long counter = System.currentTimeMillis() / 1000 / TIME_STEP;

            byte[] challenge = ByteBuffer.allocate(8).putLong(counter).array();

            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(secret, HMAC_ALGORITHM);
            mac.init(keySpec);
            byte[] hash = mac.doFinal(challenge);

            // Dynamic truncation
            int offset = hash[hash.length - 1] & 0xF;
            int binary = ((hash[offset] & 0x7F) << 24) |
                    ((hash[offset + 1] & 0xFF) << 16) |
                    ((hash[offset + 2] & 0xFF) << 8) |
                    (hash[offset + 3] & 0xFF);

            int otp = binary % (int) Math.pow(10, TOTP_LENGTH);
            String result = String.format("%0" + TOTP_LENGTH + "d", otp);

            log("✅ Generated TOTP: " + result);
            return result;

        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log("❌ TOTP generation failed: " + e.getMessage());
            throw new RuntimeException("Failed to generate TOTP", e);
        } catch (Exception e) {
            log("❌ Unexpected error in TOTP generation: " + e.getMessage());
            throw new RuntimeException("Failed to generate TOTP", e);
        }
    }

    /**
     * Verify if a TOTP code is valid (with a window of +/- 1 time step)
     * @param secret Base32 secret
     * @param code Code to verify
     * @return true if valid
     */
    public static boolean verifyTOTP(String secret, String code) {
        // Check current time step
        String current = generateTOTP(secret);
        if (current.equals(code)) return true;

        // Check previous time step (30 seconds ago)
        long counter = (System.currentTimeMillis() / 1000 / TIME_STEP) - 1;
        String previous = generateTOTPForCounter(secret, counter);
        if (previous.equals(code)) return true;

        // Check next time step (30 seconds ahead)
        counter = (System.currentTimeMillis() / 1000 / TIME_STEP) + 1;
        String next = generateTOTPForCounter(secret, counter);
        return next.equals(code);
    }

    private static String generateTOTPForCounter(String base32Secret, long counter) {
        try {
            byte[] secret = Base32Decoder.decode(base32Secret.replaceAll("\\s+", "").toUpperCase());

            byte[] challenge = ByteBuffer.allocate(8).putLong(counter).array();

            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(secret, HMAC_ALGORITHM);
            mac.init(keySpec);
            byte[] hash = mac.doFinal(challenge);

            int offset = hash[hash.length - 1] & 0xF;
            int binary = ((hash[offset] & 0x7F) << 24) |
                    ((hash[offset + 1] & 0xFF) << 16) |
                    ((hash[offset + 2] & 0xFF) << 8) |
                    (hash[offset + 3] & 0xFF);

            int otp = binary % (int) Math.pow(10, TOTP_LENGTH);
            return String.format("%0" + TOTP_LENGTH + "d", otp);

        } catch (Exception e) {
            return null;
        }
    }

    private static void log(String message) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        System.out.println("[" + timestamp + "] " + message);
    }
}

/**
 * Base32 decoder for TOTP secrets
 * Implements RFC 4648 Base32 decoding
 */
class Base32Decoder {
    private static final String BASE32_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int[] BASE32_LOOKUP = new int[256];

    static {
        // Initialize lookup table
        for (int i = 0; i < BASE32_CHARS.length(); i++) {
            BASE32_LOOKUP[BASE32_CHARS.charAt(i)] = i;
        }
        // Also handle lowercase
        for (int i = 0; i < 26; i++) {
            BASE32_LOOKUP['a' + i] = BASE32_LOOKUP['A' + i];
        }
    }

    /**
     * Decode a Base32 string to bytes
     * @param base32 The Base32 encoded string
     * @return Decoded byte array
     */
    public static byte[] decode(String base32) {
        if (base32 == null || base32.isEmpty()) {
            throw new IllegalArgumentException("Base32 string cannot be null or empty");
        }

        // Remove padding and clean
        String clean = base32.replaceAll("=", "").toUpperCase();
        int length = clean.length();

        // Calculate output length: (input_length * 5) / 8
        byte[] bytes = new byte[length * 5 / 8];
        int buffer = 0;
        int bitsLeft = 0;
        int index = 0;

        for (int i = 0; i < length; i++) {
            char c = clean.charAt(i);
            int value;

            if (c >= 'A' && c <= 'Z') {
                value = c - 'A';
            } else if (c >= '2' && c <= '7') {
                value = c - '2' + 26; // '2' is 26, '3' is 27, etc.
            } else {
                throw new IllegalArgumentException("Invalid Base32 character: " + c);
            }

            buffer = (buffer << 5) | value;
            bitsLeft += 5;

            if (bitsLeft >= 8) {
                bytes[index++] = (byte) (buffer >> (bitsLeft - 8));
                bitsLeft -= 8;
            }
        }

        return bytes;
    }

    /**
     * Encode bytes to Base32 string
     * @param data Bytes to encode
     * @return Base32 encoded string
     */
    public static String encode(byte[] data) {
        StringBuilder result = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;

        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;

            while (bitsLeft >= 5) {
                int index = (buffer >> (bitsLeft - 5)) & 0x1F;
                result.append(BASE32_CHARS.charAt(index));
                bitsLeft -= 5;
            }
        }

        // Handle remaining bits
        if (bitsLeft > 0) {
            int index = (buffer << (5 - bitsLeft)) & 0x1F;
            result.append(BASE32_CHARS.charAt(index));

            // Add padding
            int padding = 8 - (result.length() % 8);
            if (padding < 8) {
                for (int i = 0; i < padding; i++) {
                    result.append('=');
                }
            }
        }

        return result.toString();
    }

    /**
     * Test if a string is valid Base32
     * @param base32 String to test
     * @return true if valid
     */
    public static boolean isValidBase32(String base32) {
        if (base32 == null || base32.isEmpty()) return false;
        return base32.matches("^[A-Z2-7]+=*$");
    }
}