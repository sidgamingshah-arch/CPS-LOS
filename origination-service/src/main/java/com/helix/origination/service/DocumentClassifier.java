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

    private boolean contains(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) {
                return true;
            }
        }
        return false;
    }
}
