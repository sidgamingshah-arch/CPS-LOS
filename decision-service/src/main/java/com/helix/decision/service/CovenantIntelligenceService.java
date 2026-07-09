package com.helix.decision.service;

import com.helix.common.audit.AuditService;
import com.helix.common.web.ApiException;
import com.helix.decision.client.UpstreamClient;
import com.helix.decision.client.UpstreamClient.CreditInputsDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.helix.decision.dto.CovenantIntelDtos.ConfirmExtractionRequest;
import com.helix.decision.dto.Dtos.AddCovenantRequest;
import com.helix.decision.entity.CertificateAssessment;
import com.helix.decision.entity.Covenant;
import com.helix.decision.entity.CovenantExtraction;
import com.helix.decision.repo.CertificateAssessmentRepository;
import com.helix.decision.repo.CovenantExtractionRepository;
import com.helix.decision.repo.CovenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI-assisted covenant intelligence at the boundary (PRD §7 / §11), all advisory
 * and human-gated:
 *
 * <ol>
 *   <li><b>Extraction from CP free text</b> — parse covenant clauses into structured
 *       candidates ({@link CovenantExtraction}); a human confirms each, which
 *       materialises a real {@link Covenant}.</li>
 *   <li><b>Compliance-certificate assessment</b> — parse a borrower's compliance
 *       certificate into per-line {@link CertificateAssessment}s, map each to a
 *       system covenant (flagging taxonomy mismatches), and recompute the value
 *       from the spreading module so a disagreement with the borrower's claim is
 *       surfaced.</li>
 * </ol>
 *
 * No authoritative figure is mutated — the recomputation reads the deterministic
 * spread ratios; the extractor only proposes. AI outputs are stamped
 * {@code audit.ai(...)}; the confirm gates stamp {@code audit.human(...)}.
 */
@Service
public class CovenantIntelligenceService {

    private static final Logger log = LoggerFactory.getLogger(CovenantIntelligenceService.class);

    // ---- metric taxonomy: canonical token + the borrower phrases that map to it ----
    private record MetricDef(String metric, String covenantType, String defaultOperator, List<String> synonyms) {
    }

    private static final List<MetricDef> METRICS = List.of(
            new MetricDef("DSCR", "FINANCIAL_MAINTENANCE", ">=",
                    List.of("dscr", "debt service coverage", "debt-service coverage")),
            new MetricDef("NET_LEVERAGE", "FINANCIAL_MAINTENANCE", "<=",
                    List.of("net leverage", "net debt to ebitda", "net debt/ebitda",
                            "debt to ebitda", "debt/ebitda", "leverage ratio", "leverage")),
            new MetricDef("INTEREST_COVERAGE", "FINANCIAL_MAINTENANCE", ">=",
                    List.of("interest coverage", "interest cover", "icr")),
            new MetricDef("CURRENT_RATIO", "FINANCIAL_MAINTENANCE", ">=",
                    List.of("current ratio")),
            new MetricDef("GEARING", "FINANCIAL_MAINTENANCE", "<=",
                    List.of("gearing", "debt to equity", "debt-to-equity", "debt/equity")),
            new MetricDef("EBITDA_MARGIN", "FINANCIAL_MAINTENANCE", ">=",
                    List.of("ebitda margin")),
            new MetricDef("NET_WORTH", "FINANCIAL_MAINTENANCE", ">=",
                    List.of("tangible net worth", "net worth", "tnw")));

    /** Flattened (synonym → def), longest synonym first so "net leverage" wins over "leverage". */
    private record Syn(String text, MetricDef def) {
    }

    private static final List<Syn> SYNONYMS = METRICS.stream()
            .flatMap(d -> d.synonyms().stream().map(s -> new Syn(s, d)))
            .sorted((a, b) -> Integer.compare(b.text().length(), a.text().length()))
            .toList();

