package com.helix.counterparty.service;

import com.helix.common.audit.AuditService;
import com.helix.common.governance.AiCapability;
import com.helix.common.governance.AiGovernanceClient;
import com.helix.common.web.ApiException;
import com.helix.counterparty.dto.Dtos.DocFieldSuggestion;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * COUNTERPARTY-SCOPED, extraction-only document intelligence for the create screen. A named human
 * uploads a trade licence / MOA / AOA (or pastes its text); this service parses the entity's
 * identity fields FROM the document's real text with a deterministic regex parser and RETURNS them
 * as advisory <em>suggestions</em>. It follows the platform's advisory-AI invariant:
 *
 * <ul>
 *   <li><b>Advisory only</b> — every value is copied verbatim from the text, never invented.</li>
 *   <li><b>No persistence</b> — the extract call writes no counterparty and mutates no figure;
 *       the human edits the pre-filled form and submits it separately (the real, audited write).</li>
 *   <li><b>Audited</b> — each extraction stamps {@code audit.ai("counterparty-doc-extract", …)}.</li>
 * </ul>
 *
 * <p>This is a small ported parser (mirroring origination's {@code DocIntelligenceService} content
 * parser) so the create path never takes a cross-service dependency. Text extraction is PDFBox for
 * PDFs and UTF-8 for text; no OCR is wired here (a scanned-only PDF degrades gracefully to a note).</p>
 */
@Service
public class DocFieldExtractionService {

    private static final Logger log = LoggerFactory.getLogger(DocFieldExtractionService.class);

    /** Cap the parsed text so a pathological upload can never bloat a prompt / log line. */
    static final int MAX_TEXT_CHARS = 40_000;

    private final AuditService audit;
    private final AiGovernanceClient governance;

    public DocFieldExtractionService(AuditService audit, AiGovernanceClient governance) {
        this.audit = audit;
        this.governance = governance;
    }

    // ------------------------------------------------------------------ entry points

    /** Extract from pasted text. */
    public DocFieldSuggestion extractFromText(String text, String declaredType, String actor) {
        if (text == null || text.isBlank()) {
            throw ApiException.badRequest("text is required when no file is uploaded");
        }
        return build(cap(text), declaredType, "TEXT", 1, actor);
    }

