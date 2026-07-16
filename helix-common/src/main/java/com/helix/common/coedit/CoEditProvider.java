package com.helix.common.coedit;

/**
 * SPI for the SharePoint / Excel-Online co-edit seam. A producing service already
 * renders local artifacts today (docgen HTML via {@code /api/docs/{id}/print}, the
 * charge-Excel CSV via {@code /api/collateral-intel/{ref}/charge-excel}). This SPI adds
 * an <em>optional</em> outbound path: push that same artifact to a SharePoint drive
 * through Microsoft Graph and hand back a browser co-edit URL.
 *
 * <p>Two implementations ship in helix-common, selected by {@code helix.coedit.provider}:
 * <ul>
 *   <li>{@link NoopCoEditProvider} — <b>DEFAULT</b> ({@code none}). Makes no external
 *       call; returns the local artifact (link + bytes) exactly as the existing export
 *       flow. This is today's behaviour and keeps the regression contract intact.</li>
 *   <li>{@link GraphCoEditProvider} — ({@code graph}). Real Microsoft Graph REST via
 *       {@code RestClient} (client-credentials token → drive-item upload → co-edit link,
 *       plus a best-effort Excel workbook session). Inert unless explicitly enabled and
 *       configured; any Graph outage <b>fails soft</b> back to the local artifact.</li>
 * </ul>
 *
 * <p>Governance: co-edit is a document-distribution convenience only. It never mutates
 * an authoritative figure — the artifact bytes are supplied verbatim by the caller.
 */
public interface CoEditProvider {

    /** Provider name recorded on the result / audit trail (e.g. {@code none}, {@code graph}). */
    String name();

    /**
     * Publish the supplied artifact for co-editing. Must NEVER throw: a provider that
     * cannot reach its backend returns a fail-soft {@link CoEditResult#fallback} carrying
     * the local artifact so the caller always gets a usable result.
     */
    CoEditResult publish(CoEditRequest request);
}
