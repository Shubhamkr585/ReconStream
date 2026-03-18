package com.reconstream.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class RequestHashUtil {
    private RequestHashUtil() {}

    public static String computeHash(CreatePaymentRequest request) {
        int mdr = request.expectedMdrBps() != null ? request.expectedMdrBps() : 200;
        String canonical = String.format("{\"externalOrderId\":\"%s\",\"amountPaise\":%d,\"expectedMdrBps\":%d}",
                request.externalOrderId(), request.amountPaise(), mdr);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
}