    /** Extract from an uploaded document's real bytes (PDF via PDFBox, else UTF-8 text). */
    public DocFieldSuggestion extractFromFile(MultipartFile file, String declaredType, String actor) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("file is required (or POST JSON {text} instead)");
        }
        Extracted ex = extractText(file);
        return build(ex.text(), declaredType, ex.method(), ex.pageCount(), actor);
    }

    // ------------------------------------------------------------------ core

    private DocFieldSuggestion build(String text, String declaredType, String method, int pageCount, String actor) {
        // Gate: create-time autofill is the DOC_INTEL governed capability. No counterparty exists
        // yet, so there is no jurisdiction to scope by (null → platform default, enabled).
        governance.enforce(AiCapability.DOC_INTEL, null);

        String type = declaredType == null || declaredType.isBlank() ? "MOA_AOA" : declaredType.trim().toUpperCase(Locale.ROOT);
        List<String> notes = new ArrayList<>();
        Map<String, Object> fields = new LinkedHashMap<>();

        boolean contentDerived = false;
        if (text != null && !text.isBlank()) {
            int found = parseIdentityFields(text, fields);
            contentDerived = found > 0;
            if (!contentDerived) {
                notes.add("No recognisable identity fields found in the document text.");
            }
        } else {
            notes.add("No embedded text could be extracted from the upload (" + method
                    + "); a scanned document needs OCR configured, or paste the text instead.");
        }

        String legalName = strOf(fields.get("legal_name"));
        String cin = strOf(fields.get("cin"));
        String registrationNo = strOf(fields.get("registration_number"));
        // A CIN doubles as the registration number when no explicit registration line is present.
        if (registrationNo == null && cin != null) {
            registrationNo = cin;
        }
        String gstin = strOf(fields.get("gstin"));
        String incorporationDate = strOf(fields.get("incorporation_date"));
        String registeredAddress = strOf(fields.get("registered_address"));
        List<String> directors = directorsOf(fields.get("directors"));

        double overallConfidence = contentDerived ? 0.90 : 0.0;

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("declaredType", type);
        detail.put("extractionMethod", method);
        detail.put("fields", fields.size());
        detail.put("contentDerived", contentDerived);
        detail.put("advisory", true);
        detail.put("persisted", false);
        // Subject is the detected legal name (or "unassigned") — no counterparty record exists yet.
        String subjectRef = legalName == null || legalName.isBlank() ? "unassigned" : legalName;
        audit.ai("counterparty-doc-extract", "COUNTERPARTY_DOC_EXTRACTED", "Counterparty", subjectRef,
                "Extracted %d field(s) from an uploaded %s (%s) — suggestion only, human confirm required; nothing persisted"
                        .formatted(fields.size(), type, method), detail);

        return new DocFieldSuggestion(type, contentDerived, method, pageCount, overallConfidence,
                legalName, registrationNo, cin, gstin, incorporationDate, registeredAddress, directors,
                fields, true, notes);
    }

    // ------------------------------------------------- deterministic identity-field parser

    /**
     * Parses the entity's identity fields FROM the document's real text using case-insensitive
     * regex. Each hit is stored with the 1-based LINE where it was found. Deterministic PARSING
     * (not generation): every value is copied verbatim. Returns the number of fields recognised.
     */
    int parseIdentityFields(String text, Map<String, Object> fields) {
        int before = fields.size();
        cin(fields, text);
        gstin(fields, text);
        lineTail(fields, text, "legal_name",
                "(?:name\\s+of\\s+(?:the\\s+)?company|legal\\s+name|entity\\s+name|company\\s+name|name\\s+of\\s+(?:the\\s+)?entity)",
                0.90);
        lineTail(fields, text, "registration_number",
                "(?:registration\\s*(?:no|number)|trade\\s+licen[cs]e\\s*(?:no|number)?|licen[cs]e\\s*(?:no|number)|reg\\.?\\s*no)",
                0.88);
        incorporationDate(fields, text);
        lineTail(fields, text, "registered_address",
                "(?:registered\\s+(?:office\\s+)?address|registered\\s+office|principal\\s+place\\s+of\\s+business)",
                0.86);
        directors(fields, text);
        return fields.size() - before;
    }

    /** CIN — 21-char corporate identity number (e.g. U27100MH2015PLC123456). */
    private void cin(Map<String, Object> fields, String text) {
        Matcher m = Pattern.compile("\\b([LU][0-9]{5}[A-Z]{2}[0-9]{4}[A-Z]{3}[0-9]{6})\\b").matcher(text);
        if (m.find()) {
            field(fields, "cin", m.group(1), 0.95, lineOf(text, m.start()));
        }
    }

    /** GSTIN — 2-digit state + 10-char PAN + entity/Z/check (15 chars). */
    private void gstin(Map<String, Object> fields, String text) {
        Matcher m = Pattern.compile("\\b([0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][0-9A-Z]Z[0-9A-Z])\\b").matcher(text);
        if (m.find()) {
            field(fields, "gstin", m.group(1), 0.96, lineOf(text, m.start()));
        }
    }

    /** Date of incorporation — the first date after an incorporation label (ISO / d-m-y / d Mon yyyy). */
    private void incorporationDate(Map<String, Object> fields, String text) {
        Matcher m = Pattern.compile(
                "(?i)(?:date\\s+of\\s+incorporation|incorporated\\s+on|incorporation\\s+date|date\\s+of\\s+registration)"
                        + "\\s*[:\\-]?\\s*"
                        + "(\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}"
                        + "|\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}"
                        + "|\\d{1,2}(?:st|nd|rd|th)?\\s+[A-Za-z]+,?\\s+\\d{4})")
                .matcher(text);
        if (m.find()) {
            field(fields, "incorporation_date", m.group(1).trim(), 0.90, lineOf(text, m.start()));
        }
    }

    /** Directors — captures the tail of a "directors" line and splits it into names. */
    private void directors(Map<String, Object> fields, String text) {
        Matcher m = Pattern.compile(
                "(?i)(?:names?\\s+of\\s+(?:the\\s+)?directors?|directors?|promoters?)\\s*[:\\-]\\s*([^\\r\\n]+)")
                .matcher(text);
        if (m.find()) {
            String raw = m.group(1).trim();
            List<String> names = new ArrayList<>();
            for (String part : raw.split("\\s*(?:,|;|/|\\band\\b|&)\\s*")) {
                String n = part.trim();
                // Keep only plausible names (avoid a trailing "and" fragment or a stray number).
                if (n.length() >= 2 && n.length() <= 80 && n.matches(".*[A-Za-z].*")) {
                    names.add(n);
                }
            }
            if (!names.isEmpty()) {
                field(fields, "directors", names, 0.82, lineOf(text, m.start()));
            }
        }
    }

    /** Captures the remainder of the line after {@code <label>[:-]} (trimmed + capped). */
    private void lineTail(Map<String, Object> fields, String text, String key, String labelRegex, double conf) {
        Matcher m = Pattern.compile("(?i)" + labelRegex + "\\s*[:\\-]\\s*([^\\r\\n]+)").matcher(text);
        if (m.find()) {
            String v = m.group(1).trim();
            if (v.length() > 200) {
                v = v.substring(0, 200).trim();
            }
            if (!v.isBlank()) {
                field(fields, key, v, conf, lineOf(text, m.start()));
            }
        }
    }

    private void field(Map<String, Object> fields, String key, Object value, double confidence, int sourceLine) {
        Map<String, Object> cell = new LinkedHashMap<>();
        cell.put("value", value);
        cell.put("confidence", confidence);
        cell.put("sourceLine", sourceLine);
        fields.put(key, cell);
    }

    /** 1-based line number of a char offset in {@code text}. */
    private static int lineOf(String text, int offset) {
        int line = 1;
        int end = Math.min(offset, text.length());
        for (int i = 0; i < end; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    // ------------------------------------------------- convenience accessors over the field map

    @SuppressWarnings("unchecked")
    private static String strOf(Object cell) {
        if (cell instanceof Map<?, ?> m) {
            Object v = m.get("value");
            if (v instanceof List) {
                return null;   // list-valued fields (directors) are surfaced separately
            }
            return v == null ? null : String.valueOf(v);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> directorsOf(Object cell) {
        if (cell instanceof Map<?, ?> m && m.get("value") instanceof List<?> l) {
            List<String> out = new ArrayList<>();
            for (Object o : l) {
                if (o != null) {
                    out.add(String.valueOf(o));
                }
            }
            return out;
        }
        return List.of();
    }

    // ------------------------------------------------- text extraction (PDF / plain text)

    private record Extracted(String text, int pageCount, String method) {
    }

    private Extracted extractText(MultipartFile file) {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        String ct = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            log.warn("Could not read uploaded file {}: {}", name, e.getMessage());
            return new Extracted("", 0, "EMPTY");
        }
        if (bytes == null || bytes.length == 0) {
            return new Extracted("", 0, "EMPTY");
        }
        boolean isPdf = ct.contains("pdf") || name.endsWith(".pdf");
        if (isPdf) {
            try (PDDocument doc = Loader.loadPDF(bytes)) {
                int pages = doc.getNumberOfPages();
                String text = new PDFTextStripper().getText(doc);
                return new Extracted(cap(text == null ? "" : text), pages, "PDFBOX");
            } catch (Exception e) {
                log.warn("PDFBox extraction failed for {}: {}", name, e.getMessage());
                return new Extracted("", 0, "PDFBOX");
            }
        }
        boolean isText = ct.startsWith("text/") || ct.contains("csv") || ct.contains("plain") || ct.contains("json")
                || name.endsWith(".txt") || name.endsWith(".csv") || name.endsWith(".md");
        String decoded = new String(bytes, StandardCharsets.UTF_8);
        if (isText || looksTextual(decoded)) {
            return new Extracted(cap(decoded), 1, "TEXT");
        }
        return new Extracted("", 0, "UNSUPPORTED");
    }

    /** Heuristic: treat a decode as textual when it has no NUL and is mostly printable. */
    private static boolean looksTextual(String s) {
        if (s.isEmpty() || s.indexOf('\0') >= 0) {
            return false;
        }
        int printable = 0;
        int len = Math.min(s.length(), 4096);
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t' || c >= 0x20) {
                printable++;
            }
        }
        return len > 0 && printable >= (int) (len * 0.85);
    }

    private static String cap(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > MAX_TEXT_CHARS ? s.substring(0, MAX_TEXT_CHARS) : s;
    }
}
