package com.helix.counterparty.service;

import com.helix.common.audit.AuditService;
import com.helix.common.model.Enums.CddTier;
import com.helix.common.model.Enums.KycStatus;
import com.helix.common.notify.NotificationService;
import com.helix.common.web.ApiException;
import com.helix.counterparty.client.ConfigMasterClient;
import com.helix.counterparty.dto.Dtos.CreateCounterpartyRequest;
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

@Service
public class CounterpartyService {

    private static final Logger log = LoggerFactory.getLogger(CounterpartyService.class);

    private final CounterpartyRepository repository;
    private final ScreeningHitRepository hits;
    private final AuditService audit;
    private final ConfigMasterClient config;
    private final NotificationService notifications;

    public CounterpartyService(CounterpartyRepository repository, ScreeningHitRepository hits, AuditService audit,
                               ConfigMasterClient config, NotificationService notifications) {
        this.repository = repository;
        this.hits = hits;
        this.audit = audit;
        this.config = config;
        this.notifications = notifications;
    }

    @Transactional
    public Counterparty create(CreateCounterpartyRequest req, String actor) {
        Counterparty cp = new Counterparty();
        cp.setReference(References.forCounterparty());
        cp.setLegalName(req.legalName());
        cp.setLegalForm(req.legalForm());
        cp.setRegistrationNo(req.registrationNo());
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
     * Final CDD risk-tier sign-off (PRD §1 HITL gate). KYC cannot be verified while
     * any screening hit above MEDIUM severity remains open or escalated.
     */
    @Transactional
    public Counterparty verifyKyc(Long id, String actor) {
        Counterparty cp = get(id);
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
