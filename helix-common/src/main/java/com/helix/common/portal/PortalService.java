package com.helix.common.portal;

import com.helix.common.audit.AuditService;
import com.helix.common.dms.DocumentStoreService;
import com.helix.common.dms.StoredDocument;
import com.helix.common.query.QueryChannel;
import com.helix.common.query.QueryMessage;
import com.helix.common.query.QueryMessageRepository;
import com.helix.common.query.QueryStatus;
import com.helix.common.query.QueryThread;
import com.helix.common.query.QueryThreadRepository;
import com.helix.common.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Token-scoped customer / vendor SELF-SERVICE portal (CLoM gap #23; the R1-02 / R1-14 / R3-08
 * "response by customer" rows). Automatically present in every service that includes helix-common
 * (like {@code /api/audit}, {@code /api/queries}, {@code /api/documents}) via the exposed
 * {@code /api/portal} controller.
 *
 * <p>An external party never authenticates: the ONLY credential is the one-time response token
 * minted for their RFI at raise/dispatch time (see {@code QueryService#dispatchExternal} — the
 * raw token rides out embedded in the RFI notification's tokenised callback link, and its SHA-256
 * hash is the sole thing persisted on the {@link QueryThread}). Presenting that token here lets the
 * party (a) VIEW the single RFI they were sent, (b) RESPOND with a message, and (c) UPLOAD a
 * document through the existing governed DMS store — all scoped STRICTLY to that one thread.</p>
 *
 * <h3>Security posture</h3>
 * <ul>
 *   <li><b>One token → exactly one thread (no IDOR).</b> The token is the lookup key
 *       ({@code findByResponseTokenHash}); there is no thread id in any path, so a token for
 *       thread A can never address thread B. There is deliberately NO list endpoint.</li>
 *   <li><b>No data leak on denial.</b> An invalid / unknown / closed token is denied with a
 *       generic message — the response body never carries the topic, question, message log,
 *       reference or the token of any thread.</li>
 *   <li><b>Read is safe + idempotent.</b> {@link #context} does not consume the token and writes
 *       nothing; the token stays usable for respond/upload until the bank resolves/cancels the
 *       thread (a terminal thread closes the portal). This deliberately differs from the legacy
 *       single-use {@code external-response} callback, which is left byte-identical.</li>
 *   <li><b>External, never internal-human.</b> Every portal write is stamped
 *       {@code audit.external(...)} (actorType {@code EXTERNAL}) and the appended message carries
 *       authorType {@code EXTERNAL} — a token-bearer is never recorded as a named internal human.</li>
 * </ul>
 */
@Service
public class PortalService {

    private static final Logger log = LoggerFactory.getLogger(PortalService.class);
    private static final String SUBJECT = "QueryThread";
    /** Generic denial text — deliberately carries NO thread data (topic/question/ref/token). */
    private static final String DENY_INVALID = "This portal link is invalid or has expired";
    private static final String DENY_CLOSED = "This request has been closed and can no longer accept a response";

    private final QueryThreadRepository threads;
    private final QueryMessageRepository messages;
    private final DocumentStoreService documents;
    private final AuditService audit;

    public PortalService(QueryThreadRepository threads, QueryMessageRepository messages,
                         DocumentStoreService documents, AuditService audit) {
        this.threads = threads;
        this.messages = messages;
        this.documents = documents;
        this.audit = audit;
    }

    /** One message on the timeline, party-labelled ({@code BANK} / {@code YOU}) — no internal usernames leaked. */
    public record PortalMessage(String from, String body, Instant at) {
    }

    /**
     * The read model for a single tokened thread — ONLY this thread. {@code from}-labels are
     * redacted to {@code BANK}/{@code YOU} so an internal RM/analyst username is never exposed to
     * the external party. Internal subject references are intentionally omitted.
     */
    public record PortalContext(String reference, String channel, String status, String topic,
                                String question, Instant deadline, List<PortalMessage> messages,
                                List<String> allowedActions) {
    }

    /** Result of a document upload through the portal — the DMS row id + integrity fields. */
    public record UploadResult(String reference, String status, Long storedDocId, String filename,
                               String sha256, long sizeBytes) {
    }

    // =============================================================== read (safe, idempotent, non-consuming)

    @Transactional(readOnly = true)
    public PortalContext context(String token) {
        return toContext(resolve(token));
    }

    // =============================================================== respond

    @Transactional
    public PortalContext respond(String token, String message) {
        QueryThread t = resolve(token);
        if (message == null || message.isBlank()) {
            throw ApiException.badRequest("A response message is required");
        }
        appendExternal(t, message);
        markResponded(t);
        audit.external(party(t.getChannel()), "PORTAL_RESPONSE_RECEIVED", SUBJECT, t.getQueryRef(),
                "External " + party(t.getChannel()) + " response received via self-service portal",
                Map.of("channel", t.getChannel().name(), "status", t.getStatus().name(), "via", "PORTAL"));
        return toContext(t);
    }

    // =============================================================== document upload (via the governed DMS store)

    @Transactional
    public UploadResult upload(String token, String filename, String contentType, byte[] content) {
        QueryThread t = resolve(token);
        if (content == null || content.length == 0) {
            throw ApiException.badRequest("An uploaded document is required");
        }
        if (filename == null || filename.isBlank()) {
            throw ApiException.badRequest("filename is required");
        }
        // Store through the EXISTING governed DMS lane, tagged to THIS thread's subject. The
        // uploader is stamped as an unmistakably-external principal (never an internal user id).
        StoredDocument doc = documents.store(SUBJECT, t.getQueryRef(), filename, contentType, content,
                "portal:" + party(t.getChannel()));
        appendExternal(t, "Uploaded document '" + filename + "' (" + content.length + " bytes)");
        markResponded(t);
        audit.external(party(t.getChannel()), "PORTAL_DOCUMENT_UPLOADED", SUBJECT, t.getQueryRef(),
                "External " + party(t.getChannel()) + " uploaded '" + filename + "' via self-service portal",
                Map.of("channel", t.getChannel().name(), "storedDocId", doc.getId(),
                        "filename", filename, "sha256", doc.getSha256(), "sizeBytes", doc.getSizeBytes(),
                        "via", "PORTAL"));
        return new UploadResult(t.getQueryRef(), t.getStatus().name(), doc.getId(), doc.getFilename(),
                doc.getSha256(), doc.getSizeBytes());
    }

    // =============================================================== internals

    /**
     * Resolve the single thread the token grants access to, or deny. The raw token is hashed and
     * looked up by hash — the token is the key, so access is structurally scoped to ONE thread.
     * Any failure (blank/unknown/non-external/terminal) is a generic denial that leaks no thread
     * data. Never logs the token.
     */
    private QueryThread resolve(String token) {
        if (token == null || token.isBlank()) {
            throw ApiException.notFound(DENY_INVALID);
        }
        QueryThread t = threads.findByResponseTokenHash(hashToken(token)).orElse(null);
        if (t == null) {
            // Unknown, forged, or a token whose hash was cleared (e.g. spent on the legacy
            // single-use external-response). No thread — nothing to leak.
            throw ApiException.notFound(DENY_INVALID);
        }
        if (t.getChannel() == QueryChannel.INTERNAL) {
            // Belt-and-braces: internal threads never carry a token hash, but never serve one here.
            throw ApiException.notFound(DENY_INVALID);
        }
        if (isTerminal(t.getStatus())) {
            // Withdrawn (CANCELLED) or already closed (RESOLVED) — the link no longer grants access.
            throw ApiException.forbiddenAutonomy(DENY_CLOSED);
        }
        return t;
    }

    private void appendExternal(QueryThread t, String body) {
        QueryMessage m = new QueryMessage();
        m.setQueryRef(t.getQueryRef());
        m.setAuthor(party(t.getChannel()));
        m.setAuthorType("EXTERNAL");     // never HUMAN — a token-bearer is not a named internal human
        m.setBody(body);
        m.setInbound(true);
        messages.save(m);
    }

    private void markResponded(QueryThread t) {
        t.setStatus(QueryStatus.RESPONDED);
        threads.save(t);
    }

    private PortalContext toContext(QueryThread t) {
        List<PortalMessage> timeline = new ArrayList<>();
        for (QueryMessage m : messages.findByQueryRefOrderByIdAsc(t.getQueryRef())) {
            // Redact the author to a party label so an internal RM/analyst username is never
            // exposed to the external party — the external party's own posts show as YOU.
            boolean external = m.isInbound() || "EXTERNAL".equalsIgnoreCase(m.getAuthorType());
            timeline.add(new PortalMessage(external ? "YOU" : "BANK", m.getBody(), m.getAt()));
        }
        List<String> allowed = List.of("VIEW", "RESPOND", "UPLOAD_DOCUMENT");
        return new PortalContext(t.getQueryRef(), t.getChannel().name(), t.getStatus().name(),
                t.getTopic(), t.getQuestion(), t.getDueAt(), timeline, allowed);
    }

    private static boolean isTerminal(QueryStatus s) {
        return s == QueryStatus.RESOLVED || s == QueryStatus.CANCELLED;
    }

    /** Party label derived from the channel — never an internal identity. */
    private static String party(QueryChannel c) {
        return c == QueryChannel.EXTERNAL_VENDOR ? "vendor" : "customer";
    }

    /** SHA-256 (hex) of a raw token — matches the hash persisted by the query external-response lane. */
    private static String hashToken(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);   // never on a standard JRE
        }
    }
}
