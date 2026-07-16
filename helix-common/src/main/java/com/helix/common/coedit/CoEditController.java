package com.helix.common.coedit;

import com.helix.common.audit.AuditService;
import com.helix.common.web.ApiException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Co-edit endpoint, automatically present in every service that includes helix-common
 * (like {@code /api/audit} and {@code /api/notifications}). It is provider-driven:
 *
 * <ul>
 *   <li>DEFAULT ({@code helix.coedit.provider=none}) — returns the local artifact (link +
 *       bytes) verbatim: today's export behaviour, no external call attempted.</li>
 *   <li>({@code helix.coedit.provider=graph}) — pushes the artifact to SharePoint via
 *       Microsoft Graph and returns a browser co-edit URL, failing soft to the local
 *       artifact on any Graph outage.</li>
 * </ul>
 *
 * <p>The caller supplies the artifact bytes it already rendered (the docgen HTML from
 * {@code /api/docs/{id}/print}, the charge-Excel CSV from
 * {@code /api/collateral-intel/{ref}/charge-excel}, …) so this layer stays decoupled from
 * any one service's document routes. Every publish carries {@code X-Actor} into an
 * append-only audit event; co-edit never mutates an authoritative figure.
 */
@RestController
@RequestMapping("/api/coedit")
public class CoEditController {

    private final CoEditProvider provider;
    private final AuditService audit;

    public CoEditController(CoEditProvider provider, AuditService audit) {
        this.provider = provider;
        this.audit = audit;
    }

    /** Reports the active provider so the UI / tests can tell which mode is wired. */
    @GetMapping("/provider")
    public Map<String, Object> provider() {
        return Map.of("provider", provider.name());
    }

    @PostMapping
    public CoEditResult publish(@RequestBody CoEditHttpRequest req,
                                @RequestHeader(value = "X-Actor", defaultValue = "system") String actor) {
        if (req == null || req.content() == null || req.content().isBlank()) {
            throw ApiException.badRequest("content is required (the artifact bytes to publish)");
        }
        byte[] bytes = decode(req);
        String fileName = blankTo(req.fileName(), "artifact.bin");
        String contentType = blankTo(req.contentType(), "application/octet-stream");
        String subjectType = blankTo(req.subjectType(), "Artifact");
        String subjectId = blankTo(req.subjectId(), fileName);

        CoEditRequest cr = new CoEditRequest(subjectType, subjectId, fileName, contentType,
                bytes, emptyToNull(req.localUrl()), actor);
        CoEditResult result = provider.publish(cr);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("provider", result.provider());
        detail.put("mode", result.mode());
        detail.put("fileName", fileName);
        detail.put("contentType", contentType);
        detail.put("bytes", bytes.length);
        detail.put("fallback", result.fallback());
        if (result.coEditUrl() != null) detail.put("coEditUrl", result.coEditUrl());
        if (result.localUrl() != null) detail.put("localUrl", result.localUrl());
        if (result.workbookSessionId() != null) detail.put("workbookSessionId", result.workbookSessionId());

        String eventType = result.fallback() ? "COEDIT_FALLBACK"
                : (CoEditResult.MODE_COEDIT.equals(result.mode()) ? "COEDIT_PUBLISHED" : "COEDIT_LOCAL");
        String summary = switch (eventType) {
            case "COEDIT_PUBLISHED" -> "Published %s to SharePoint for co-edit (%s)".formatted(fileName, result.provider());
            case "COEDIT_FALLBACK" -> "Co-edit fell back to local artifact for %s (%s)".formatted(fileName, result.provider());
            default -> "Returned local artifact %s (provider=%s)".formatted(fileName, result.provider());
        };
        audit.human(actor, eventType, subjectType, subjectId, summary, detail);
        return result;
    }

    private static byte[] decode(CoEditHttpRequest req) {
        String encoding = req.encoding() == null ? "text" : req.encoding().trim().toLowerCase();
        if ("base64".equals(encoding)) {
            try {
                return Base64.getDecoder().decode(req.content());
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("content is not valid base64: " + e.getMessage());
            }
        }
        return req.content().getBytes(StandardCharsets.UTF_8);
    }

    private static String blankTo(String s, String dflt) {
        return s == null || s.isBlank() ? dflt : s;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /**
     * HTTP request body for a co-edit publish.
     *
     * @param encoding {@code text} (default; content is UTF-8 text) or {@code base64}
     *                 (content is base64-encoded bytes — use for binary artifacts)
     */
    public record CoEditHttpRequest(String subjectType, String subjectId, String fileName,
                                    String contentType, String content, String encoding, String localUrl) {
    }
}
