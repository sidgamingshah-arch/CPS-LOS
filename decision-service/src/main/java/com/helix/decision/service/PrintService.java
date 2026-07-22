package com.helix.decision.service;

import com.helix.common.audit.AuditService;
import com.helix.common.web.ApiException;
import com.helix.decision.entity.CreditProposal;
import com.helix.decision.entity.GeneratedDocument;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * Print / office rendering for the confirmed credit artifacts (credit proposal,
 * sanction letter, generated documents). This is a <b>pure rendering</b> path — it
 * takes an artifact that has <em>already</em> been generated (and, for documents,
 * human-confirmed via the existing confirm-lock) and re-emits its authoritative body
 * in the requested output format:
 *
 * <ul>
 *   <li>the default (no {@code format}, or {@code html}) — a self-contained,
 *       print-optimised standalone HTML page (letterhead, governance header,
 *       {@code @media print} page-break styling) that the browser turns into a PDF via
 *       its native print-to-PDF. This path is <b>byte-identical</b> to the pre-existing
 *       behaviour;</li>
 *   <li>{@code rtf} — a Word-openable RTF document;</li>
 *   <li>{@code xlsx} — a SpreadsheetML 2003 XML workbook (Excel-openable, no zip/OOXML
 *       library) of the artifact's tabular content;</li>
 *   <li>{@code csv} — the tabular content as CSV (formula-injection guarded).</li>
 * </ul>
 *
 * <p>Dependency-free by design (see {@link OfficeRenderer}). No figure is recomputed and
 * no source artifact is mutated — the artifact entities are loaded through the owning
 * services' read-only transactions and are never re-saved here; the only write is an
 * append-only {@code *_RENDERED} audit event in this render's own transaction.
 */
@Service
public class PrintService {

    private final DocGenService docGen;
    private final CreditProposalService proposals;
    private final AuditService audit;

    public PrintService(DocGenService docGen, CreditProposalService proposals, AuditService audit) {
        this.docGen = docGen;
        this.proposals = proposals;
        this.audit = audit;
    }

    /** The output formats a print endpoint can serve. HTML is the default (byte-identical). */
    private enum Fmt {HTML, RTF, XLSX, CSV}

    /**
     * Resolve the {@code ?format=} query value. Absent / blank / {@code html} → the existing
     * HTML behaviour; {@code rtf|doc|docx|word} → RTF; {@code xlsx|xls|excel|spreadsheet} →
     * SpreadsheetML; {@code csv} → CSV; anything else → 400 (never a silent HTML fallback that
     * would surprise a caller who asked for a specific format).
     */
    private static Fmt parse(String format) {
        if (format == null || format.isBlank() || "html".equalsIgnoreCase(format)) return Fmt.HTML;
        return switch (format.trim().toLowerCase()) {
            case "rtf", "doc", "docx", "word" -> Fmt.RTF;
            case "xlsx", "xls", "excel", "spreadsheet", "spreadsheetml" -> Fmt.XLSX;
            case "csv" -> Fmt.CSV;
            default -> throw ApiException.badRequest("Unsupported print format: " + format);
        };
    }

    // ------------------------------------------------------------- generated document / sanction letter

    /**
     * Renders a generated document (facility letter, sanction letter, …) in the requested
     * format. The HTML default reproduces {@link GeneratedDocument#getHtml()} verbatim in a
     * print-optimised standalone page (unchanged behaviour); the office formats re-emit the
     * same authoritative body as RTF / SpreadsheetML / CSV. Unknown id → 404.
     */
    public ResponseEntity<String> renderDocument(Long id, String format, String actor) {
        Fmt fmt = parse(format);
        // docGen.get(...) runs in DocGenService's own read-only transaction, so the entity
        // comes back detached — building the output can never dirty-flush it. Unknown → 404.
        GeneratedDocument d = docGen.get(id);

        if (fmt == Fmt.HTML) {
            boolean confirmed = "CONFIRMED".equals(d.getStatus()) || "ISSUED".equals(d.getStatus());
            String html = documentHtml(d, confirmed);
            audit.human(actor, "DOCUMENT_RENDERED", "GeneratedDocument", String.valueOf(id),
                    "Rendered %s (%s) to print/PDF view".formatted(d.getTitle(), d.getStatus()),
                    Map.of("templateKey", d.getTemplateKey(), "status", d.getStatus(),
                            "confirmed", confirmed, "rendering", true));
            return htmlResponse(html);
        }

        String base = sanitizeFilename(nb(d.getTemplateKey(), "document") + "-" + id);
        Office office = office(fmt, "Document", d.getTitle(), d.getHtml(), base);
        audit.human(actor, "DOCUMENT_RENDERED", "GeneratedDocument", String.valueOf(id),
                "Rendered %s (%s) to %s".formatted(d.getTitle(), d.getStatus(), fmt.name().toLowerCase()),
                Map.of("templateKey", d.getTemplateKey(), "status", d.getStatus(),
                        "format", fmt.name().toLowerCase(), "rendering", true));
        return officeResponse(office);
    }

