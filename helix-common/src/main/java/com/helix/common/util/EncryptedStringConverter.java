package com.helix.common.util;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Transparent, at-rest field-level encryption for genuinely-sensitive free-text PII
 * columns (UBO / beneficial-owner names, screening match names + rationale, KYC/
 * disposition remarks, collateral- and covenant-extraction grounding text, review
 * notes). Apply <em>only</em> to columns that never participate in a repository query
 * predicate, dedup, join, ordering, unique constraint or index — AES-GCM is
 * non-deterministic, so an encrypted column can no longer be matched by equality.
 *
 * <p>Usage on an entity field (never auto-applied — opt in per column):
 * <pre>{@code
 *   @Convert(converter = EncryptedStringConverter.class)
 *   private String dispositionNote;
 * }</pre>
 *
 * <p><b>Algorithm.</b> AES-256/GCM/NoPadding. On write a fresh 12-byte IV is generated,
 * prepended to the ciphertext+tag and the whole blob is Base64-encoded (the stored TEXT).
 * On read the blob is Base64-decoded and GCM-decrypted.
 *
 * <p><b>Null / blank passthrough.</b> {@code null} and blank values are stored verbatim
 * (never encrypted) so optional fields and empty strings behave exactly as before.
 *
 * <p><b>Legacy / mixed-data tolerance.</b> Decryption never throws on read: if the stored
 * value is not valid Base64, is too short to be one of our blobs, or fails the GCM
 * authentication tag (i.e. it is a pre-existing <em>plaintext</em> value written before
 * this converter was applied, or was encrypted under a different key), the raw stored
 * value is returned unchanged. This makes the converter safe to switch on over an
 * existing database and guarantees a read can never crash.
 *
 * <p><b>Key.</b> Read once from the {@code HELIX_FIELD_KEY} environment variable — a
 * Base64-encoded 32-byte (AES-256) key. When it is unset the {@linkplain
 * #DEV_DEFAULT_KEY_B64 documented dev-default key} is used so local dev and the
 * regression harness work with no configuration, and a loud one-time WARN banner is logged
 * (the built-in key is public and provides no confidentiality). To prevent a silent
 * false at-rest assurance in production, an unset {@code HELIX_FIELD_KEY} while a
 * <em>production</em> profile is active ({@code SPRING_PROFILES_ACTIVE} contains
 * {@code prod}/{@code production}) <b>fails closed</b> at startup — unless the operator
 * explicitly opts in to the dev key with {@code HELIX_ALLOW_DEV_KEY=true}. An explicitly-set
 * key that is not valid Base64 or not exactly 32 bytes fails fast rather than silently
 * falling back.
 */
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private static final Logger log = LoggerFactory.getLogger(EncryptedStringConverter.class);

    /** Environment variable carrying a Base64-encoded 32-byte AES-256 key. */
    public static final String KEY_ENV = "HELIX_FIELD_KEY";

    /** Opt-in that permits the built-in dev key even under a production profile ({@code true}). */
    public static final String ALLOW_DEV_KEY_ENV = "HELIX_ALLOW_DEV_KEY";

    /**
     * Documented dev-default key used only when {@link #KEY_ENV} is unset — it is the
     * Base64 of the 32 ASCII bytes {@code "helix-dev-field-key-not-for-prod"}. This keeps
     * dev + regression working with zero config. It is intentionally well-known and MUST
     * be overridden in any real deployment by setting {@link #KEY_ENV}.
     */
    public static final String DEV_DEFAULT_KEY_B64 = "aGVsaXgtZGV2LWZpZWxkLWtleS1ub3QtZm9yLXByb2Q=";

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;          // 96-bit IV recommended for GCM
    private static final int GCM_TAG_BITS = 128;      // 16-byte authentication tag
    private static final int MIN_BLOB_LENGTH = IV_LENGTH + (GCM_TAG_BITS / 8);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final SecretKeySpec KEY = loadKey();

    private static SecretKeySpec loadKey() {
        String configured = System.getenv(KEY_ENV);
        boolean explicit = configured != null && !configured.isBlank();
        if (explicit) {
            return decodeKey(configured.trim());
        }
        // No key configured — the built-in, publicly-known dev-default would be used.
        // Fail closed under a production profile unless the operator explicitly opts in.
        if (isProductionProfile() && !devKeyOptIn()) {
            throw new IllegalStateException(KEY_ENV + " is not set but a production profile is active "
                    + "(SPRING_PROFILES_ACTIVE); refusing to start with the built-in, publicly-known dev "
                    + "field key (false at-rest assurance). Set " + KEY_ENV + " to a Base64-encoded 32-byte "
                    + "AES-256 key, or explicitly opt in with " + ALLOW_DEV_KEY_ENV + "=true (NOT for production).");
        }
        log.warn("============================================================================");
        log.warn("{} is not set — using the BUILT-IN DEV FIELD KEY for at-rest field encryption.", KEY_ENV);
        log.warn("This key is public (documented dev-default) and provides NO confidentiality.");
        log.warn("NOT for production — set {} (Base64 32-byte AES-256 key) in any real deployment.", KEY_ENV);
        log.warn("============================================================================");
        return decodeKey(DEV_DEFAULT_KEY_B64);
    }

    private static SecretKeySpec decodeKey(String b64) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    KEY_ENV + " must be a Base64-encoded 32-byte AES key", e);
        }
        if (key.length != 32) {
            throw new IllegalStateException(
                    KEY_ENV + " must decode to exactly 32 bytes (AES-256); got " + key.length);
        }
        return new SecretKeySpec(key, "AES");
    }

    /** True when a production Spring profile is active (SPRING_PROFILES_ACTIVE contains prod/production). */
    private static boolean isProductionProfile() {
        String profiles = System.getenv("SPRING_PROFILES_ACTIVE");
        return profiles != null && profiles.toLowerCase().contains("prod");
    }

    /** True when the operator has explicitly opted in to the dev key ({@code HELIX_ALLOW_DEV_KEY=true}). */
    private static boolean devKeyOptIn() {
        String v = System.getenv(ALLOW_DEV_KEY_ENV);
        return v != null && "true".equalsIgnoreCase(v.trim());
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        // Null / blank pass through unencrypted so optional fields are untouched.
        if (attribute == null || attribute.isBlank()) {
            return attribute;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, KEY, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(attribute.getBytes(StandardCharsets.UTF_8));
            byte[] blob = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, blob, 0, iv.length);
            System.arraycopy(ciphertext, 0, blob, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(blob);
        } catch (Exception e) {
            // Encryption is a hard requirement on write — surface a misconfiguration.
            throw new IllegalStateException("Field encryption failed", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return dbData;
        }
        byte[] blob;
        try {
            blob = Base64.getDecoder().decode(dbData);
        } catch (IllegalArgumentException e) {
            // Not valid Base64 -> a pre-existing plaintext value written before this converter
            // was applied. Expected legacy path; stay silent and return it verbatim.
            return dbData;
        }
        if (blob.length < MIN_BLOB_LENGTH) {
            // Too short to be one of our IV||ct||tag blobs -> legacy plaintext. Expected; silent.
            return dbData;
        }
        try {
            byte[] iv = Arrays.copyOfRange(blob, 0, IV_LENGTH);
            byte[] ciphertext = Arrays.copyOfRange(blob, IV_LENGTH, blob.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, KEY, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Valid-length Base64 that FAILED GCM authentication — not a plausible legacy-plaintext
            // (which would rarely be valid Base64 of this length). Signals a key mismatch or
            // tampering, so log a WARN. Still return the stored value verbatim; a read never crashes.
            log.warn("field decryption failed the GCM authentication tag on a {}-byte at-rest blob "
                    + "(key mismatch or tampering?) — returning the stored value verbatim", blob.length);
            return dbData;
        }
    }
}
