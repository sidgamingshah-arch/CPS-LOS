package com.helix.common.util;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

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
 * regression harness work with no configuration. Production MUST set
 * {@code HELIX_FIELD_KEY}. An explicitly-set key that is not valid Base64 or not exactly
 * 32 bytes fails fast rather than silently falling back.
 */
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    /** Environment variable carrying a Base64-encoded 32-byte AES-256 key. */
    public static final String KEY_ENV = "HELIX_FIELD_KEY";

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
        String b64 = explicit ? configured.trim() : DEV_DEFAULT_KEY_B64;
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
        try {
            byte[] blob = Base64.getDecoder().decode(dbData);
            if (blob.length < MIN_BLOB_LENGTH) {
                return dbData; // too short to be one of our IV||ct||tag blobs -> legacy plaintext
            }
            byte[] iv = Arrays.copyOfRange(blob, 0, IV_LENGTH);
            byte[] ciphertext = Arrays.copyOfRange(blob, IV_LENGTH, blob.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, KEY, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Not valid Base64 / not our ciphertext / wrong key -> return stored value
            // verbatim. Tolerates already-plaintext legacy rows; a read never crashes.
            return dbData;
        }
    }
}