    // ------------------------------------------------------------- credit proposal

    /**
     * Renders the latest credit proposal for an application in the requested format. The HTML
     * default reproduces {@link CreditProposal#getHtml()} verbatim in a print-optimised
     * standalone page (unchanged behaviour, stamped {@code CREDIT_PROPOSAL_RENDERED}); the
     * office formats re-emit the same authoritative body as RTF / SpreadsheetML / CSV (stamped
     * {@code DOCUMENT_RENDERED}). Unknown reference → 404.
     */
    public ResponseEntity<String> renderProposal(String reference, String format, String actor) {
        Fmt fmt = parse(format);
        // proposals.latest(...) runs read-only in CreditProposalService. Unknown → 404.
        CreditProposal p = proposals.latest(reference);
        String title = "Credit proposal · " + reference + " (v" + p.getVersion() + ")";

        if (fmt == Fmt.HTML) {
            String html = proposalHtml(reference, p, title);
            audit.human(actor, "CREDIT_PROPOSAL_RENDERED", "Application", reference,
                    "Rendered credit proposal v%d to print/PDF view".formatted(p.getVersion()),
                    Map.of("version", p.getVersion(), "rendering", true));
            return htmlResponse(html);
        }

        String base = sanitizeFilename("credit-proposal-" + reference + "-v" + p.getVersion());
        Office office = office(fmt, "Credit proposal", title, p.getHtml(), base);
        audit.human(actor, "DOCUMENT_RENDERED", "Application", reference,
                "Rendered credit proposal v%d to %s".formatted(p.getVersion(), fmt.name().toLowerCase()),
                Map.of("version", p.getVersion(), "artifact", "CreditProposal",
                        "format", fmt.name().toLowerCase(), "rendering", true));
        return officeResponse(office);
    }

    // ------------------------------------------------------------- office assembly

    /** A rendered office body plus the response metadata (content type + download filename). */
    private record Office(String body, MediaType contentType, String filename) {
    }

    private static Office office(Fmt fmt, String sheetName, String title, String html, String base) {
        return switch (fmt) {
            case RTF -> new Office(OfficeRenderer.rtf(title, html),
                    MediaType.parseMediaType("application/rtf"), base + ".rtf");
            // SpreadsheetML 2003 XML — Excel-openable via the mso-application PI; named .xls (it
            // is NOT a zipped OOXML .xlsx, so a .xlsx name would make Excel reject it).
            case XLSX -> new Office(OfficeRenderer.spreadsheetXml(sheetName, title, html),
                    MediaType.parseMediaType("application/vnd.ms-excel"), base + ".xls");
            case CSV -> new Office(OfficeRenderer.csv(html),
                    MediaType.parseMediaType("text/csv; charset=utf-8"), base + ".csv");
            case HTML -> throw new IllegalStateException("HTML handled on the caller path");
        };
    }

