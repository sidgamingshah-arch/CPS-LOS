package com.helix.common.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;

/**
 * Minimal HMAC-signed bearer token — the platform's session credential. config-service
 * mints it on login; the gateway verifies it on every request and injects the verified
 * subject as {@code X-Actor}, so a caller can no longer assert an identity they don't
 * hold a token for.
 *
 * <p>Deliberately dependency-free (JDK crypto only, no JWT library): a compact
 * {@code base64url(payload).base64url(hmacSha256(payload))} where the payload is a
 * delimited {@code sub=<actor>&iat=<ms>&exp=<ms>}. Symmetric HMAC with a shared secret
 * is appropriate here — the same trust domain mints and verifies. Roles are NOT carried
 * in the token; they resolve from the {@code ACTOR_ROLE} directory at enforcement time so
 * the directory stays the single source of truth and a role change needs no re-issue.</p>
 *
 * <p>The gateway carries its own byte-identical verifier (it can't depend on this servlet
 * library); keep the two in lockstep if the format ever changes.</p>
 */
public final class AuthToken {

    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();

    private AuthToken() { }

    public record Claims(String subject, long issuedAtMillis, long expiresAtMillis) {
        public boolean isExpired() {
            return System.currentTimeMillis() >= expiresAtMillis;
        }
    }

    /** Mints a token for {@code subject} valid for {@code ttlSeconds}. */
    public static String mint(String secret, String subject, long ttlSeconds) {
        long now = System.currentTimeMillis();
        long exp = now + ttlSeconds * 1000L;
        String payload = "sub=" + subject + "&iat=" + now + "&exp=" + exp;
        String p = ENC.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String sig = ENC.encodeToString(hmac(secret, p));
        return p + "." + sig;
    }

    /** Verifies signature + expiry. Empty when malformed, tampered, or expired. */
    public static Optional<Claims> verify(String secret, String token) {
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
        long iat = 0, exp = 0;
        for (String kv : payload.split("&")) {
            int eq = kv.indexOf('=');
            if (eq < 0) continue;
            String k = kv.substring(0, eq), v = kv.substring(eq + 1);
            switch (k) {
                case "sub" -> sub = v;
                case "iat" -> iat = parse(v);
                case "exp" -> exp = parse(v);
                default -> { }
            }
        }
        if (sub == null || sub.isBlank() || exp == 0) return Optional.empty();
        Claims c = new Claims(sub, iat, exp);
        if (c.isExpired()) return Optional.empty();
        return Optional.of(c);
    }

    private static long parse(String v) {
        try { return Long.parseLong(v); } catch (NumberFormatException e) { return 0; }
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
