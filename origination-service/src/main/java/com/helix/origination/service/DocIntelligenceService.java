package com.helix.origination.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helix.common.audit.AuditService;
import com.helix.common.llm.LlmClient;
import com.helix.common.llm.LlmRequest;
import com.helix.common.llm.LlmResult;
import com.helix.common.web.ApiException;
import com.helix.origination.dto.DocIntelDtos.DocCheckFinding;
import com.helix.origination.dto.DocIntelDtos.DocCheckResponse;
import com.helix.origination.dto.DocIntelDtos.NormaliseResponse;
import com.helix.origination.dto.DocIntelDtos.TranslateResponse;
import com.helix.origination.dto.Dtos.SpreadFromExtractionRequest;
import com.helix.origination.entity.DocExtraction;
import com.helix.origination.entity.Document;
import com.helix.origination.entity.LoanApplication;
import com.helix.origination.repo.DocExtractionRepository;
import com.helix.origination.repo.DocumentRepository;
import com.helix.origination.repo.LoanApplicationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GenAI document intelligence (PRD: multilingual extraction, casual→legal language,
 * document checks). All outputs are <b>AI suggestions</b> — audited as AI events,
 * gated by a human confirm, and never auto-applied to the deterministic figure path.
 * The heuristics here stand in for the LLM/extraction service at the platform boundary.
 */
