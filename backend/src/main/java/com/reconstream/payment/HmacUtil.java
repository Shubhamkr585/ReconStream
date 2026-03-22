package com.reconstream.payment;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class HmacUtil {

    private HmacUtil() {}

    public static String computeSignature(String pspTxnId, String externalOrderId, String eventType, Long amountPaise, String secret) {
        String data = (pspTxnId != null ? pspTxnId : "") + ":" +
                      (externalOrderId != null ? externalOrderId : "") + ":" +
                      (eventType != null ? eventType : "") + ":" +
                      (amountPaise != null ? amountPaise : 0);
        return calculateHmacSha256(data, secret);
    }

    public static String calculateHmacSha256(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKey);
            byte[] bytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to calculate HMAC-SHA256", e);
        }
    }

    public static boolean verifySignature(String pspTxnId, String externalOrderId, String eventType, Long amountPaise, String signature, String secret) {
        if (signature == null || secret == null) {
            return false;
        }
        String expected = computeSignature(pspTxnId, externalOrderId, eventType, amountPaise, secret);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.toLowerCase().getBytes(StandardCharsets.UTF_8))
                || MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.toUpperCase().getBytes(StandardCharsets.UTF_8));
    }
}
