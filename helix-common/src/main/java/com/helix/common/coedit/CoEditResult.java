package com.helix.common.coedit;

import java.util.Base64;
import java.util.List;

/**
 * Outcome of a {@link CoEditProvider#publish} call.
 *
 * <ul>
 *   <li>{@code mode == LOCAL} — no external push; {@code contentBase64} carries the local
 *       artifact bytes and {@code localUrl} points to the existing export endpoint. This is
 *       the default ({@code none}) behaviour, and also what a {@code graph} provider returns
 *       when it fails soft ({@code fallback == true}).</li>
 *   <li>{@code mode == COEDIT} — the artifact was uploaded to SharePoint; {@code coEditUrl}
 *       (and {@code webUrl}) open it for browser co-editing, and {@code workbookSessionId} is
 *       populated when an Excel workbook session was opened.</li>
 * </ul>
 */
public record CoEditResult(String provider, String mode, String subjectType, String subjectId,
                           String fileName, String contentType, String coEditUrl, String webUrl,
                           String workbookSessionId, String localUrl, String contentBase64,
                           boolean fallback, List<String> warnings) {

    public static final String MODE_LOCAL = "LOCAL";
    public static final String MODE_COEDIT = "COEDIT";

    /** Local result: echo the artifact bytes + local link, no external call (default path). */
    public static CoEditResult local(String provider, CoEditRequest req) {
        return new CoEditResult(provider, MODE_LOCAL, req.subjectType(), req.subjectId(),
                req.fileName(), req.contentType(), null, null, null, req.localUrl(),
                encode(req.content()), false, List.of());
    }

    /** Fail-soft result: a co-edit push failed, so hand back the local artifact + a warning. */
    public static CoEditResult fallback(String provider, CoEditRequest req, String warning) {
        return new CoEditResult(provider, MODE_LOCAL, req.subjectType(), req.subjectId(),
                req.fileName(), req.contentType(), null, null, null, req.localUrl(),
                encode(req.content()), true, List.of(warning));
    }

    /** Co-edit result: the artifact is live in SharePoint at {@code coEditUrl}. */
    public static CoEditResult coedit(String provider, CoEditRequest req, String coEditUrl,
                                      String webUrl, String workbookSessionId, List<String> warnings) {
        return new CoEditResult(provider, MODE_COEDIT, req.subjectType(), req.subjectId(),
                req.fileName(), req.contentType(), coEditUrl, webUrl, workbookSessionId,
                req.localUrl(), null, false, warnings == null ? List.of() : warnings);
    }

    private static String encode(byte[] content) {
        return content == null ? null : Base64.getEncoder().encodeToString(content);
    }
}