@Service
public class DocIntelligenceService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(DocIntelligenceService.class);

    private final DocumentRepository documents;
    private final DocExtractionRepository extractions;
    private final LoanApplicationRepository applications;
    private final AuditService audit;
    private final com.helix.common.governance.AiGovernanceClient governance;
    private final LlmClient llm;
    /** Spread engine — used to AUTO-draft a spread from a confirmed FS extraction. {@code @Lazy}
     *  keeps the wiring cycle-free and the draft is advisory (never confirms the spread). */
    private final OriginationService origination;

    public DocIntelligenceService(DocumentRepository documents, DocExtractionRepository extractions,
                                  LoanApplicationRepository applications, AuditService audit,
                                  com.helix.common.governance.AiGovernanceClient governance, LlmClient llm,
                                  @Lazy OriginationService origination) {
        this.documents = documents;
        this.extractions = extractions;
        this.applications = applications;
        this.audit = audit;
        this.governance = governance;
        this.llm = llm;
        this.origination = origination;
    }

    // --------------------------------------------------- extraction (suggest → confirm)

    @Transactional
    public DocExtraction extract(Long docId, String actor) {
        Document doc = documents.findById(docId).orElseThrow(() -> ApiException.notFound("No document: " + docId));
        LoanApplication app = applications.findById(doc.getApplicationId()).orElse(null);
        String ref = app == null ? "?" : app.getReference();
        // Gate: doc-intel is a governed AI capability. The jurisdiction-scoped check
        // means a bank can switch AI off for, say, CBUAE while keeping it on for RBI.
        governance.enforce(com.helix.common.governance.AiCapability.DOC_INTEL,
                app == null ? null : app.getJurisdiction());
        String type = doc.getClassifiedType() == null ? "OTHER" : doc.getClassifiedType();
        String lang = detectLanguage(doc.getFileName());
        String docText = doc.getExtractedText();

        Map<String, Object> fields = new LinkedHashMap<>();
        double conf;
        String model;
        boolean contentDerived = false;
        // HONEST EXTRACTION: when the document's REAL text is present (uploaded via the multipart
        // /documents/upload path → PDFBox / UTF-8 / OCR), derive the fields FROM that text with a
        // deterministic content parser. The values then come from the document itself, not a
        // per-deal template. If the text yields nothing parseable we fall back to the template so a
        // demo is never empty. Either way the result is a SUGGESTED, human-confirmed advisory.
        if (docText != null && !docText.isBlank()) {
            int found = parseFieldsFromText(type, docText, fields);
            if (found > 0) {
                contentDerived = true;
                conf = 0.90;                     // overall confidence of the content parse
                model = "doc-intel-ocr-v1";      // marks the extraction as document-derived (for the UI)
            } else {
                conf = templateFields(type, fields, app);
                model = "doc-intel-v1";
            }
        } else {
            conf = templateFields(type, fields, app);
            model = "doc-intel-v1";
        }

        // Optional LLM extraction: when a bank has configured an external model, it drafts the
        // extracted fields — now GROUNDED in the document's real text (passed as the user prompt).
        // CRITICAL GOVERNANCE: the output remains an advisory SUGGESTED DocExtraction requiring
        // human confirm — it is NEVER auto-applied to the deterministic spread (confirm() records
        // review accountability only, see below). Provider 'none' (default) → the content parser
        // above (or the deterministic template), byte-identical to today for text-less documents.
        LlmExtraction lx = llmExtract(type, doc.getFileName(), lang, fields, conf, docText);
        boolean llmDrafted = lx != null;
        if (llmDrafted) {
            fields = lx.fields();
            model = lx.model();
        }

        DocExtraction e = new DocExtraction();
        e.setDocumentId(docId);
        e.setApplicationReference(ref);
        e.setClassifiedType(type);
        e.setDetectedLanguage(lang);
        e.setModel(model);
        e.setOverallConfidence(conf);
        e.setStatus("SUGGESTED");
        e.setFields(fields);
        DocExtraction saved = extractions.save(e);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("documentId", docId);
        detail.put("language", lang);
        detail.put("fields", fields.size());
        detail.put("advisory", true);
        detail.put("contentDerived", contentDerived);
        if (contentDerived) {
            detail.put("extractionMethod", doc.getExtractionMethod());
        }
        if (llmDrafted) {
            detail.put("llmDrafted", true);
        }
        audit.ai("document-intelligence", "DOC_EXTRACTED", "Application", ref,
                "Extracted %d field(s) from %s (%s, conf %.2f) — suggestion, human confirm required"
                        .formatted(fields.size(), doc.getFileName(), lang, conf), detail);
        return saved;
    }

    /**
     * Advisory LLM extraction of document fields. Returns a flat map of field ->
     * {@code {value, confidence, sourcePage}}, parsing a JSON object from the model when
     * possible, else storing the raw text under {@code ai_extraction}. Returns {@code null}
     * when not configured / failed / empty so the caller keeps the deterministic template.
     * The result is only ever persisted as a SUGGESTED extraction (never auto-applied).
     */
    private LlmExtraction llmExtract(String type, String fileName, String lang,
                                     Map<String, Object> deterministic, double conf, String extractedText) {
        String system = "You are an ADVISORY document-intelligence extractor for wholesale-credit "
                + "documents. Extract the key fields from the document as a flat JSON object of "
                + "field -> value. This is a SUGGESTION only: a named human will review and confirm it, and "
                + "it is NEVER written directly into any financial figure. Extract only what the text "
                + "actually states; do not invent values. Return ONLY a JSON object.";
        String user = "Document type: " + type + "\nFile name: " + fileName + "\nDetected language: " + lang
                + "\nExpected fields (hint): " + deterministic.keySet();
        // Ground the model in the document's REAL text so a configured external model actually reads
        // the document instead of guessing from the file name. Truncated to a sane slice.
        if (extractedText != null && !extractedText.isBlank()) {
            String slice = extractedText.length() > 6000 ? extractedText.substring(0, 6000) : extractedText;
            user = user + "\n\nDocument text follows:\n" + slice;
        }
        LlmResult r = safeComplete(LlmRequest.of("doc-extract", system, user));
        if (!r.usable()) {
            return null;
        }
        Map<String, Object> parsed = parseJsonObject(r.text());
        Map<String, Object> out = new LinkedHashMap<>();
        if (parsed != null && !parsed.isEmpty()) {
            for (Map.Entry<String, Object> en : parsed.entrySet()) {
                Object v = en.getValue();
                if (v instanceof Map) {
                    out.put(en.getKey(), v);
                } else {
                    out.put(en.getKey(), Map.of("value", v == null ? "" : v, "confidence", conf, "sourcePage", 0));
                }
            }
        } else {
            out.put("ai_extraction", Map.of("value", r.text().strip(), "confidence", conf, "sourcePage", 0));
        }
        return new LlmExtraction(out, r.model());
    }

    private Map<String, Object> parseJsonObject(String text) {
        if (text == null) {
            return null;
        }
        String t = text.strip();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) {
                t = t.substring(nl + 1);
            }
            if (t.endsWith("```")) {
                t = t.substring(0, t.length() - 3);
            }
            t = t.strip();
        }
        if (!t.startsWith("{")) {
            return null;
        }
        try {
            return JSON.readValue(t, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (Exception ex) {
            return null;
        }
    }

    private LlmResult safeComplete(LlmRequest req) {
        try {
            LlmResult r = llm.complete(req);
            return r == null ? LlmResult.notConfigured() : r;
        } catch (Exception e) {
            return LlmResult.failed(e.getMessage());
        }
    }

    /** LLM-drafted extraction fields + the model that produced them. */
    private record LlmExtraction(Map<String, Object> fields, String model) {
    }

    // ---------------------------------------- advisory LLM hooks (normalise / translate / checks)

    /**
     * Advisory LLM rewrite into the target register. Prose (never a JSON object). Reuses the
     * deterministic rewrite as a reference; the model quotes figures verbatim. Fail-soft via
     * {@link #safeComplete} — a non-usable result leaves the caller on its deterministic path.
     */
    private LlmResult llmNormalise(String original, String target, String deterministic) {
        String register = "LEGAL".equals(target) ? "formal legal contract" : "plain business";
        String system = "You are an ADVISORY language-normalisation assistant for wholesale-credit staff. "
                + "Rewrite the user's text into " + register + " register, preserving its meaning. Reuse any "
                + "figures, dates, names and defined terms verbatim and never invent facts. The rewrite is "
                + "advisory and human-reviewed; it sets no figure or decision. Reply with only the rewritten "
                + "text. capability=language-normalise";
        String user = "Target register: " + target + "\nOriginal text:\n" + original
                + "\n\nDeterministic reference rewrite (improve the wording, keep the meaning):\n" + deterministic;
        return safeComplete(LlmRequest.of("language-normalise", system, user));
    }

    /** Advisory LLM translation — only invoked when the source and target languages differ. */
    private LlmResult llmTranslate(String original, String src, String tgt) {
        String system = "You are an ADVISORY translation assistant for wholesale-credit documents. Translate "
                + "the user's text from " + src + " to " + tgt + ", preserving meaning. Reuse all figures, "
                + "dates, names and account identifiers verbatim and never invent facts. The translation is "
                + "advisory and human-reviewed; it sets no figure or decision. Reply with only the translated "
                + "text. capability=translation";
        String user = "Source language: " + src + "\nTarget language: " + tgt + "\nText:\n" + original;
        return safeComplete(LlmRequest.of("translation", system, user));
    }

    /** Advisory LLM redraft of one document-check finding's explanation MESSAGE (severity/code fixed). */
    private LlmResult llmExplainCheck(String fileName, String classifiedType, String level,
                                      String code, String message) {
        String system = "You are an ADVISORY document-check explainer for wholesale-credit staff. Rewrite the "
                + "given finding into one clear sentence for a reviewer. Do NOT change the finding's severity "
                + "or code, and reuse any figures and type names verbatim; invent nothing. The explanation is "
                + "advisory and sets no figure or decision. Reply with only the sentence. capability=doc-checks";
        String user = "Document: " + nz(fileName) + "\nClassified type: " + nz(classifiedType)
                + "\nSeverity: " + level + "\nCode: " + code + "\nDeterministic finding: " + message;
        return safeComplete(LlmRequest.of("doc-checks", system, user));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    @Transactional
    public DocExtraction confirm(Long extractionId, String note, String actor) {
        DocExtraction e = get(extractionId);
        if (!"SUGGESTED".equals(e.getStatus())) {
            throw ApiException.conflict("Extraction is " + e.getStatus());
        }
        e.setStatus("CONFIRMED");
        e.setReviewedBy(actor);
        e.setReviewedAt(Instant.now());
        e.setReviewNote(note);
        DocExtraction saved = extractions.save(e);
        // Human accountability for the AI suggestion — the confirm records review, it does NOT set
        // an authoritative figure.
        audit.human(actor, "DOC_EXTRACTION_CONFIRMED", "Application", e.getApplicationReference(),
                "Confirmed AI extraction #%d (review — figures stay a human-confirmed spread)".formatted(extractionId),
                Map.of("extractionId", extractionId));
        // NOTE: the advisory auto-draft is intentionally NOT done here (inside confirm()'s transaction).
        // It runs top-level from the controller AFTER this transaction commits — see
        // autoDraftAfterConfirm() — because spreadFromExtraction is itself @Transactional and the
        // SQLite pool is size 1: nesting it (or an afterCommit hook, which still holds the connection
        // until afterCompletion) would deadlock / fail to acquire a connection, and a rollback-only
        // inner tx would 500 the confirm and lose the CONFIRMED status + audit.
        return saved;
    }

    /**
     * AI LARGER ROLE (advisory): after a FINANCIAL_STATEMENT extraction is CONFIRMED, auto-draft the
     * spread from it so the analyst need not click "populate grid" separately. Strictly advisory —
     * spreadFromExtraction rebuilds an UNCONFIRMED DRAFT (spreadConfirmed=false); the authoritative
     * confirmSpread gate is untouched. Called TOP-LEVEL by the controller AFTER confirm() has
     * committed (so it opens its own transaction on a free connection — no pool-1 deadlock). Fail-soft
     * (never surfaces an error to the confirm). GUARD: only populates an EMPTY grid — never replaces
     * an analyst's existing manual draft (hasSpread) or a confirmed authoritative spread.
     */
    public void autoDraftAfterConfirm(Long extractionId, String actor) {
        DocExtraction ext = extractions.findById(extractionId).orElse(null);
        if (ext == null || !"CONFIRMED".equals(ext.getStatus())
                || !"FINANCIAL_STATEMENT".equals(ext.getClassifiedType())) {
            return;
        }
        String ref = ext.getApplicationReference();
        if (ref == null) {
            return;
        }
        try {
            if (origination.hasSpread(ref)) {
                return;   // an existing manual/confirmed spread must never be clobbered by a review
            }
            origination.spreadFromExtraction(ref,
                    new SpreadFromExtractionRequest(extractionId, null, null, null,
                            "Auto-drafted from confirmed extraction #" + extractionId),
                    actor);
        } catch (Exception e) {
            log.warn("Auto-draft from confirmed extraction #{} skipped (non-fatal): {}",
                    extractionId, e.getMessage());
        }
    }

    @Transactional
    public DocExtraction reject(Long extractionId, String reason, String actor) {
        DocExtraction e = get(extractionId);
        e.setStatus("REJECTED");
        e.setReviewedBy(actor);
        e.setReviewedAt(Instant.now());
        e.setReviewNote(reason);
        DocExtraction saved = extractions.save(e);
        audit.human(actor, "DOC_EXTRACTION_REJECTED", "Application", e.getApplicationReference(),
                "Rejected AI extraction #%d: %s".formatted(extractionId, reason), Map.of("extractionId", extractionId));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<DocExtraction> extractionsFor(Long docId) {
        return extractions.findByDocumentIdOrderByIdDesc(docId);
    }

    // --------------------------------------------------- language normalisation + translation

    @Transactional
    public NormaliseResponse normalise(String text, String target, String actor) {
        if (text == null || text.isBlank()) throw ApiException.badRequest("text required");
        String tgt = target == null ? "LEGAL" : target.toUpperCase();
        List<String> notes = new ArrayList<>();
        String out = "LEGAL".equals(tgt) ? toLegal(text, notes) : toPlain(text, notes);
        // Optional advisory LLM rewrite: when an external model is configured it produces the
        // normalised text grounded in the SAME original + target register. The output is advisory
        // (no figure or decision). Provider 'none' (default) or an unusable reply → the deterministic
        // rewrite, byte-identical to today.
        boolean llmDrafted = false;
        String llmModel = null;
        LlmResult r = llmNormalise(text, tgt, out);
        if (r.usable()) {
            out = r.text().strip();
            notes = new ArrayList<>(List.of("Normalised by the configured external model (advisory)"));
            llmDrafted = true;
            llmModel = r.model();
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("target", tgt);
        detail.put("advisory", true);
        if (llmDrafted) {
            detail.put("llmDrafted", true);
            detail.put("llmModel", llmModel);
        }
        audit.ai("language-normalisation", "TEXT_NORMALISED", "Text", "n/a",
                "Rewrote %d chars → %s register (advisory)".formatted(text.length(), tgt), detail);
        return new NormaliseResponse(tgt, text, out, notes, true);
    }

    @Transactional
    public TranslateResponse translate(String text, String targetLanguage, String actor) {
        if (text == null || text.isBlank()) throw ApiException.badRequest("text required");
        String src = detectLanguageFromText(text);
        String tgt = targetLanguage == null ? "en" : targetLanguage.toLowerCase();
        String translated = src.equals(tgt)
                ? text
                : "[" + tgt + "] " + glossaryTranslate(text, tgt);
        double conf = src.equals(tgt) ? 1.0 : 0.78;
        // Optional advisory LLM translation, only when the languages differ. The confidence stays
        // deterministic; the model only drafts the translated TEXT (advisory). Provider 'none'
        // (default) or an unusable reply → the deterministic glossary passthrough, byte-identical.
        boolean llmDrafted = false;
        String llmModel = null;
        if (!src.equals(tgt)) {
            LlmResult r = llmTranslate(text, src, tgt);
            if (r.usable()) {
                translated = r.text().strip();
                llmDrafted = true;
                llmModel = r.model();
            }
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("source", src);
        detail.put("target", tgt);
        detail.put("advisory", true);
        if (llmDrafted) {
            detail.put("llmDrafted", true);
            detail.put("llmModel", llmModel);
        }
        audit.ai("translation", "TEXT_TRANSLATED", "Text", "n/a",
                "Translated %s→%s (advisory, %.2f)".formatted(src, tgt, conf), detail);
        return new TranslateResponse(src, tgt, text, translated, conf, true);
    }

    // --------------------------------------------------- document checks

    @Transactional
    public DocCheckResponse checks(Long docId, String actor) {
        Document doc = documents.findById(docId).orElseThrow(() -> ApiException.notFound("No document: " + docId));
        String ref = applications.findById(doc.getApplicationId()).map(LoanApplication::getReference).orElse("?");
        List<DocCheckFinding> findings = new ArrayList<>();

        if (doc.getClassificationConfidence() < 0.85) {
            findings.add(new DocCheckFinding("WARN", "LOW_CLASSIFICATION_CONFIDENCE",
                    "Classification confidence %.2f below 0.85 — verify the document type".formatted(doc.getClassificationConfidence())));
        }
        if (doc.getDeclaredType() != null && doc.getClassifiedType() != null
                && !doc.getDeclaredType().equalsIgnoreCase(doc.getClassifiedType())) {
            findings.add(new DocCheckFinding("WARN", "TYPE_MISMATCH",
                    "Declared '%s' but classified '%s'".formatted(doc.getDeclaredType(), doc.getClassifiedType())));
        }
        boolean hasExtraction = !extractions.findByDocumentIdOrderByIdDesc(docId).isEmpty();
        if (!hasExtraction) {
            findings.add(new DocCheckFinding("INFO", "NO_EXTRACTION",
                    "No extraction run yet — run document-intelligence extraction"));
        }
        if (("FACILITY_DOC".equals(doc.getClassifiedType()) || "SECURITY_DOC".equals(doc.getClassifiedType()))
                && !doc.isVerified()) {
            findings.add(new DocCheckFinding("WARN", "SIGNATURE_UNVERIFIED",
                    "Legal/security document not yet verified — execution/signature check pending"));
        }
        if (findings.isEmpty()) {
            findings.add(new DocCheckFinding("OK", "CLEAN", "No issues detected"));
        }
        // The passed verdict is derived from the DETERMINISTIC finding levels — computed here,
        // before any advisory redraft, so it can never move.
        boolean passed = findings.stream().noneMatch(x -> "ERROR".equals(x.level()) || "WARN".equals(x.level()));

        // Optional advisory LLM redraft of each finding's explanation MESSAGE only. The detection
        // (which findings exist, their level + code) and the passed verdict stay deterministic; the
        // model may only reword the human-readable explanation, quoting the figures verbatim.
        // Provider 'none' (default) or an unusable reply → the deterministic messages, byte-identical.
        boolean llmDrafted = false;
        String llmModel = null;
        List<DocCheckFinding> drafted = new ArrayList<>(findings.size());
        for (DocCheckFinding f : findings) {
            LlmResult r = llmExplainCheck(doc.getFileName(), doc.getClassifiedType(), f.level(), f.code(), f.message());
            if (r.usable()) {
                drafted.add(new DocCheckFinding(f.level(), f.code(), r.text().strip()));
                llmDrafted = true;
                llmModel = r.model();
            } else {
                drafted.add(f);
            }
        }
        findings = drafted;

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("documentId", docId);
        detail.put("passed", passed);
        detail.put("advisory", true);
        if (llmDrafted) {
            detail.put("llmDrafted", true);
            detail.put("llmModel", llmModel);
        }
        audit.ai("document-checks", "DOC_CHECKED", "Application", ref,
                "%d finding(s) on %s — advisory".formatted(findings.size(), doc.getFileName()), detail);
        return new DocCheckResponse(docId, doc.getClassifiedType(), passed, findings, true);
    }

    // --------------------------------------------------- heuristics (stand-ins for the LLM)

    /**
     * Deterministic stand-in for the extraction model when no external LLM is configured.
     * <p>The sample field <b>values</b> are derived per deal from a stable hash of the
     * borrower/segment/requested-amount so a demo across several applications shows
     * <em>different, plausible</em> figures instead of one canned constant ("why are the
     * numbers the same?"). The field <b>keys</b>, the per-field confidences and the returned
     * overall confidence are unchanged per document type, so the extraction contract and every
     * existing assertion hold. This is still a governed ADVISORY suggestion: the caller persists
     * it as a SUGGESTED {@link DocExtraction} behind a human confirm and never writes it into any
     * authoritative figure.
     */
    private double templateFields(String type, Map<String, Object> fields, LoanApplication app) {
        long seed = seedOf(app);
        String name = app == null || app.getCounterpartyName() == null || app.getCounterpartyName().isBlank()
                ? "ACME Industries Ltd" : app.getCounterpartyName();
        double base = app == null || app.getRequestedAmount() <= 0 ? 500_000_000.0 : app.getRequestedAmount();
        switch (type) {
            case "FINANCIAL_STATEMENT" -> {
                double revMult = 1.6 + (seed % 22) / 10.0;              // 1.6 .. 3.7x the requested amount
                double margin = 0.12 + ((seed / 7) % 17) / 100.0;       // 12% .. 28% EBITDA margin
                double debtMult = 0.7 + ((seed / 13) % 13) / 10.0;      // 0.7 .. 1.9x
                long revenue = roundMillion(base * revMult);
                field(fields, "reporting_period", "FY" + (2024 + (int) (seed % 2)), 0.94, 1);
                field(fields, "revenue", revenue, 0.91, 3);
                field(fields, "ebitda", roundMillion(revenue * margin), 0.88, 4);
                field(fields, "total_debt", roundMillion(base * debtMult), 0.86, 7);
                field(fields, "auditor", AUDITORS[(int) (seed % AUDITORS.length)], 0.93, 1);
                return 0.90;
            }
            case "KYC_ID" -> {
                String cin = "U%05dMH%04dPLC%06d".formatted(seed % 100000L, 2005 + (seed % 18L), seed % 1000000L);
                int y = 2004 + (int) (seed % 20), mo = 1 + (int) ((seed / 3) % 12), day = 1 + (int) ((seed / 11) % 28);
                field(fields, "legal_name", name, 0.97, 1);
                field(fields, "registration_number", cin, 0.95, 1);
                field(fields, "incorporation_date", "%04d-%02d-%02d".formatted(y, mo, day), 0.92, 1);
                field(fields, "registered_address", ADDRESSES[(int) (seed % ADDRESSES.length)], 0.89, 2);
                return 0.93;
            }
            case "FACILITY_DOC", "SECURITY_DOC" -> {
                field(fields, "borrower", name, 0.95, 1);
                field(fields, "facility_amount", roundMillion(base), 0.9, 2);
                field(fields, "tenor_months", app == null || app.getTenorMonths() <= 0 ? 60 : app.getTenorMonths(), 0.9, 2);
                field(fields, "governing_law", governingLaw(app), 0.92, 14);
                field(fields, "security", "First pari-passu charge on fixed assets", 0.84, 6);
                return 0.90;
            }
            case "BANK_STATEMENT" -> {
                field(fields, "account_number", "•••• %04d".formatted(seed % 10000L), 0.96, 1);
                field(fields, "average_balance", roundMillion(base * (0.02 + (seed % 8) / 100.0)), 0.85, 1);
                field(fields, "inward_returns", (int) (seed % 3), 0.9, 2);
                return 0.90;
            }
            case "TAX_GST" -> {
                field(fields, "gstin", "%02dABCDE%04dF1Z%d".formatted(1 + (seed % 37L), seed % 10000L, seed % 10L), 0.96, 1);
                field(fields, "annual_turnover", roundMillion(base * (1.6 + (seed % 20) / 10.0)), 0.87, 1);
                return 0.91;
            }
            default -> {
                field(fields, "document_title", "Unstructured document", 0.7, 1);
                return 0.70;
            }
        }
    }

    /** Sample auditor names for the deterministic FS extraction (generic, not real firms). */
    private static final String[] AUDITORS = {
            "BigFour LLP", "Sterling Audit LLP", "Meridian Assurance", "Anchor Chartered Accountants",
            "Crestview Auditors LLP", "Pinnacle & Partners"};
    /** Sample registered addresses for the deterministic KYC extraction. */
    private static final String[] ADDRESSES = {
            "Plot 42, MIDC, Mumbai", "Sector 18, Gurugram", "Whitefield, Bengaluru",
            "GIFT City, Gandhinagar", "Guindy, Chennai", "Salt Lake, Kolkata"};

    /** Stable, sign-safe per-deal hash so sample values vary by borrower/segment but stay deterministic. */
    private static long seedOf(LoanApplication app) {
        String basis = app == null ? ""
                : (nz(app.getCounterpartyRef()) + "|" + nz(app.getReference()) + "|" + nz(app.getSegment()));
        if (basis.isBlank()) basis = "ACME";
        long h = 1125899906842597L;
        for (int i = 0; i < basis.length(); i++) {
            h = 31 * h + basis.charAt(i);
        }
        return h & Long.MAX_VALUE;
    }

    /** Rounds to the nearest million so the sample figures read like reported financials. */
    private static long roundMillion(double v) {
        return Math.round(v / 1_000_000.0) * 1_000_000L;
    }

    private static String governingLaw(LoanApplication app) {
        String j = app == null || app.getJurisdiction() == null ? "" : app.getJurisdiction().toUpperCase();
        return j.contains("CBUAE") || j.contains("AE") ? "Laws of the UAE (DIFC)" : "Laws of India";
    }

    private void field(Map<String, Object> fields, String key, Object value, double confidence, int page) {
        fields.put(key, Map.of("value", value, "confidence", confidence, "sourcePage", page));
    }

    // --------------------------------------------- deterministic content parser (from real text)

    /**
     * Derives extraction fields FROM the document's real text using case-insensitive regex, keyed
     * to the document {@code type}. Each hit is stored via {@link #field} with the 1-based LINE
     * NUMBER where it was found as the source citation, so the UI can point at the evidence. This
     * is deterministic PARSING (not generation): every value is copied verbatim from the text.
     * Returns the number of fields recognised — 0 means the caller should fall back to the template
     * so a demo is never empty. The result is still a governed SUGGESTED advisory extraction.
     */
    int parseFieldsFromText(String type, String text, Map<String, Object> fields) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int before = fields.size();
        switch (type == null ? "OTHER" : type) {
            case "FINANCIAL_STATEMENT" -> {
                money(fields, text, "revenue",
                        "(?:total\\s+revenue|revenue\\s+from\\s+operations|revenue|turnover|net\\s+sales|sales)", 0.90);
                money(fields, text, "ebitda", "ebitda", 0.88);
                money(fields, text, "total_debt",
                        "(?:total\\s+debt|total\\s+borrowings|gross\\s+debt)", 0.86);
                period(fields, text);
                lineTail(fields, text, "auditor", "(?:statutory\\s+auditor|auditor)s?", 0.90);
            }
            case "TAX_GST" -> {
                gstin(fields, text);
                money(fields, text, "annual_turnover",
                        "(?:aggregate\\s+turnover|annual\\s+turnover|turnover)", 0.87);
            }
            case "BANK_STATEMENT" -> {
                lineTail(fields, text, "account_number",
                        "(?:account\\s*(?:no|number)|a/c\\s*no)\\.?\\s*[:\\-]?", 0.90);
                money(fields, text, "average_balance", "(?:average\\s+balance|avg\\s+balance)", 0.82);
            }
            case "KYC_ID", "MOA_AOA" -> {
                cin(fields, text);
                lineTail(fields, text, "legal_name",
                        "(?:name\\s+of\\s+(?:the\\s+)?company|legal\\s+name|entity\\s+name)", 0.88);
            }
            case "FACILITY_DOC", "SECURITY_DOC" -> {
                money(fields, text, "facility_amount",
                        "(?:facility\\s+amount|sanctioned\\s+amount|loan\\s+amount)", 0.88);
                lineTail(fields, text, "borrower", "(?:name\\s+of\\s+borrower|borrower|obligor)", 0.85);
            }
            default -> {
                // Unknown type: still attempt the common financial signals + identifiers.
                money(fields, text, "revenue", "(?:total\\s+revenue|revenue|turnover)", 0.85);
                gstin(fields, text);
                cin(fields, text);
                period(fields, text);
            }
        }
        return fields.size() - before;
    }

    /** Matches {@code <label> [:-] [currency] <number>}; stores the number (long when integral). */
    private void money(Map<String, Object> fields, String text, String key, String labelRegex, double conf) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?i)" + labelRegex + "\\s*[:\\-]?\\s*(?:(?:rs|inr|usd|\\$|₹|aed)\\.?\\s*)?([0-9][0-9,]*(?:\\.[0-9]+)?)")
                .matcher(text);
        if (m.find()) {
            String cleaned = m.group(1).replace(",", "");
            try {
                double d = Double.parseDouble(cleaned);
                Object val = (d == Math.floor(d) && !Double.isInfinite(d)
                        && Math.abs(d) < 9.0e18) ? (Object) (long) d : (Object) d;
                field(fields, key, val, conf, lineOf(text, m.start()));
            } catch (NumberFormatException ignored) {
                // not a parseable number — skip this field rather than invent one
            }
        }
    }

    /** Reporting period like {@code FY2025} / {@code FY 2024-25}. */
    private void period(Map<String, Object> fields, String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?i)\\bFY\\s?(20\\d\\d(?:\\s?[-/]\\s?\\d{2,4})?)").matcher(text);
        if (m.find()) {
            field(fields, "reporting_period", "FY" + m.group(1).replaceAll("\\s", ""), 0.94, lineOf(text, m.start()));
        }
    }

    /** GSTIN — 2-digit state + 10-char PAN + entity/Z/check (15 chars). */
    private void gstin(Map<String, Object> fields, String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\\b([0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][0-9A-Z]Z[0-9A-Z])\\b").matcher(text);
        if (m.find()) {
            field(fields, "gstin", m.group(1), 0.96, lineOf(text, m.start()));
        }
    }

    /** CIN — 21-char corporate identity number (e.g. U74999MH2015PLC123456). */
    private void cin(Map<String, Object> fields, String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\\b([LU][0-9]{5}[A-Z]{2}[0-9]{4}[A-Z]{3}[0-9]{6})\\b").matcher(text);
        if (m.find()) {
            field(fields, "cin", m.group(1), 0.95, lineOf(text, m.start()));
        }
    }

    /** Captures the remainder of the line after {@code <label>[:-]} (trimmed + capped). */
    private void lineTail(Map<String, Object> fields, String text, String key, String labelRegex, double conf) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?i)" + labelRegex + "\\s*[:\\-]?\\s*([^\\r\\n]+)").matcher(text);
        if (m.find()) {
            String v = m.group(1).trim();
            if (v.length() > 120) {
                v = v.substring(0, 120).trim();
            }
            if (!v.isBlank()) {
                field(fields, key, v, conf, lineOf(text, m.start()));
            }
        }
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

    private String detectLanguage(String fileName) {
        if (fileName == null) return "en";
        String f = fileName.toLowerCase();
        if (f.contains("_ar") || f.contains("arabic")) return "ar";
        if (f.contains("_hi") || f.contains("hindi")) return "hi";
        if (f.contains("_fr")) return "fr";
        return "en";
    }

    private String detectLanguageFromText(String text) {
        for (char c : text.toCharArray()) {
            if (c >= 0x0600 && c <= 0x06FF) return "ar";    // Arabic block
            if (c >= 0x0900 && c <= 0x097F) return "hi";    // Devanagari block
        }
        return "en";
    }

    private String glossaryTranslate(String text, String tgt) {
        // Boundary stand-in: a real engine would call the translation provider here.
        return text;
    }

    private String toLegal(String text, List<String> notes) {
        String out = text.trim();
        out = replaceWord(out, "we'll", "the Lender shall", notes, "expanded contraction → obligation");
        out = replaceWord(out, "you'll", "the Borrower shall", notes, "expanded contraction → obligation");
        out = replaceWord(out, "asap", "without undue delay", notes, "informal → legal phrasing");
        out = replaceWord(out, "ok", "acceptable", notes, "informal → formal");
        out = replaceWord(out, "deal", "facility", notes, "colloquial → defined term");
        out = replaceWord(out, "must", "shall", notes, "imperative → covenant register");
        if (!out.endsWith(".")) out = out + ".";
        if (notes.isEmpty()) notes.add("No informal phrasing detected; minor formatting applied");
        return capitalise(out);
    }

    private String toPlain(String text, List<String> notes) {
        String out = text.trim();
        out = replaceWord(out, "shall", "must", notes, "legal → plain");
        out = replaceWord(out, "heretofore", "until now", notes, "archaic → plain");
        out = replaceWord(out, "notwithstanding", "despite", notes, "legalese → plain");
        out = replaceWord(out, "pursuant to", "under", notes, "legalese → plain");
        if (notes.isEmpty()) notes.add("Already plain; no changes needed");
        return capitalise(out);
    }

    private String replaceWord(String s, String from, String to, List<String> notes, String note) {
        String replaced = s.replaceAll("(?i)\\b" + java.util.regex.Pattern.quote(from) + "\\b", to);
        if (!replaced.equals(s)) notes.add("'" + from + "' → '" + to + "' (" + note + ")");
        return replaced;
    }

    private String capitalise(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private DocExtraction get(Long id) {
        return extractions.findById(id).orElseThrow(() -> ApiException.notFound("No extraction: " + id));
    }
}
