package com.helix.counterparty.service;

import com.helix.common.audit.AuditService;
import com.helix.common.model.Enums.CddTier;
import com.helix.common.model.Enums.KycStatus;
import com.helix.common.web.ApiException;
import com.helix.counterparty.dto.Dtos.CreateCounterpartyRequest;
import com.helix.counterparty.entity.Counterparty;
import com.helix.counterparty.entity.ScreeningHit;
import com.helix.counterparty.repo.CounterpartyRepository;
import com.helix.counterparty.repo.ScreeningHitRepository;
import com.helix.common.util.References;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Service
public class CounterpartyService {

    private final CounterpartyRepository repository;
    private final ScreeningHitRepository hits;
    private final AuditService audit;

    public CounterpartyService(CounterpartyRepository repository, ScreeningHitRepository hits, AuditService audit) {
        this.repository = repository;
        this.hits = hits;
        this.audit = audit;
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
        cp.setListedEntity(req.listedEntity());
        cp.setRegulatedFi(req.regulatedFi());
        cp.setPep(req.pep());
        cp.setAdverseMedia(req.adverseMedia());
        cp.setHighRiskJurisdiction(req.highRiskJurisdiction());
        cp.setComplexOwnership(req.complexOwnership());

        CddTier tier = deriveCddTier(cp);
        cp.setCddTier(tier.name());
        cp.setKycStatus(KycStatus.IN_PROGRESS.name());
        cp.setReKycDueDate(LocalDate.now().plusMonths(reKycMonths(tier)));

        Counterparty saved = repository.save(cp);
        audit.human(actor, "COUNTERPARTY_CREATED", "Counterparty", saved.getReference(),
                "Onboarded %s with CDD tier %s".formatted(saved.getLegalName(), tier),
                Map.of("jurisdiction", saved.getJurisdiction(), "cddTier", tier.name()));
        return saved;
    }

    /**
     * CDD intensity tiering — mirrors the active jurisdiction's CDD rule pack
     * (e.g. rbi_kyc_md_tiers). Enhanced triggers always win; simplified is only
     * available to low-risk listed/regulated entities with no triggers.
     */
    CddTier deriveCddTier(Counterparty cp) {
        if (cp.isPep() || cp.isHighRiskJurisdiction() || cp.isAdverseMedia() || cp.isComplexOwnership()) {
            return CddTier.ENHANCED;
        }
        if (cp.isListedEntity() || cp.isRegulatedFi()) {
            return CddTier.SIMPLIFIED;
        }
        return CddTier.STANDARD;
    }

    private int reKycMonths(CddTier tier) {
        return switch (tier) {
            case ENHANCED -> 12;
            case STANDARD -> 24;
            case SIMPLIFIED -> 36;
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
        cp.setVerifiedAt(java.time.Instant.now());
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
