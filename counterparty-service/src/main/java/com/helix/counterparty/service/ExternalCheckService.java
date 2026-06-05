package com.helix.counterparty.service;

import com.helix.common.audit.AuditService;
import com.helix.common.web.ApiException;
import com.helix.counterparty.entity.Counterparty;
import com.helix.counterparty.entity.ExternalCheck;
import com.helix.counterparty.repo.CounterpartyRepository;
import com.helix.counterparty.repo.ExternalCheckRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Source-system check façade (PRD Stage 3). The actual checks run in source
 * systems (internal/external screening, credit bureau, KYC/AML, external rating);
 * Helix fetches status and can request a refresh, which the source re-runs and
 * hands back. One generic mechanism covers all five repeated integration features,
 * for any entity type (obligor, co-obligor, parent, guarantor, third party).
 */
@Service
public class ExternalCheckService {

    private static final Map<String, String> SOURCE = Map.of(
            "SCREENING_INTERNAL", "Internal-Screening",
            "SCREENING_EXTERNAL", "WorldCheck",
            "CREDIT_BUREAU", "CIBIL",
            "KYC_AML", "KYC-Hub",
            "EXTERNAL_RATING", "S&P");

    private final ExternalCheckRepository checks;
    private final CounterpartyRepository counterparties;
    private final AuditService audit;

    public ExternalCheckService(ExternalCheckRepository checks, CounterpartyRepository counterparties, AuditService audit) {
        this.checks = checks;
        this.counterparties = counterparties;
        this.audit = audit;
    }

    /** Fetches (or creates) the latest status from the source system for a check type. */
    @Transactional
    public ExternalCheck fetch(Long counterpartyId, String entityType, String entityName, String checkType, String actor) {
        Counterparty cp = counterparties.findById(counterpartyId)
                .orElseThrow(() -> ApiException.notFound("No counterparty: " + counterpartyId));
        ExternalCheck c = checks.findFirstByCounterpartyIdAndCheckTypeOrderByIdDesc(counterpartyId, checkType.toUpperCase())
                .orElseGet(ExternalCheck::new);
        c.setCounterpartyId(counterpartyId);
        c.setEntityType(entityType == null ? "OBLIGOR" : entityType.toUpperCase());
        c.setEntityName(entityName == null ? cp.getLegalName() : entityName);
        c.setEntityRef(cp.getReference());
        c.setCheckType(checkType.toUpperCase());
        c.setSourceSystem(SOURCE.getOrDefault(checkType.toUpperCase(), "SourceSystem"));
        c.setRmAssigned(cp.getRmId());
        applySimulatedStatus(c, cp);
        c.setLastFetchedAt(Instant.now());
        ExternalCheck saved = checks.save(c);
        audit.engine("EXTERNAL_CHECK_FETCHED", "Counterparty", cp.getReference(),
                "%s status %s from %s".formatted(c.getCheckType(), c.getStatus(), c.getSourceSystem()),
                Map.of("checkType", c.getCheckType(), "status", c.getStatus(), "source", c.getSourceSystem()));
        return saved;
    }

    /** Requests the source system to re-run the check, then fetches the refreshed status. */
    @Transactional
    public ExternalCheck refresh(Long checkId, String actor) {
        ExternalCheck c = checks.findById(checkId).orElseThrow(() -> ApiException.notFound("No check: " + checkId));
        c.setStatus("REFRESH_REQUESTED");
        c.setRefreshRequestedAt(Instant.now());
        c.setRefreshRequestedBy(actor);
        checks.save(c);
        audit.human(actor, "EXTERNAL_CHECK_REFRESH_REQUESTED", "Counterparty", c.getEntityRef(),
                "Requested %s refresh at %s".formatted(c.getCheckType(), c.getSourceSystem()),
                Map.of("checkType", c.getCheckType()));
        // Source system re-runs and hands back; re-derive the status.
        Counterparty cp = counterparties.findById(c.getCounterpartyId()).orElse(null);
        if (cp != null) {
            applySimulatedStatus(c, cp);
        }
        c.setLastFetchedAt(Instant.now());
        return checks.save(c);
    }

    /** Unified screening result view across all entity types for a counterparty. */
    @Transactional(readOnly = true)
    public List<ExternalCheck> unifiedView(Long counterpartyId) {
        return checks.findByCounterpartyIdOrderByCheckTypeAsc(counterpartyId);
    }

    private void applySimulatedStatus(ExternalCheck c, Counterparty cp) {
        // The source-system outcome is modelled from the counterparty's risk flags.
        String status;
        Map<String, Object> result;
        switch (c.getCheckType()) {
            case "SCREENING_INTERNAL", "SCREENING_EXTERNAL" -> {
                boolean hit = cp.isPep() || cp.isAdverseMedia() || cp.isHighRiskJurisdiction();
                status = hit ? "HIT" : "CLEAR";
                result = Map.of("pep", cp.isPep(), "adverseMedia", cp.isAdverseMedia(),
                        "highRiskJurisdiction", cp.isHighRiskJurisdiction());
            }
            case "KYC_AML" -> {
                status = cp.isHighRiskJurisdiction() ? "HIT" : "CLEAR";
                result = Map.of("cddTier", String.valueOf(cp.getCddTier()));
            }
            case "CREDIT_BUREAU" -> {
                status = "CLEAR";
                result = Map.of("score", 720, "inquiries6m", 2, "delinquencies24m", 0);
            }
            case "EXTERNAL_RATING" -> {
                status = "CLEAR";
                result = Map.of("agency", "S&P", "rating", "BBB", "outlook", "stable");
            }
            default -> {
                status = "CLEAR";
                result = Map.of();
            }
        }
        c.setStatus(status);
        c.setClassification(cp.getSegment());
        c.setResult(result);
    }
}