    private static final Pattern NUMBER = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(%|percent|per cent|x|times)?",
            Pattern.CASE_INSENSITIVE);

    private final CovenantExtractionRepository extractions;
    private final CertificateAssessmentRepository assessments;
    private final CovenantRepository covenants;
    private final CovenantService covenantService;
    private final UpstreamClient upstream;
    private final AuditService audit;
    private final com.helix.common.governance.AiGovernanceClient governance;

    public CovenantIntelligenceService(CovenantExtractionRepository extractions,
                                       CertificateAssessmentRepository assessments,
                                       CovenantRepository covenants, CovenantService covenantService,
                                       UpstreamClient upstream, AuditService audit,
                                       com.helix.common.governance.AiGovernanceClient governance) {
        this.extractions = extractions;
        this.assessments = assessments;
        this.covenants = covenants;
        this.covenantService = covenantService;
        this.upstream = upstream;
        this.audit = audit;
        this.governance = governance;
    }

    /** Both extract paths share the same gate — resolve once. */
    private void enforceGate(String reference) {
        UpstreamClient.DealEnvelopeDto env = upstream.envelopeOrNull(reference);
        governance.enforce(com.helix.common.governance.AiCapability.COVENANT_INTEL,
                env == null ? null : env.jurisdiction());
    }

    // ====================================================== 1. extraction from CP free text

    @Transactional
    public List<CovenantExtraction> extract(String reference, String text, String actor) {
        enforceGate(reference);
        List<CovenantExtraction> out = new ArrayList<>();
        for (String chunk : splitClauses(text)) {
            CovenantExtraction e = parseClause(reference, chunk);
            if (e != null) {
                out.add(extractions.save(e));
            }
        }
        audit.ai("covenant-extraction", "COVENANT_EXTRACTED", "Application", reference,
                "Extracted %d covenant candidate(s) from CP free text".formatted(out.size()),
                Map.of("candidates", out.size(), "advisory", true));
        return out;
    }

    private CovenantExtraction parseClause(String reference, String chunk) {
        String lower = chunk.toLowerCase();
        Syn matched = SYNONYMS.stream().filter(s -> lower.contains(s.text())).findFirst().orElse(null);
        if (matched == null) {
            return null;   // no covenant metric in this clause
        }
        int metricIdx = lower.indexOf(matched.text());
        MetricDef def = matched.def();

        List<String> signals = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        signals.add("matched metric '" + matched.text() + "' → " + def.metric());

        // operator
        boolean operatorExplicit = true;
        String operator = detectOperator(lower);
        if (operator == null) {
            operator = def.defaultOperator();
            operatorExplicit = false;
            gaps.add("operator not stated — inferred '" + operator + "' from metric convention");
        } else {
            signals.add("operator phrase → '" + operator + "'");
        }

        // threshold: number closest to the metric mention
        Double threshold = nearestNumber(chunk, metricIdx);
        if (threshold == null) {
            gaps.add("no numeric threshold found");
        } else {
            signals.add("threshold " + threshold);
        }

        // test frequency
        String freq = detectFrequency(lower);
        if (freq == null) {
            freq = "QUARTERLY";
            gaps.add("test frequency not stated — defaulted QUARTERLY");
        } else {
            signals.add("frequency → " + freq);
        }

        double confidence = 0.4
                + (operatorExplicit ? 0.3 : 0.1)
                + (threshold != null ? 0.3 : 0.0);

        CovenantExtraction e = new CovenantExtraction();
        e.setApplicationReference(reference);
        e.setSourceText(chunk.trim());
        e.setCovenantType(def.covenantType());
        e.setMetric(def.metric());
        e.setReportedLabel(matched.text());
        e.setOperator(operator);
        e.setThreshold(threshold);
        e.setTestFrequency(freq);
        e.setBreachSeverity("INFORMATION".equals(def.covenantType()) ? "MINOR" : "MAJOR");
        e.setConfidence(Math.round(confidence * 100.0) / 100.0);
        e.setSignals(signals);
        e.setGaps(gaps);
        e.setExtractedBy("ai:covenant-extraction");
        return e;
    }

    @Transactional(readOnly = true)
    public List<CovenantExtraction> listExtractions(String reference) {
        return extractions.findByApplicationReferenceOrderByIdDesc(reference);
    }

    /** Human gate: materialise a real covenant from the candidate (with optional edits). */
    @Transactional
    public Covenant confirmExtraction(Long id, ConfirmExtractionRequest edits, String actor) {
        CovenantExtraction e = extractions.findById(id)
                .orElseThrow(() -> ApiException.notFound("No extraction: " + id));
        if (!"DRAFT".equals(e.getStatus())) {
            throw ApiException.conflict("Extraction already " + e.getStatus());
        }
        String covenantType = pick(edits == null ? null : edits.covenantType(), e.getCovenantType());
        String metric = pick(edits == null ? null : edits.metric(), e.getMetric());
        String operator = pick(edits == null ? null : edits.operator(), e.getOperator());
        Double threshold = edits != null && edits.threshold() != null ? edits.threshold() : e.getThreshold();
        String freq = canonicalFrequency(pick(edits == null ? null : edits.testFrequency(), e.getTestFrequency()));
        String severity = pick(edits == null ? null : edits.breachSeverity(), e.getBreachSeverity());

        if (threshold == null) {
            throw ApiException.badRequest("Cannot confirm: no threshold — supply one in the confirm request");
        }

        AddCovenantRequest req = new AddCovenantRequest(covenantType, metric, operator, threshold,
                freq, "credit_proposal_clause", 30, severity,
                List.of("notify_RM", "raise_EWS"));
        Covenant created = covenantService.add(e.getApplicationReference(), req, actor);

        e.setStatus("CONFIRMED");
        e.setLinkedCovenantId(created.getId());
        e.setReviewedBy(actor);
        e.setReviewedAt(Instant.now());
        e.setReviewNote(edits == null ? null : edits.note());
        extractions.save(e);
        audit.human(actor, "COVENANT_EXTRACTION_CONFIRMED", "Application", e.getApplicationReference(),
                "Confirmed extracted %s %s %.2f -> covenant #%d".formatted(metric, operator, threshold, created.getId()),
                Map.of("extractionId", id, "covenantId", created.getId()));
        return created;
    }

    @Transactional
    public CovenantExtraction rejectExtraction(Long id, String note, String actor) {
        CovenantExtraction e = extractions.findById(id)
                .orElseThrow(() -> ApiException.notFound("No extraction: " + id));
        if (!"DRAFT".equals(e.getStatus())) {
            throw ApiException.conflict("Extraction already " + e.getStatus());
        }
        e.setStatus("REJECTED");
        e.setReviewedBy(actor);
        e.setReviewedAt(Instant.now());
        e.setReviewNote(note);
        CovenantExtraction saved = extractions.save(e);
        audit.human(actor, "COVENANT_EXTRACTION_REJECTED", "Application", e.getApplicationReference(),
                "Rejected covenant candidate #" + id, Map.of("extractionId", id));
        return saved;
    }

    // ====================================================== 2. compliance-certificate assessment

    @Transactional
    public List<CertificateAssessment> assessCertificate(String reference, String text, String actor) {
        enforceGate(reference);
        // The deterministic spread ratios used to recompute each covenant. We distinguish
        // three states so consumers (API + audit) can tell them apart:
        //   - UPSTREAM_OK + non-empty ratios  → recompute ran
        //   - UPSTREAM_OK + empty ratios      → spread not confirmed yet (no fault)
        //   - UPSTREAM_DOWN                   → origination unreachable; recompute skipped
        Map<String, Double> ratios;
        boolean upstreamOk;
        try {
            CreditInputsDto inputs = upstream.creditInputs(reference);
            ratios = inputs.ratios() == null ? Map.of() : inputs.ratios();
            upstreamOk = true;
        } catch (Exception ex) {
            log.warn("origination-service credit-inputs unavailable for {} ({}); assessment will skip the deterministic recompute",
                    reference, ex.getMessage());
            ratios = Map.of();
            upstreamOk = false;
        }
        boolean recomputeAvailable = upstreamOk && !ratios.isEmpty();
        List<Covenant> active = covenants.findByApplicationReference(reference).stream()
                .filter(Covenant::isActive).toList();

        List<CertificateAssessment> out = new ArrayList<>();
        int disagreements = 0;
        for (String line : text.split("\\R")) {
            if (line.isBlank()) continue;
            CertificateAssessment a = parseCertificateLine(reference, line, active, ratios);
            if (a == null) continue;
            a.setRecomputeAvailable(recomputeAvailable);
            if (Boolean.FALSE.equals(a.getAgreement())) disagreements++;
            out.add(assessments.save(a));
        }
        String tail = upstreamOk
                ? (ratios.isEmpty() ? " (no ratios on file — spread not yet confirmed)" : "")
                : " (recompute skipped — origination unavailable)";
        audit.ai("covenant-certificate", "CERTIFICATE_ASSESSED", "Application", reference,
                "Assessed %d certificate line(s); %d disagree with recomputation%s".formatted(
                        out.size(), disagreements, tail),
                Map.of("lines", out.size(), "disagreements", disagreements,
                        "recomputeAvailable", recomputeAvailable,
                        "upstreamOk", upstreamOk, "advisory", true));
        return out;
    }

    /**
     * Metric synonyms that read as natural canonical names in industry usage even though
     * they are not the primary token — we don't want to flag these as "taxonomy mismatch"
     * because that floods the UI with false positives the analyst learns to ignore.
     */
    private static final Set<String> CANONICAL_SECONDARY_SYNONYMS = Set.of(
            "icr",                  // interest coverage
            "tnw", "net worth",     // tangible net worth
            "debt/equity",          // gearing
            "net debt to ebitda", "net debt/ebitda");  // net leverage

    /** Maximum source-line length the persisted column allows (matches @Column(length=…)). */
    private static final int SOURCE_LINE_MAX = 1000;

    private CertificateAssessment parseCertificateLine(String reference, String rawLine,
                                                       List<Covenant> active, Map<String, Double> ratios) {
        String line = rawLine.trim().replaceFirst("^[-*\\d.)\\s]+", "");
        String lower = line.toLowerCase();
        Syn matched = SYNONYMS.stream().filter(s -> lower.contains(s.text())).findFirst().orElse(null);
        if (matched == null) return null;
        MetricDef def = matched.def();
        int metricIdx = lower.indexOf(matched.text());

        CertificateAssessment a = new CertificateAssessment();
        a.setApplicationReference(reference);
        a.setSourceLine(line.trim());
        a.setReportedLabel(matched.text());
        a.setSystemMetric(def.metric());
        a.setReportedValue(nearestNumber(line, metricIdx));
        a.setReportedStatus(detectStatus(line));
        // Taxonomy mismatch only when the borrower's term is not the canonical primary
        // synonym AND not a widely-accepted secondary (ICR/TNW etc) — otherwise we'd flag
        // every legitimate industry-standard abbreviation as a mismatch.
        String primary = def.synonyms().get(0);
        boolean nonPrimary = !matched.text().equalsIgnoreCase(primary);
        boolean canonical = CANONICAL_SECONDARY_SYNONYMS.contains(matched.text().toLowerCase());
        a.setTaxonomyMismatch(nonPrimary && !canonical);
        a.setAssessedBy("ai:covenant-certificate");

        // Map to a system covenant on this metric and recompute deterministically. When the
        // analyst has tied multiple active covenants to the same metric (e.g. an information
        // covenant + a maintenance covenant on NET_LEVERAGE), prefer the most-recently-created
        // one and surface the ambiguity in the source line for a human to disambiguate.
        List<Covenant> matches = active.stream()
                .filter(c -> def.metric().equals(c.getMetric()))
                .sorted((x, y) -> Long.compare(y.getId(), x.getId()))
                .toList();
        Covenant cov = matches.isEmpty() ? null : matches.get(0);
        if (matches.size() > 1) {
            String suffix = " [ambiguous: " + matches.size() + " active covenants on "
                    + def.metric() + " — matched #" + cov.getId() + "]";
            // Truncate the line content (not the suffix) so the ambiguity marker is always
            // preserved and the row never overflows the @Column(length = SOURCE_LINE_MAX).
            String current = a.getSourceLine();
            int available = SOURCE_LINE_MAX - suffix.length();
            if (current.length() > available) {
                current = current.substring(0, Math.max(0, available - 1)) + "…";
            }
            a.setSourceLine(current + suffix);
        }
        if (cov != null) {
            a.setCovenantId(cov.getId());
            a.setOperator(cov.getOperator());
            a.setThreshold(cov.getThreshold());
            Double observed = ratios.get(def.metric());
            if (observed != null) {
                a.setRecomputedObserved(observed);
                boolean passed = satisfies(observed, cov.getOperator(), cov.getThreshold());
                a.setRecomputedPassed(passed);
                if (!"UNKNOWN".equals(a.getReportedStatus())) {
                    a.setAgreement(("COMPLIED".equals(a.getReportedStatus())) == passed);
                }
            }
        }
        return a;
    }

    @Transactional(readOnly = true)
    public List<CertificateAssessment> listAssessments(String reference) {
        return assessments.findByApplicationReferenceOrderByIdDesc(reference);
    }

    @Transactional
    public CertificateAssessment reviewAssessment(Long id, boolean confirm, String note, String actor) {
        CertificateAssessment a = assessments.findById(id)
                .orElseThrow(() -> ApiException.notFound("No assessment: " + id));
        if (!"DRAFT".equals(a.getStatus())) {
            throw ApiException.conflict("Assessment already " + a.getStatus());
        }
        a.setStatus(confirm ? "CONFIRMED" : "REJECTED");
        a.setReviewedBy(actor);
        a.setReviewedAt(Instant.now());
        a.setReviewNote(note);
        CertificateAssessment saved = assessments.save(a);
        audit.human(actor, confirm ? "CERTIFICATE_ASSESSMENT_CONFIRMED" : "CERTIFICATE_ASSESSMENT_REJECTED",
                "Application", a.getApplicationReference(),
                "%s certificate assessment #%d (%s)".formatted(confirm ? "Confirmed" : "Rejected", id, a.getSystemMetric()),
                Map.of("assessmentId", id, "metric", String.valueOf(a.getSystemMetric())));
        return saved;
    }

    // ====================================================== parsing helpers

    /** Split free text into candidate clauses on lines, semicolons and bullets. */
    private static List<String> splitClauses(String text) {
        List<String> chunks = new ArrayList<>();
        for (String part : text.split("\\R|;|•|(?<=\\.)\\s+(?=[A-Z])")) {
            String t = part.trim().replaceFirst("^[-*\\d.)\\s]+", "");
            if (t.length() >= 4) chunks.add(t);
        }
        return chunks;
    }

    private static String detectOperator(String lower) {
        if (containsAny(lower, "at least", "minimum", "min.", "not less than", "no less than",
                "greater than or equal", "at minimum", "floor of", "≥", ">=")) return ">=";
        if (containsAny(lower, "at most", "maximum", "max.", "not more than", "no more than",
                "not exceed", "shall not exceed", "not to exceed", "ceiling of", "cap of",
                "up to", "≤", "<=")) return "<=";
        if (containsAny(lower, "greater than", "more than", "above", "exceeds")) return ">";
        if (containsAny(lower, "less than", "below", "under")) return "<";
        return null;
    }

    /**
     * Frequency keywords matched on word boundaries — substring matches were flipping
     * "12-month projections" to MONTHLY even when the clause itself was annual / quarterly.
     */
    private static final Pattern FREQ_QUARTERLY = Pattern.compile("\\bquarterly\\b|\\bper quarter\\b|\\beach quarter\\b|\\bevery quarter\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FREQ_MONTHLY = Pattern.compile("\\bmonthly\\b|\\bper month\\b|\\beach month\\b|\\bevery month\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FREQ_SEMI_ANNUAL = Pattern.compile("\\bsemi[-\\s]?annual(?:ly)?\\b|\\bhalf[-\\s]?yearly?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FREQ_ANNUAL = Pattern.compile("\\bannual(?:ly)?\\b|\\byearly\\b|\\bper annum\\b|\\bp\\.a\\.", Pattern.CASE_INSENSITIVE);

    private static String detectFrequency(String text) {
        if (FREQ_QUARTERLY.matcher(text).find()) return "QUARTERLY";
        if (FREQ_MONTHLY.matcher(text).find()) return "MONTHLY";
        if (FREQ_SEMI_ANNUAL.matcher(text).find()) return "HALF_YEARLY";
        if (FREQ_ANNUAL.matcher(text).find()) return "ANNUAL";
        return null;
    }

    /**
     * Compliance keywords matched on word boundaries. The previous substring match flipped
     * lines like "Key metrics: DSCR 0.90x" to COMPLIED because "metrics" contains "met".
     */
    private static final Pattern NON_COMPLIED = Pattern.compile(
            "\\bnot complied\\b|\\bnon[-\\s]complied\\b|\\bnon[-\\s]compliant\\b|"
                    + "\\bbreach(?:ed)?\\b|\\bnot met\\b|\\bfailure\\b|\\bfailed\\b|\\bdefault(?:ed)?\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COMPLIED = Pattern.compile(
            "\\bcomplied\\b|\\bcompliant\\b|\\bin compliance\\b|\\bmet\\b|\\bpassed\\b|"
                    + "\\bsatisfied\\b|\\bwithin limit\\b|\\bwithin limits\\b",
            Pattern.CASE_INSENSITIVE);

    private static String detectStatus(String text) {
        // Negative first — "complied" is a sub-pattern of "not complied".
        if (NON_COMPLIED.matcher(text).find()) return "NOT_COMPLIED";
        if (COMPLIED.matcher(text).find()) return "COMPLIED";
        return "UNKNOWN";
    }

    /**
     * The number nearest to the metric mention. Constrained to a window around the anchor
     * (60 chars) so a day-count phrase elsewhere in the clause ("Quarterly reporting within
     * 30 days; DSCR shall be at least 1.40x") doesn't beat the actual threshold. Percent
     * suffixes are normalised to a fraction.
     */
    private static final int ANCHOR_WINDOW = 60;

    private static Double nearestNumber(String text, int anchorIdx) {
        Matcher m = NUMBER.matcher(text);
        Double best = null;
        int bestDist = Integer.MAX_VALUE;
        while (m.find()) {
            int dist = Math.abs(m.start() - anchorIdx);
            if (dist > ANCHOR_WINDOW) continue;
            double val = Double.parseDouble(m.group(1));
            String unit = m.group(2);
            if (unit != null && (unit.startsWith("%") || unit.toLowerCase().contains("percent")
                    || unit.toLowerCase().contains("per cent"))) {
                val = val / 100.0;
            }
            if (dist < bestDist) {
                bestDist = dist;
                best = val;
            }
        }
        return best;
    }

    private static boolean satisfies(double observed, String op, double threshold) {
        return switch (op) {
            case ">=" -> observed >= threshold;
            case ">" -> observed > threshold;
            case "<=" -> observed <= threshold;
            case "<" -> observed < threshold;
            case "==" -> Math.abs(observed - threshold) < 1e-9;
            default -> true;
        };
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }

    private static String pick(String override, String fallback) {
        return override == null || override.isBlank() ? fallback : override;
    }

    /**
     * Canonicalise a frequency token to the platform's single vocabulary
     * (MONTHLY | QUARTERLY | HALF_YEARLY | ANNUAL) so a confirmed covenant is never
     * mis-rolled by a schedule/MER switch that only knows HALF_YEARLY. SEMI_ANNUAL is
     * accepted as a synonym for HALF_YEARLY (belt-and-suspenders with the addPeriod switches).
     */
    private static String canonicalFrequency(String freq) {
        if (freq == null || freq.isBlank()) return freq;
        String u = freq.trim().toUpperCase();
        return "SEMI_ANNUAL".equals(u) ? "HALF_YEARLY" : u;
    }
}
