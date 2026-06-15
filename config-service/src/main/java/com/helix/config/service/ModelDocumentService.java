package com.helix.config.service;

import com.helix.common.audit.AuditService;
import com.helix.common.web.ApiException;
import com.helix.config.entity.MasterRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the qualitative scorecard from an uploaded credit-rating <b>model document</b>
 * (the bank's internal rating methodology). The extractor recognises the qualitative
 * parameter sections a rating model typically carries — management, industry, business
 * profile, financial flexibility, governance — and for each one DERIVES a scoring prompt
 * plus a weight (parsed from the document where stated, else an even split), then submits
 * them as {@code QUAL_SCORECARD} master records under the normal maker-checker flow.
 *
 * <p>The parsing here is a deterministic heuristic standing in for the LLM/doc-intel
 * service at the platform boundary (same pattern as {@code DocIntelligenceService}); the
 * generated prompts and weights are <b>proposals</b> a human reviews and approves before
 * they become the active scorecard. Nothing here touches the deterministic financial
 * scorecard — qualitative scoring is an advisory overlay.</p>
 */
@Service
public class ModelDocumentService {

    /** Theme detectors: keyword set → canonical parameter (key, displayName). */
    private record Theme(String key, String displayName, List<String> keywords) { }

    private static final List<Theme> THEMES = List.of(
            new Theme("management_quality", "Management Quality & Track Record",
                    List.of("management", "promoter", "leadership", "track record", "succession")),
            new Theme("industry_outlook", "Industry & Business Outlook",
                    List.of("industry", "sector", "market", "competitive", "demand", "cyclical")),
            new Theme("business_profile", "Business & Operating Profile",
                    List.of("business profile", "operating profile", "diversification", "scale", "operations")),
            new Theme("financial_flexibility", "Financial Flexibility & Liquidity",
                    List.of("financial flexibility", "liquidity", "funding access", "refinanc", "banking relationship")),
            new Theme("governance_esg", "Governance & ESG",
                    List.of("governance", "esg", "board", "disclosure", "audit", "sustainability")),
            new Theme("group_support", "Group & Parent Support",
                    List.of("parent support", "group support", "letter of comfort", "ssa", "shareholder support")));

    private static final Pattern WEIGHT = Pattern.compile("(\\d{1,3}(?:\\.\\d+)?)\\s*%");

    private final MasterDataService masters;
    private final AuditService audit;

    public ModelDocumentService(MasterDataService masters, AuditService audit) {
        this.masters = masters;
        this.audit = audit;
    }

    public record ExtractedParam(String key, String displayName, double weight, String prompt) { }

    public record ExtractionResult(int parametersFound, double totalWeight, List<String> warnings,
                                   List<MasterRecord> submitted) { }

    /**
     * Parses the model document and submits one PENDING {@code QUAL_SCORECARD} record per
     * detected qualitative parameter. The records await checker approval before they
     * become the active qualitative scorecard.
     */
    @Transactional
    public ExtractionResult extract(String modelDocText, String jurisdiction, boolean replaceExisting, String maker) {
        if (maker == null || maker.isBlank()) {
            throw ApiException.forbiddenAutonomy("A named maker is required to extract a scorecard");
        }
        if (modelDocText == null || modelDocText.isBlank()) {
            throw ApiException.badRequest("Model document text is required");
        }
        String lower = modelDocText.toLowerCase();
        List<ExtractedParam> found = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (Theme t : THEMES) {
            String hitLine = lineContaining(modelDocText, lower, t.keywords());
            if (hitLine == null) continue;
            Double parsedWeight = parseWeightNear(hitLine);
            found.add(new ExtractedParam(t.key(), t.displayName(),
                    parsedWeight == null ? -1.0 : parsedWeight / 100.0, generatePrompt(t, hitLine)));
        }
        if (found.isEmpty()) {
            throw ApiException.badRequest(
                    "No qualitative parameters recognised in the model document — expected sections like "
                    + "Management, Industry, Business Profile, Financial Flexibility, Governance");
        }

        // Normalise weights: if the doc didn't state them (or they don't sum to ~1), split evenly.
        boolean anyParsed = found.stream().anyMatch(p -> p.weight() > 0);
        double sumParsed = found.stream().filter(p -> p.weight() > 0).mapToDouble(ExtractedParam::weight).sum();
        if (!anyParsed || Math.abs(sumParsed - 1.0) > 0.02) {
            warnings.add(anyParsed
                    ? "Stated weights summed to %.2f (not 1.0) — applied an even split across %d parameters"
                            .formatted(sumParsed, found.size())
                    : "No weights stated in the document — applied an even split across %d parameters"
                            .formatted(found.size()));
            double even = round2(1.0 / found.size());
            List<ExtractedParam> renorm = new ArrayList<>();
            for (ExtractedParam p : found) renorm.add(new ExtractedParam(p.key(), p.displayName(), even, p.prompt()));
            found = renorm;
        }

        List<MasterRecord> submitted = new ArrayList<>();
        double total = 0;
        for (ExtractedParam p : found) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("displayName", p.displayName());
            payload.put("weight", p.weight());
            payload.put("prompt", p.prompt());
            payload.put("source", "MODEL_DOC");
            payload.put("anchors", List.of(
                    Map.of("band", "WEAK", "min", 0, "guidance",
                            "Material concerns on " + p.displayName().toLowerCase()),
                    Map.of("band", "ADEQUATE", "min", 45, "guidance", "In line with peers"),
                    Map.of("band", "STRONG", "min", 67, "guidance",
                            "Clear strength on " + p.displayName().toLowerCase())));
            submitted.add(masters.submit("QUAL_SCORECARD", p.key(), jurisdiction, payload, maker));
            total += p.weight();
        }
        audit.human(maker, "MODEL_DOC_EXTRACTED", "Master:QUAL_SCORECARD",
                jurisdiction == null ? "default" : jurisdiction,
                "Extracted %d qualitative parameter(s) + prompts from the model document (pending approval)"
                        .formatted(submitted.size()),
                Map.of("parameters", submitted.size(), "jurisdiction", jurisdiction == null ? "" : jurisdiction,
                        "totalWeight", round2(total)));
        return new ExtractionResult(submitted.size(), round2(total), warnings, submitted);
    }

    // --------------------------------------------------- helpers

    private String generatePrompt(Theme t, String hitLine) {
        return ("Assess %s for the borrower on a 0-100 scale, per the bank's credit-rating model. "
                + "Weigh the factors the model lists for this dimension%s. Ground every point in the "
                + "model document's guidance and the deal's data; cite what drove the score. Return a "
                + "score (0-100) and a 2-3 sentence rationale. This is an advisory recommendation — the "
                + "authoritative grade is unchanged until a named human confirms.")
                .formatted(t.displayName(),
                        hitLine == null || hitLine.isBlank() ? "" : " (model reference: \"" + trim(hitLine, 120) + "\")");
    }

    private static String lineContaining(String text, String lower, List<String> keywords) {
        for (String kw : keywords) {
            int idx = lower.indexOf(kw);
            if (idx < 0) continue;
            int start = text.lastIndexOf('\n', idx) + 1;
            int end = text.indexOf('\n', idx);
            if (end < 0) end = text.length();
            return text.substring(start, end).trim();
        }
        return null;
    }

    private static Double parseWeightNear(String line) {
        if (line == null) return null;
        Matcher m = WEIGHT.matcher(line);
        if (m.find()) {
            try {
                double w = Double.parseDouble(m.group(1));
                if (w > 0 && w <= 100) return w;
            } catch (NumberFormatException ignored) { }
        }
        return null;
    }

    private static String trim(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
