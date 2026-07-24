package com.helix.counterparty.service;

import com.helix.common.audit.AuditService;
import com.helix.common.model.Enums.CddTier;
import com.helix.common.model.Enums.KycStatus;
import com.helix.common.notify.NotificationService;
import com.helix.common.validate.ConfigValidator;
import com.helix.common.web.ApiException;
import com.helix.counterparty.client.ConfigMasterClient;
import com.helix.counterparty.dto.Dtos.CreateCounterpartyRequest;
import com.helix.counterparty.dto.Dtos.HygieneCheck;
import com.helix.counterparty.dto.Dtos.HygieneResult;
import com.helix.counterparty.entity.Counterparty;
import com.helix.counterparty.entity.ScreeningHit;
import com.helix.counterparty.repo.CounterpartyRepository;
import com.helix.counterparty.repo.ScreeningHitRepository;
import com.helix.common.util.References;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class CounterpartyService {

    private static final Logger log = LoggerFactory.getLogger(CounterpartyService.class);

    /** VALIDATION_PARAMETER domain for statutory identifier formats (PAN/GSTIN/LEI/CIN). */
    static final String IDENTIFIER_VALIDATION_DOMAIN = "COUNTERPARTY_IDENTIFIERS";

    private final CounterpartyRepository repository;
    private final ScreeningHitRepository hits;
    private final AuditService audit;
    private final ConfigMasterClient config;
    private final NotificationService notifications;
    private final ConfigValidator validator;

    public CounterpartyService(CounterpartyRepository repository, ScreeningHitRepository hits, AuditService audit,
                               ConfigMasterClient config, NotificationService notifications,
                               ConfigValidator validator) {
        this.repository = repository;
        this.hits = hits;
        this.audit = audit;
        this.config = config;
        this.notifications = notifications;
        this.validator = validator;
    }

    @Transactional
    public Counterparty create(CreateCounterpartyRequest req, String actor) {
        Counterparty cp = new Counterparty();
        cp.setReference(References.forCounterparty());
        cp.setLegalName(req.legalName());
        cp.setLegalForm(req.legalForm());
        cp.setRegistrationNo(req.registrationNo());
        cp.setPan(normalizeIdentifier(req.pan()));
        cp.setGstin(normalizeIdentifier(req.gstin()));
        cp.setLei(normalizeIdentifier(req.lei()));
        cp.setCin(normalizeIdentifier(req.cin()));
        validateIdentifiers(cp, validator);
        cp.setJurisdiction(req.jurisdiction());
        cp.setSegment(req.segment());
        cp.setSector(req.sector());
        cp.setCountry(req.country());
        // Presentation (analysis) currency: explicit, else inferred from the
        // jurisdiction/country, else left null (spreading then defaults to the
        // latest period's native currency).
        cp.setPresentationCurrency(
                req.presentationCurrency() != null && !req.presentationCurrency().isBlank()
                        ? req.presentationCurrency().toUpperCase()
                        : defaultCurrencyFor(req.jurisdiction(), req.country()));
        cp.setListedEntity(req.listedEntity());
        cp.setRegulatedFi(req.regulatedFi());
        cp.setPep(req.pep());
        cp.setAdverseMedia(req.adverseMedia());
        cp.setHighRiskJurisdiction(req.highRiskJurisdiction());
        cp.setComplexOwnership(req.complexOwnership());
        // Config-defined flags beyond the six typed booleans (RISK_FLAG master keys).
        if (req.extraRiskFlags() != null && !req.extraRiskFlags().isEmpty()) {
            cp.getExtraRiskFlags().putAll(req.extraRiskFlags());
        }
        cp.setCreatedBy(actor);   // maker — anchors the maker≠checker KYC sign-off gate

        CddTier tier = deriveCddTier(cp);
        cp.setCddTier(tier.name());
        cp.setKycStatus(KycStatus.IN_PROGRESS.name());
        cp.setReKycDueDate(LocalDate.now().plusMonths(config.reKycMonths(cp.getJurisdiction(), tier.name())));

        Counterparty saved = repository.save(cp);
        audit.human(actor, "COUNTERPARTY_CREATED", "Counterparty", saved.getReference(),
                "Onboarded %s with CDD tier %s".formatted(saved.getLegalName(), tier),
                Map.of("jurisdiction", saved.getJurisdiction(), "cddTier", tier.name()));
        return saved;
    }

    /**
     * CDD intensity tiering — now DRIVEN BY the active jurisdiction's CDD_TIERS rule pack (E3):
     * any of the pack's {@code enhanced_triggers} present on the counterparty forces ENHANCED;
     * else any {@code simplified_eligible} flag yields SIMPLIFIED; else the pack's
     * {@code default_tier}. The seeded pack's trigger/eligible lists match the historical
     * hardcoded logic, so this is behaviour-preserving — but the tiering now moves when the pack
     * is re-authored (maker-checker), instead of being a code branch.
     */
    CddTier deriveCddTier(Counterparty cp) {
        Map<String, Object> tiers = config.cddTiers(cp.getJurisdiction());
        Set<String> flags = activeCddFlags(cp);
        if (asStrings(tiers.get("enhanced_triggers")).stream()
                .anyMatch(t -> flags.contains(t.toUpperCase()))) {
            return CddTier.ENHANCED;
        }
        if (asStrings(tiers.get("simplified_eligible")).stream()
                .anyMatch(t -> flags.contains(t.toUpperCase()))) {
            return CddTier.SIMPLIFIED;
        }
        String def = String.valueOf(tiers.getOrDefault("default_tier", "STANDARD"));
        try {
            return CddTier.valueOf(def.toUpperCase());
        } catch (IllegalArgumentException e) {
            return CddTier.STANDARD;
        }
    }

    /** The CDD_TIERS trigger keys that are TRUE for this counterparty. */
    private static Set<String> activeCddFlags(Counterparty cp) {
        Set<String> f = new java.util.HashSet<>();
        if (cp.isPep()) f.add("PEP");
        if (cp.isHighRiskJurisdiction()) f.add("HIGH_RISK_JURISDICTION");
        if (cp.isAdverseMedia()) f.add("ADVERSE_MEDIA");
        if (cp.isComplexOwnership()) f.add("COMPLEX_OWNERSHIP");
        if (cp.isListedEntity()) f.add("LISTED_ENTITY");
        if (cp.isRegulatedFi()) f.add("REGULATED_FI");
        return f;
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStrings(Object o) {
        if (o instanceof List<?> l) {
            return l.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    // ---- lifecycle: close (makes the declared-but-unreachable CLOSED terminal reachable, D9) ----

    /**
     * Closes (exits) an ACTIVE counterparty relationship — a governed, audited transition to the
     * terminal CLOSED lifecycle state that nothing previously set. Idempotency: a re-close conflicts.
     */
    @Transactional
    public Counterparty close(Long id, String reason, String actor) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("A close reason is required");
        }
        Counterparty cp = get(id);
        if ("CLOSED".equals(cp.getLifecycleStatus())) {
            throw ApiException.conflict("Counterparty " + cp.getReference() + " is already CLOSED");
        }
        if (!"ACTIVE".equals(cp.getLifecycleStatus())) {
            throw ApiException.conflict("Only an ACTIVE counterparty can be closed (is "
                    + cp.getLifecycleStatus() + ")");
        }
        cp.setLifecycleStatus("CLOSED");
        Counterparty saved = repository.save(cp);
        audit.human(actor, "COUNTERPARTY_CLOSED", "Counterparty", cp.getReference(),
                "Relationship closed: " + reason, Map.of("reason", reason));
        return saved;
    }

    // ---- re-KYC sweep (makes the declared-but-unreachable RE_KYC_DUE state reachable, D9) ----

    /**
     * Deterministic re-KYC sweep: flags every VERIFIED counterparty whose KYC is older than its
     * CDD tier's re-KYC interval (from the CDD_TIERS pack) as {@code RE_KYC_DUE}. The {@code asOf}
     * date lets an operator (or an e2e) evaluate due-ness at a point in time without waiting real
     * months; defaults to today. Idempotent (already-due rows are skipped), SYSTEM-audited, and
     * emits an advisory REKYC_DUE notification per flagged obligor.
     */
    @Transactional
    public Map<String, Object> reKycSweep(String asOfStr, String actor) {
        LocalDate asOf = asOfStr == null || asOfStr.isBlank() ? LocalDate.now() : LocalDate.parse(asOfStr);
        int scanned = 0, flagged = 0;
        List<String> flaggedRefs = new ArrayList<>();
        for (Counterparty cp : repository.findByKycStatus(KycStatus.VERIFIED.name())) {
            scanned++;
            if (cp.getVerifiedAt() == null) continue;
            int interval = config.reKycMonths(cp.getJurisdiction(), cp.getCddTier());
            LocalDate dueOn = LocalDate.ofInstant(cp.getVerifiedAt(), ZoneOffset.UTC).plusMonths(interval);
            if (asOf.isBefore(dueOn)) continue;   // not yet due at the evaluation date
            cp.setKycStatus(KycStatus.RE_KYC_DUE.name());
            cp.setReKycDueDate(dueOn);
            repository.save(cp);
            flagged++;
            flaggedRefs.add(cp.getReference());
            audit.engine("REKYC_DUE", "Counterparty", cp.getReference(),
                    "Re-KYC due (tier %s, verified %s, interval %dmo, due %s)".formatted(
                            cp.getCddTier(), cp.getVerifiedAt(), interval, dueOn),
                    Map.of("cddTier", nz(cp.getCddTier()), "intervalMonths", interval, "dueOn", dueOn.toString()));
            safeNotify(new NotificationService.Enqueue("REKYC_DUE", "REKYC_DUE", "Counterparty",
                    cp.getReference(), "rekyc:" + cp.getReference() + ":" + dueOn, cp.getJurisdiction(),
                    Map.of("borrower", nz(cp.getLegalName()), "reference", cp.getReference(),
                            "cddTier", nz(cp.getCddTier()), "dueDate", dueOn.toString(),
                            "rm", nz(cp.getRmId())), null), actor);
        }
        audit.engine("REKYC_SWEEP", "Counterparty", "batch",
                "Re-KYC sweep as of %s — flagged %d of %d verified".formatted(asOf, flagged, scanned),
                Map.of("asOf", asOf.toString(), "flagged", flagged, "scanned", scanned));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("asOf", asOf.toString());
        out.put("scanned", scanned);
        out.put("flagged", flagged);
        out.put("flaggedRefs", flaggedRefs);
        return out;
    }

    private void safeNotify(NotificationService.Enqueue cmd, String actor) {
        try {
            notifications.enqueue(cmd, actor);
        } catch (Exception e) {
            log.warn("notification enqueue failed for {} ({})", cmd.eventType(), e.getMessage());
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** Best-effort default analysis currency from the jurisdiction, then the country ISO. */
    private String defaultCurrencyFor(String jurisdiction, String country) {
        String j = jurisdiction == null ? "" : jurisdiction.toUpperCase();
        if (j.startsWith("IN")) return "INR";
        if (j.startsWith("AE")) return "AED";
        String c = country == null ? "" : country.toUpperCase();
        return switch (c) {
            case "IN" -> "INR";
            case "AE" -> "AED";
            case "US" -> "USD";
            case "GB", "UK" -> "GBP";
            case "SG" -> "SGD";
            default -> null;
        };
    }

    @Transactional(readOnly = true)
    public List<Counterparty> list() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public Counterparty get(Long id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("No counterparty: " + id));
    }

    @Transactional(readOnly = true)
    public Counterparty getByReference(String reference) {
        return repository.findByReference(reference)
                .orElseThrow(() -> ApiException.notFound("No counterparty: " + reference));
    }

    /**
     * Final CDD risk-tier sign-off (PRD §1 HITL gate). Two conditions, both enforced here:
     * (1) maker≠checker — the named human who signs off must differ from the counterparty's
     *     creator (SoD; skipped only for legacy rows with no recorded creator);
     * (2) no open hits ≥ MEDIUM — KYC cannot be verified while any screening hit at/above
     *     MEDIUM severity remains OPEN or ESCALATED.
     */
    @Transactional
    public Counterparty verifyKyc(Long id, String actor) {
        Counterparty cp = get(id);
        // Authorization (maker≠checker) is checked BEFORE the business-rule block below: an
        // actor who may not sign off learns nothing about the screening state. Fail-CLOSED on an
        // unknown creator — a record with no recorded creator (should not occur after the
        // audit-trail backfill) is refused rather than silently bypassing SoD.
        String creator = cp.getCreatedBy();
        if (creator == null || creator.isBlank()) {
            throw ApiException.forbiddenAutonomy(
                    "Segregation of duties cannot be verified: this counterparty has no recorded creator; "
                            + "a named creator must be established before CDD sign-off");
        }
        if (creator.equals(actor)) {
            throw ApiException.forbiddenAutonomy(
                    "Segregation of duties: the CDD risk-tier sign-off must be a different named human "
                            + "than the counterparty's creator (" + creator + ")");
        }
        List<ScreeningHit> open = hits.findByCounterpartyIdOrderBySeverityDesc(id).stream()
                .filter(h -> isBlockingDisposition(h.getDisposition()))
                .filter(h -> severityRank(h.getSeverity()) >= severityRank("MEDIUM"))
                .toList();
        if (!open.isEmpty()) {
            throw ApiException.conflict(
                    "Cannot verify KYC: %d unresolved screening hit(s) at/above MEDIUM severity".formatted(open.size()));
        }
        cp.setKycStatus(KycStatus.VERIFIED.name());
        cp.setVerifiedBy(actor);
        Instant verifiedAt = Instant.now();
        cp.setVerifiedAt(verifiedAt);
        // Anchor the next re-KYC due date to verification (was anchored to creation), per the
        // CDD tier's interval — so the sweep and the displayed due date agree.
        cp.setReKycDueDate(LocalDate.ofInstant(verifiedAt, ZoneOffset.UTC)
                .plusMonths(config.reKycMonths(cp.getJurisdiction(), cp.getCddTier())));
        audit.human(actor, "KYC_VERIFIED", "Counterparty", cp.getReference(),
                "CDD risk tier %s signed off; KYC verified".formatted(cp.getCddTier()),
                Map.of("cddTier", cp.getCddTier()));
        return repository.save(cp);
    }

    // ---- statutory identifiers + hygiene RAG ----------------------------------------------------

    /** Trim + uppercase an identifier; blank collapses to null (identifiers are optional). */
    static String normalizeIdentifier(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase();
    }

    /** The identifier fields the COUNTERPARTY_IDENTIFIERS validation domain covers, keyed by rule field name. */
    static Map<String, Object> identifierValues(Counterparty cp) {
        Map<String, Object> ids = new LinkedHashMap<>();
        ids.put("pan", cp.getPan());
        ids.put("gstin", cp.getGstin());
        ids.put("lei", cp.getLei());
        ids.put("cin", cp.getCin());
        return ids;
    }

    /**
     * Format-validate the supplied identifiers against the VALIDATION_PARAMETER master
     * (400 aggregating every failure). Absent identifiers skip; WARN-severity findings
     * are logged, never blocking. Shared by counterparty create and prospect create.
     */
    static void validateIdentifiers(Counterparty cp, ConfigValidator validator) {
        List<String> warnings = validator.validate(IDENTIFIER_VALIDATION_DOMAIN, identifierValues(cp));
        if (!warnings.isEmpty()) {
            log.warn("Identifier validation warnings for {}: {}", cp.getLegalName(), warnings);
        }
    }

    /**
     * Deterministic data-hygiene RAG — a read-only aggregation of identifier formats,
     * screening state, and KYC state. RED: any malformed identifier or an unresolved
     * screening hit at/above HIGH severity. AMBER: missing identifiers, lower-severity
     * unresolved hits, or KYC not verified. GREEN: identifiers present + valid AND
     * screening clear AND KYC verified. No authoritative figure is touched.
     */
    @Transactional
    public HygieneResult hygiene(Long id) {
        Counterparty cp = get(id);
        List<HygieneCheck> checkList = new ArrayList<>();
        boolean red = false, amber = false;

        // 1) statutory identifiers — format/checksum per the VALIDATION_PARAMETER master.
        Map<String, Object> ids = identifierValues(cp);
        Set<String> malformed = validator.evaluate(IDENTIFIER_VALIDATION_DOMAIN, ids).stream()
                .map(ConfigValidator.RuleFailure::field)
                .collect(Collectors.toSet());
        for (Map.Entry<String, Object> e : ids.entrySet()) {
            String value = e.getValue() == null ? "" : String.valueOf(e.getValue());
            if (value.isBlank()) {
                checkList.add(new HygieneCheck("identifier." + e.getKey(), "MISSING", "not captured"));
                amber = true;
            } else if (malformed.contains(e.getKey())) {
                checkList.add(new HygieneCheck("identifier." + e.getKey(), "MALFORMED",
                        value + " fails format/checksum validation"));
                red = true;
            } else {
                checkList.add(new HygieneCheck("identifier." + e.getKey(), "VALID", value));
            }
        }

        // 2) screening — unresolved severe hits drive RED; any other unresolved hit AMBER.
        List<ScreeningHit> unresolved = hits.findByCounterpartyIdOrderBySeverityDesc(id).stream()
                .filter(h -> isBlockingDisposition(h.getDisposition()))
                .toList();
        long severe = unresolved.stream()
                .filter(h -> severityRank(h.getSeverity()) >= severityRank("HIGH"))
                .count();
        if (severe > 0) {
            checkList.add(new HygieneCheck("screening", "SEVERE_OPEN",
                    severe + " unresolved screening hit(s) at/above HIGH severity"));
            red = true;
        } else if (!unresolved.isEmpty()) {
            checkList.add(new HygieneCheck("screening", "OPEN",
                    unresolved.size() + " unresolved lower-severity screening hit(s)"));
            amber = true;
        } else {
            checkList.add(new HygieneCheck("screening", "CLEAR", "no unresolved screening hits"));
        }

        // 3) KYC — verified or not.
        if (KycStatus.VERIFIED.name().equals(cp.getKycStatus())) {
            checkList.add(new HygieneCheck("kyc", "VERIFIED", "verified by " + cp.getVerifiedBy()));
        } else {
            checkList.add(new HygieneCheck("kyc", "NOT_VERIFIED", "KYC status " + cp.getKycStatus()));
            amber = true;
        }

        String status = red ? "RED" : amber ? "AMBER" : "GREEN";
        audit.engine("HYGIENE_ASSESSED", "Counterparty", cp.getReference(),
                "Data-hygiene RAG %s across %d check(s)".formatted(status, checkList.size()),
                Map.of("status", status, "checks", checkList.size()));
        return new HygieneResult(cp.getId(), cp.getReference(), status, checkList);
    }

    private boolean isBlockingDisposition(String disposition) {
        return "OPEN".equals(disposition) || "ESCALATED".equals(disposition);
    }

    static int severityRank(String severity) {
        return switch (severity) {
            case "SEVERE" -> 4;
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            default -> 1;
        };
    }
}
