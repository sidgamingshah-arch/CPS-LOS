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
import com.helix.origination.entity.DocExtraction;
import com.helix.origination.entity.Document;
import com.helix.origination.entity.LoanApplication;
import com.helix.origination.repo.DocExtractionRepository;
import com.helix.origination.repo.DocumentRepository;
import com.helix.origination.repo.LoanApplicationRepository;
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

    private final DocumentRepository documents;
    private final DocExtractionRepository extractions;
    private final LoanApplicationRepository applications;
    private final AuditService audit;
    private final com.helix.common.governance.AiGovernanceClient governance;
    private final LlmClient llm;

    public DocIntelligenceService(DocumentRepository documents, DocExtractionRepository extractions,
                                  LoanApplicationRepository applications, AuditService audit,
                                  com.helix.common.governance.AiGovernanceClient governance, LlmClient llm) {
        this.documents = documents;
        this.extractions = extractions;
        this.applications = applications;
        this.audit = audit;
        this.governance = governance;
        this.llm = llm;
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

        Map<String, Object> fields = new LinkedHashMap<>();
        double conf = templateFields(type, fields);
        String model = "doc-intel-v1";

        // Optional LLM extraction: when a bank has configured an external model, it drafts the
        // extracted fields. CRITICAL GOVERNANCE: the output remains an advisory SUGGESTED
        // DocExtraction requiring human confirm — it is NEVER auto-applied to the deterministic
        // spread (confirm() records review accountability only, see below). Provider 'none'
        // (default) → deterministic template, model 'doc-intel-v1', byte-identical to today.
        LlmExtraction lx = llmExtract(type, doc.getFileName(), lang, fields, conf);
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
                                     Map<String, Object> deterministic, double conf) {
        String system = "You are an ADVISORY document-intelligence extractor for wholesale-credit "
                + "documents. Extract the key fields from the described document as a flat JSON object of "
                + "field -> value. This is a SUGGESTION only: a named human will review and confirm it, and "
                + "it is NEVER written directly into any financial figure. Return ONLY a JSON object.";
        String user = "Document type: " + type + "\nFile name: " + fileName + "\nDetected language: " + lang
                + "\nExpected fields (hint): " + deterministic.keySet();
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
        // Human accountability for the AI suggestion — NOT pushed into the figure path.
        audit.human(actor, "DOC_EXTRACTION_CONFIRMED", "Application", e.getApplicationReference(),
                "Confirmed AI extraction #%d (review only — figures stay human-spread)".formatted(extractionId),
                Map.of("extractionId", extractionId));
        return saved;
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
        audit.ai("language-normalisation", "TEXT_NORMALISED", "Text", "n/a",
                "Rewrote %d chars → %s register (advisory)".formatted(text.length(), tgt),
                Map.of("target", tgt, "advisory", true));
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
        audit.ai("translation", "TEXT_TRANSLATED", "Text", "n/a",
                "Translated %s→%s (advisory, %.2f)".formatted(src, tgt, conf),
                Map.of("source", src, "target", tgt, "advisory", true));
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
        boolean passed = findings.stream().noneMatch(x -> "ERROR".equals(x.level()) || "WARN".equals(x.level()));
        audit.ai("document-checks", "DOC_CHECKED", "Application", ref,
                "%d finding(s) on %s — advisory".formatted(findings.size(), doc.getFileName()),
                Map.of("documentId", docId, "passed", passed, "advisory", true));
        return new DocCheckResponse(docId, doc.getClassifiedType(), passed, findings, true);
    }

    // --------------------------------------------------- heuristics (stand-ins for the LLM)

    private double templateFields(String type, Map<String, Object> fields) {
        switch (type) {
            case "FINANCIAL_STATEMENT" -> {
                field(fields, "reporting_period", "FY2025", 0.94, 1);
                field(fields, "revenue", 1_250_000_000L, 0.91, 3);
                field(fields, "ebitda", 312_000_000L, 0.88, 4);
                field(fields, "total_debt", 540_000_000L, 0.86, 7);
                field(fields, "auditor", "BigFour LLP", 0.93, 1);
                return 0.90;
            }
            case "KYC_ID" -> {
                field(fields, "legal_name", "ACME Industries Ltd", 0.97, 1);
                field(fields, "registration_number", "U12345MH2010PLC000111", 0.95, 1);
                field(fields, "incorporation_date", "2010-04-12", 0.92, 1);
                field(fields, "registered_address", "Plot 42, MIDC, Mumbai", 0.89, 2);
                return 0.93;
            }
            case "FACILITY_DOC", "SECURITY_DOC" -> {
                field(fields, "borrower", "ACME Industries Ltd", 0.95, 1);
                field(fields, "facility_amount", 500_000_000L, 0.9, 2);
                field(fields, "tenor_months", 60, 0.9, 2);
                field(fields, "governing_law", "Laws of India", 0.92, 14);
                field(fields, "security", "First pari-passu charge on fixed assets", 0.84, 6);
                return 0.90;
            }
            case "BANK_STATEMENT" -> {
                field(fields, "account_number", "•••• 4521", 0.96, 1);
                field(fields, "average_balance", 18_400_000L, 0.85, 1);
                field(fields, "inward_returns", 0, 0.9, 2);
                return 0.90;
            }
            case "TAX_GST" -> {
                field(fields, "gstin", "27ABCDE1234F1Z5", 0.96, 1);
                field(fields, "annual_turnover", 1_300_000_000L, 0.87, 1);
                return 0.91;
            }
            default -> {
                field(fields, "document_title", "Unstructured document", 0.7, 1);
                return 0.70;
            }
        }
    }

    private void field(Map<String, Object> fields, String key, Object value, double confidence, int page) {
        fields.put(key, Map.of("value", value, "confidence", confidence, "sourcePage", page));
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
