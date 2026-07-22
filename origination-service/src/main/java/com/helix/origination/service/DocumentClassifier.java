package com.helix.origination.service;

import com.helix.common.model.Enums.DocumentType;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Document-intelligence classification (PRD §6.1). Simulates an extraction model
 * that types a document with a confidence; the caller routes low-confidence
 * results to human review rather than silently accepting them.
 */
@Component
public class DocumentClassifier {

    public static final double AUTO_ROUTE_THRESHOLD = 0.85;

    public record Classification(DocumentType type, double confidence) {
    }

    public Classification classify(String fileName, String declaredType) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);

        if (declaredType != null && !declaredType.isBlank()) {
            try {
                // A declared type is treated as high-confidence ground truth.
                return new Classification(DocumentType.valueOf(declaredType.toUpperCase(Locale.ROOT)), 0.99);
            } catch (IllegalArgumentException ignored) {
                // fall through to content-based classification
            }
        }
        return classifyByName(name);
    }

    /**
     * Content-aware classification: the filename/declared-type logic first (unchanged), then — when
     * that is unsure (OTHER or below the auto-route threshold) — a keyword match over the document's
     * REAL extracted text. Returns whichever result carries the higher confidence, so a well-named
     * file keeps its label while a mis-/un-named file gets typed from what it actually contains.
     * Additive: the old 2-arg {@link #classify(String, String)} is unchanged for text-less callers.
     */
    public Classification classify(String fileName, String declaredType, String extractedText) {
        Classification byName = classify(fileName, declaredType);
        // A declared type is authoritative ground truth — never second-guess it from the text.
        if (declaredType != null && !declaredType.isBlank() && byName.confidence() >= 0.99) {
            return byName;
        }
        if (byName.type() != DocumentType.OTHER && byName.confidence() >= AUTO_ROUTE_THRESHOLD) {
            return byName;
        }
        Classification byText = classifyByText(extractedText);
        return byText.confidence() > byName.confidence() ? byText : byName;
    }

    private Classification classifyByName(String name) {
        if (contains(name, "balance", "p&l", "pnl", "financ", "annual", "audited")) {
            return new Classification(DocumentType.FINANCIAL_STATEMENT, 0.96);
        }
        if (contains(name, "moa", "aoa", "incorp", "memorandum", "articles")) {
            return new Classification(DocumentType.MOA_AOA, 0.93);
        }
        if (contains(name, "bureau", "cibil", "experian", "credit-report")) {
            return new Classification(DocumentType.BUREAU_REPORT, 0.94);
        }
        if (contains(name, "bank", "statement")) {
            return new Classification(DocumentType.BANK_STATEMENT, 0.9);
        }
        if (contains(name, "gst", "tax", "itr")) {
            return new Classification(DocumentType.TAX_GST, 0.9);
        }
        if (contains(name, "facility", "sanction", "loan-agreement")) {
            return new Classification(DocumentType.FACILITY_DOC, 0.88);
        }
        if (contains(name, "security", "charge", "mortgage", "pledge")) {
            return new Classification(DocumentType.SECURITY_DOC, 0.87);
        }
        if (contains(name, "pan", "passport", "id", "kyc", "aadhaar")) {
            return new Classification(DocumentType.KYC_ID, 0.86);
        }
        // Unrecognised — low confidence, must be reviewed.
        return new Classification(DocumentType.OTHER, 0.55);
    }

    /**
     * Keyword classification over the document's REAL extracted text. Because the text is the
     * actual content (not a filename guess) a strong phrase match earns high confidence. Ordered
     * most-specific-first; returns OTHER at low confidence when nothing recognisable is present.
     */
    private Classification classifyByText(String extractedText) {
        if (extractedText == null || extractedText.isBlank()) {
            return new Classification(DocumentType.OTHER, 0.0);
        }
        String t = extractedText.toLowerCase(Locale.ROOT);
        if (contains(t, "balance sheet", "profit and loss", "profit & loss", "statement of profit",
                "cash flow statement", "ebitda", "auditor's report", "audited financial",
                "total revenue", "revenue from operations")) {
            return new Classification(DocumentType.FINANCIAL_STATEMENT, 0.95);
        }
        if (contains(t, "goods and services tax", "gstin", "gst return", "gstr-", "input tax credit")) {
            return new Classification(DocumentType.TAX_GST, 0.94);
        }
        if (contains(t, "facility agreement", "sanction letter", "loan agreement", "letter of sanction",
                "facility letter", "terms and conditions of the facility")) {
            return new Classification(DocumentType.FACILITY_DOC, 0.92);
        }
        if (contains(t, "deed of hypothecation", "mortgage deed", "charge over", "pledge agreement",
                "security interest", "deed of guarantee")) {
            return new Classification(DocumentType.SECURITY_DOC, 0.9);
        }
        if (contains(t, "memorandum of association", "articles of association",
                "certificate of incorporation")) {
            return new Classification(DocumentType.MOA_AOA, 0.92);
        }
        if (contains(t, "credit information report", "cibil", "experian", "credit score",
                "bureau report")) {
            return new Classification(DocumentType.BUREAU_REPORT, 0.92);
        }
        if (contains(t, "account statement", "statement of account", "opening balance",
                "closing balance", "narration")) {
            return new Classification(DocumentType.BANK_STATEMENT, 0.88);
        }
        if (contains(t, "permanent account number", "passport no", "aadhaar", "know your customer",
                "identity proof")) {
            return new Classification(DocumentType.KYC_ID, 0.86);
        }
        return new Classification(DocumentType.OTHER, 0.0);
    }

    private boolean contains(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) {
                return true;
            }
        }
        return false;
    }
}
