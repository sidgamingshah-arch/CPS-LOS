package com.helix.common.dms;

import com.helix.common.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Product {@link DocumentStore} adapter for AWS S3 and S3-compatible object stores
 * (MinIO / Ceph RGW / etc.), active only when {@code helix.dms.store=s3}. Uses Spring
 * {@link RestClient} with a hand-rolled <b>AWS Signature V4</b> signer (JDK crypto only —
 * HmacSHA256 + SHA-256), so it needs <b>no external SDK dependency</b> on the shared
 * classpath. This keeps the default (filesystem) build free of the AWS SDK while still
 * shipping a real, working S3 backend a bank can switch on by config alone.
 *
 * <p>Config: {@code helix.dms.s3.endpoint} (e.g. {@code https://s3.eu-west-1.amazonaws.com}
 * or a MinIO URL), {@code helix.dms.s3.region}, {@code helix.dms.s3.bucket},
 * {@code helix.dms.s3.access-key}, {@code helix.dms.s3.secret-key}, {@code helix.dms.s3.path-style}
 * (default {@code true} — path-style addressing, required by most S3-compatible stores).</p>
 *
 * <p>This class is never on the default code path and is not exercised by the local regression
 * (no S3 endpoint is available there); the filesystem store remains the safe default.</p>
 */
@Component
@ConditionalOnProperty(name = "helix.dms.store", havingValue = "s3")
public class S3DocumentStore implements DocumentStore {

    private static final Logger log = LoggerFactory.getLogger(S3DocumentStore.class);
    private static final String SERVICE = "s3";
    private static final String ALGO = "AWS4-HMAC-SHA256";
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter SCOPE_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final RestClient http = RestClient.builder().build();
    private final String endpoint;
    private final String region;
    private final String bucket;
    private final String accessKey;
    private final String secretKey;
    private final boolean pathStyle;

    public S3DocumentStore(@Value("${helix.dms.s3.endpoint:}") String endpoint,
                           @Value("${helix.dms.s3.region:us-east-1}") String region,
                           @Value("${helix.dms.s3.bucket:}") String bucket,
                           @Value("${helix.dms.s3.access-key:}") String accessKey,
                           @Value("${helix.dms.s3.secret-key:}") String secretKey,
                           @Value("${helix.dms.s3.path-style:true}") boolean pathStyle) {
        this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.region = region;
        this.bucket = bucket;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.pathStyle = pathStyle;
        if (endpoint.isBlank() || bucket.isBlank()) {
            log.warn("DMS S3 store enabled but endpoint/bucket not configured — uploads will fail until set");
        } else {
            log.info("DMS S3 store enabled — endpoint={} bucket={} region={} pathStyle={}",
                    this.endpoint, bucket, region, pathStyle);
        }
    }

    @Override
    public String backend() {
        return "S3";
    }

    @Override
    public PutResult put(String storageKey, byte[] content, String contentType) {
        URI uri = objectUri(storageKey);
        String ct = contentType == null || contentType.isBlank() ? MediaType.APPLICATION_OCTET_STREAM_VALUE : contentType;
        try {
            var resp = http.put().uri(uri)
                    .headers(h -> signedHeaders(h::set, "PUT", uri, content, ct))
                    .body(content)
                    .retrieve().toBodilessEntity();
            String etag = resp.getHeaders().getETag();
            return new PutResult("s3://" + bucket + "/" + storageKey, etag == null ? "s3:" + storageKey : etag);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "S3 put failed for " + storageKey + ": " + e.getMessage());
        }
    }

    @Override
    public byte[] get(String storageKey) {
        URI uri = objectUri(storageKey);
        try {
            byte[] body = http.get().uri(uri)
                    .headers(h -> signedHeaders(h::set, "GET", uri, new byte[0], null))
                    .retrieve().body(byte[].class);
            if (body == null) throw ApiException.notFound("S3 object empty for key " + storageKey);
            return body;
        } catch (ApiException ae) {
            throw ae;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "S3 get failed for " + storageKey + ": " + e.getMessage());
        }
    }

    private URI objectUri(String storageKey) {
        String path = pathStyle ? "/" + bucket + "/" + storageKey : "/" + storageKey;
        String base = pathStyle ? endpoint : endpoint.replaceFirst("://", "://" + bucket + ".");
        return URI.create(base + path);
    }

    /**
     * Computes AWS SigV4 headers (host, x-amz-date, x-amz-content-sha256, authorization) for a
     * single-chunk signed-payload request and applies them via {@code setter}. Content-Type is
     * signed for PUT so the object is stored with the intended type.
     */
    private void signedHeaders(HeaderSetter setter, String method, URI uri, byte[] body, String contentType) {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        String amzDate = AMZ_DATE.format(now);
        String scopeDate = SCOPE_DATE.format(now);
        String host = uri.getPort() < 0 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
        String payloadHash = hex(sha256(body));

        StringBuilder canonicalHeaders = new StringBuilder();
        StringBuilder signedHeaders = new StringBuilder();
        boolean signContentType = contentType != null && !contentType.isBlank();
        if (signContentType) {
            canonicalHeaders.append("content-type:").append(contentType.trim()).append('\n');
            signedHeaders.append("content-type;");
        }
        canonicalHeaders.append("host:").append(host).append('\n')
                .append("x-amz-content-sha256:").append(payloadHash).append('\n')
                .append("x-amz-date:").append(amzDate).append('\n');
        signedHeaders.append("host;x-amz-content-sha256;x-amz-date");

        String canonicalRequest = method + "\n"
                + canonicalUri(uri.getRawPath()) + "\n"
                + "" + "\n"                                 // no query string on object PUT/GET
                + canonicalHeaders + "\n"
                + signedHeaders + "\n"
                + payloadHash;

        String scope = scopeDate + "/" + region + "/" + SERVICE + "/aws4_request";
        String stringToSign = ALGO + "\n" + amzDate + "\n" + scope + "\n" + hex(sha256(utf8(canonicalRequest)));

        byte[] signingKey = hmac(hmac(hmac(hmac(utf8("AWS4" + secretKey), scopeDate), region), SERVICE), "aws4_request");
        String signature = hex(hmac(signingKey, stringToSign));

        String authorization = ALGO + " Credential=" + accessKey + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;

        setter.set("Host", host);
        setter.set("x-amz-date", amzDate);
        setter.set("x-amz-content-sha256", payloadHash);
        setter.set("Authorization", authorization);
        if (signContentType) setter.set("Content-Type", contentType.trim());
    }

    /** URI-encode each path segment per RFC 3986 (S3 rules), leaving the segment separators intact. */
    private static String canonicalUri(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) return "/";
        String[] segs = rawPath.split("/", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segs.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(rfc3986(segs[i]));
        }
        return sb.toString();
    }

    private static String rfc3986(String s) {
        StringBuilder out = new StringBuilder();
        for (byte b : utf8(s)) {
            int c = b & 0xff;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                out.append((char) c);
            } else {
                out.append('%').append(String.format("%02X", c));
            }
        }
        return out.toString();
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(utf8(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        return sb.toString();
    }

    @FunctionalInterface
    private interface HeaderSetter {
        void set(String name, String value);
    }
}