    // A rendered credit artifact carries authoritative (and possibly sensitive) figures — it must
    // never be persisted by a shared/intermediary cache or the browser's disk cache.
    private ResponseEntity<String> htmlResponse(String html) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.TEXT_HTML)
                .body(html);
    }

    private ResponseEntity<String> officeResponse(Office office) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + office.filename() + "\"")
                .contentType(office.contentType())
                .body(office.body());
    }

    // ------------------------------------------------------------- HTML assembly (unchanged output)

    /** Assemble the print-optimised standalone HTML for a generated document (byte-identical). */
    private String documentHtml(GeneratedDocument d, boolean confirmed) {
        StringBuilder gov = new StringBuilder();
        if (d.isAdvisory() && !confirmed) {
            gov.append(chip("ai", "AI-DRAFTED &middot; ADVISORY"));
        }
        if (confirmed) {
            gov.append(chip("human", "HUMAN-CONFIRMED"));
        }
        gov.append(chip("det", "DETERMINISTIC FIGURES PRESERVED VERBATIM"));

        StringBuilder meta = new StringBuilder();
        metaRow(meta, "Template", d.getTemplateKey());
        metaRow(meta, "Status", d.getStatus());
        metaRow(meta, "Generated by", d.getGeneratedBy());
        if (d.getConfirmedBy() != null) {
            metaRow(meta, "Confirmed by", d.getConfirmedBy()
                    + (d.getConfirmedAt() == null ? "" : " · " + d.getConfirmedAt()));
        }
        if (!confirmed) {
            meta.append("<div class=\"note\">This is a DRAFT rendering — the document is non-binding until a "
                    + "named human confirms it (maker &ne; checker) via Document Generation.</div>");
        }

        String flow = confirmed
                ? "AI ASSEMBLES &rarr; HUMAN CONFIRMS &rarr; RENDERED (figures unchanged)"
                : "AI ASSEMBLES &rarr; awaiting HUMAN CONFIRM";

        return standalone(d.getTitle(), gov.toString(), meta.toString(), flow, d.getHtml());
    }

    /** Assemble the print-optimised standalone HTML for a credit proposal (byte-identical). */
    private String proposalHtml(String reference, CreditProposal p, String title) {
        String gov = chip("ai", "AI-ASSEMBLED &middot; GROUNDED")
                + chip("human", "HUMAN SIGNS")
                + chip("det", "DETERMINISTIC FIGURES &middot; QUOTED VERBATIM");

        StringBuilder meta = new StringBuilder();
        metaRow(meta, "Application", reference);
        metaRow(meta, "Version", "v" + p.getVersion());
        metaRow(meta, "Generated by", p.getGeneratedBy());
        if (p.getGeneratedAt() != null) metaRow(meta, "Generated at", String.valueOf(p.getGeneratedAt()));
        meta.append("<div class=\"note\">Every figure below is quoted verbatim from a platform service; "
                + "no number is invented or recomputed by this rendering. A named human at the required "
                + "authority signs the proposal — AI cannot approve.</div>");

        return standalone(title, gov, meta.toString(),
                "AI ASSEMBLES (grounded) &rarr; HUMAN SIGNS &rarr; RENDERED (figures unchanged)", p.getHtml());
    }

    /**
     * Wraps an already-rendered authoritative HTML body in a self-contained, print-optimised
     * page. All CSS is inlined (no external hosts); an auto-print script fires the browser print
     * dialog so the caller can save as PDF.
     */
    private String standalone(String title, String govChips, String metaRows, String flow, String bodyHtml) {
        String safeTitle = escape(stripTags(title));
        return "<!DOCTYPE html>\n"
                + "<html lang=\"en\"><head><meta charset=\"utf-8\"/>"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>"
                + "<title>" + safeTitle + " — Helix</title>"
                + "<style>" + PRINT_CSS + "</style></head><body>"
                + "<div class=\"toolbar no-print\">"
                + "<span class=\"tbnote\">Print-optimised rendering. Use “Print / Save as PDF” and choose "
                + "“Save as PDF” as the destination.</span>"
                + "<button type=\"button\" onclick=\"window.print()\">Print / Save as PDF</button>"
                + "</div>"
                + "<div class=\"page\">"
                + "<header class=\"letterhead\">"
                + "<div class=\"brand\"><span class=\"mark\">H</span><span class=\"name\">HELIX BANK</span></div>"
                + "<div class=\"promise\">Governed AI for wholesale credit &middot; "
                + "AI where it helps &middot; humans where regulation demands &middot; deterministic figures throughout</div>"
                + "</header>"
                + "<section class=\"gov\">"
                + "<div class=\"chips\">" + govChips + "</div>"
                + "<div class=\"flow\">" + flow + "</div>"
                + "<div class=\"meta\">" + metaRows + "</div>"
                + "</section>"
                + "<main class=\"artifact\">" + (bodyHtml == null ? "" : bodyHtml) + "</main>"
                + "<footer class=\"docfoot\">Rendered " + Instant.now()
                + " &middot; Helix &middot; a faithful rendering of an already-generated artifact — "
                + "no figure recomputed, no authoritative value changed.</footer>"
                + "</div>"
                + "<script>setTimeout(function(){try{window.print();}catch(e){}},350);</script>"
                + "</body></html>";
    }

    private static final String PRINT_CSS =
            "*{box-sizing:border-box}"
            + "html,body{margin:0;padding:0;background:#eef1f5;color:#1a1f2b;"
            + "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;"
            + "font-size:12.5px;line-height:1.5}"
            + ".toolbar{position:sticky;top:0;display:flex;align-items:center;gap:14px;justify-content:flex-end;"
            + "padding:10px 16px;background:#0d1b2a;color:#e8eef6}"
            + ".toolbar .tbnote{margin-right:auto;font-size:11px;opacity:.85}"
            + ".toolbar button{background:#3b82f6;color:#fff;border:0;border-radius:6px;padding:8px 16px;"
            + "font-size:12.5px;font-weight:600;cursor:pointer}"
            + ".page{max-width:820px;margin:18px auto;background:#fff;padding:34px 40px;"
            + "box-shadow:0 2px 14px rgba(13,27,42,.12)}"
            + ".letterhead{display:flex;align-items:center;justify-content:space-between;gap:20px;"
            + "border-bottom:2px solid #0d1b2a;padding-bottom:12px}"
            + ".brand{display:flex;align-items:center;gap:10px}"
            + ".brand .mark{display:inline-flex;align-items:center;justify-content:center;width:30px;height:30px;"
            + "border-radius:7px;background:#0d1b2a;color:#fff;font-weight:800;font-size:17px}"
            + ".brand .name{font-weight:800;letter-spacing:.14em;font-size:15px;color:#0d1b2a}"
            + ".promise{font-size:10px;color:#5b6472;text-align:right;max-width:360px;text-transform:uppercase;"
            + "letter-spacing:.04em}"
            + ".gov{margin:16px 0 8px;padding:12px 14px;background:#f6f8fb;border:1px solid #e3e8ef;border-radius:8px}"
            + ".gov .chips{display:flex;flex-wrap:wrap;gap:8px}"
            + ".chip{display:inline-block;font-size:10px;font-weight:700;letter-spacing:.05em;padding:4px 9px;"
            + "border-radius:999px;border:1px solid}"
            + ".chip.ai{background:#f3edff;color:#6b3fd6;border-color:#d9c9ff}"
            + ".chip.human{background:#e9f8ef;color:#137a43;border-color:#bfe7cf}"
            + ".chip.det{background:#e8f1fe;color:#1a5fbf;border-color:#c3ddf9}"
            + ".gov .flow{margin-top:10px;font-size:10.5px;font-weight:700;letter-spacing:.05em;color:#3a4453}"
            + ".gov .meta{margin-top:10px;font-size:11.5px;color:#3a4453}"
            + ".gov .meta .row{display:flex;gap:8px;padding:1px 0}"
            + ".gov .meta .row b{min-width:120px;color:#5b6472;font-weight:600}"
            + ".gov .meta .note{margin-top:8px;font-size:11px;color:#8a5a12;background:#fff7e6;"
            + "border:1px solid #ffe2a8;border-radius:6px;padding:7px 9px}"
            + ".artifact{margin-top:18px}"
            + ".artifact h1{font-size:19px;margin:0 0 6px}"
            + ".artifact h2{font-size:14px;margin:18px 0 6px;padding-bottom:4px;border-bottom:1px solid #e3e8ef;"
            + "page-break-after:avoid}"
            + ".artifact section{page-break-inside:avoid}"
            + ".artifact p{margin:6px 0}"
            + ".artifact p.muted,.artifact .muted{color:#5b6472}"
            + ".artifact p.meta{color:#3a4453;font-size:11.5px}"
            + ".artifact .prov,.artifact small{color:#8a93a2;font-size:10.5px}"
            + ".artifact ul,.artifact ol{margin:6px 0;padding-left:20px}"
            + ".artifact table{width:100%;border-collapse:collapse;margin:8px 0;font-size:11.5px;"
            + "page-break-inside:avoid}"
            + ".artifact th,.artifact td{border:1px solid #dfe4ec;padding:5px 8px;text-align:left;"
            + "vertical-align:top}"
            + ".artifact thead th{background:#f2f5f9}"
            + ".artifact table.kv td:first-child{color:#5b6472;width:40%}"
            + ".docfoot{margin-top:26px;padding-top:10px;border-top:1px solid #e3e8ef;font-size:10px;color:#8a93a2}"
            + "@page{size:A4;margin:16mm}"
            + "@media print{html,body{background:#fff}.no-print{display:none!important}"
            + ".page{max-width:none;margin:0;padding:0;box-shadow:none}}";

    // ------------------------------------------------------------- small helpers

    private void metaRow(StringBuilder sb, String label, String value) {
        if (value == null || value.isBlank()) return;
        sb.append("<div class=\"row\"><b>").append(escape(label)).append("</b><span>")
                .append(escape(value)).append("</span></div>");
    }

    private String chip(String kind, String label) {
        return "<span class=\"chip " + kind + "\">" + label + "</span>";
    }

    private String stripTags(String s) {
        return s == null ? "" : s.replaceAll("<[^>]*>", "").replace("&middot;", "·");
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String nb(String s, String dflt) {
        return s == null || s.isBlank() ? dflt : s;
    }

    /** Keep a download filename to safe characters so it can never break the header. */
    private static String sanitizeFilename(String s) {
        if (s == null || s.isBlank()) return "helix-document";
        String safe = s.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.length() > 120 ? safe.substring(0, 120) : safe;
    }
}
