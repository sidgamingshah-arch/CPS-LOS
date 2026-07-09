package com.helix.gateway;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;

/**
 * Gateway-side verifier for the HMAC bearer token minted by config-service. The gateway
 * is a reactive (WebFlux) module that can't depend on the servlet helix-common library,
 * so this mirrors {@code com.helix.common.auth.AuthToken} byte-for-byte (verify only —
 * the gateway never mints). Keep the two in lockstep if the token format changes.
 */
final class GatewayAuthToken {

    private static final Base64.Decoder DEC = Base64.getUrlDecoder();

    private GatewayAuthToken() { }

    record Claims(String subject, long expiresAtMillis) {
        boolean isExpired() {
            return System.currentTimeMillis() >= expiresAtMillis;
        }
    }

    static Optional<Claims> verify(String secret, String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) return Optional.empty();
        String p = token.substring(0, dot);
        String sig = token.substring(dot + 1);
        byte[] expected = hmac(secret, p);
        byte[] presented;
        try {
            presented = DEC.decode(sig);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(expected, presented)) return Optional.empty();
        String payload;
        try {
            payload = new String(DEC.decode(p), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        String sub = null;
        long exp = 0;
        for (String kv : payload.split("&")) {
            int eq = kv.indexOf('=');
            if (eq < 0) continue;
            String k = kv.substring(0, eq), v = kv.substring(eq + 1);
            if ("sub".equals(k)) sub = v;
            else if ("exp".equals(k)) {
                try { exp = Long.parseLong(v); } catch (NumberFormatException ignored) { }
            }
        }
        if (sub == null || sub.isBlank() || exp == 0) return Optional.empty();
        Claims c = new Claims(sub, exp);
        return c.isExpired() ? Optional.empty() : Optional.of(c);
    }

    private static byte[] hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC failure", e);
        }
    }
}
