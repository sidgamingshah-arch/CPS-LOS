package com.helix.decision.service;

import com.helix.common.audit.AuditService;
import com.helix.common.web.ApiException;
import com.helix.decision.client.UpstreamClient;
import com.helix.decision.client.UpstreamClient.DealEnvelopeDto;
import com.helix.decision.client.UpstreamClient.FacilityViewDto;
import com.helix.decision.client.UpstreamClient.MasterRecordDto;
import com.helix.decision.dto.CpDtos.CpBlocker;
import com.helix.decision.dto.CpDtos.CpGateResult;
import com.helix.decision.entity.ConditionPrecedent;
import com.helix.decision.entity.Disbursement;
import com.helix.decision.repo.ConditionPrecedentRepository;
import com.helix.decision.repo.DisbursementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Manages the per-facility CP register. The register is normally seeded at
 * sanction-time from {@code CP_MASTER} (templated items, jurisdiction-overridable);
 * custom items can be added on the deal. Items move through OPEN → CLEARED / WAIVED
 * / REJECTED with named-human SoD on clear vs. waive.
 *
 * <p>The pre-disbursement gate (see {@link DisbursementService#authorize}) reads
 * this register: any mandatory OPEN item blocks the drawdown authorisation.</p>
 */
@Service
public class ConditionPrecedentService {

    private final ConditionPrecedentRepository repo;
    private final DisbursementRepository disbursements;
    private final UpstreamClient upstream;
    private final AuditService audit;

    public ConditionPrecedentService(ConditionPrecedentRepository repo, DisbursementRepository disbursements,
                                     UpstreamClient upstream, AuditService audit) {
        this.repo = repo;
        this.disbursements = disbursements;
        this.upstream = upstream;
        this.audit = audit;
    }

    // ============================================================ register seeding

    /**
     * Builds the CP register for every facility on the deal from {@code CP_MASTER}.
     * If the register already has rows for a facility, those are left alone (this
     * is idempotent — re-seeding after sanction won't duplicate).
     */
    @Transactional
    public List<ConditionPrecedent> seedFromMaster(String applicationReference, String actor) {
        DealEnvelopeDto env = upstream.envelope(applicationReference);
        if (env == null) {
            throw ApiException.notFound("No deal envelope for " + applicationReference);
        }
        String jurisdiction = env.jurisdiction();
        List<MasterRecordDto> cpMasters = upstream.masters("CP_MASTER");
        List<ConditionPrecedent> seeded = new ArrayList<>();
        for (FacilityViewDto f : env.facilities() == null ? List.<FacilityViewDto>of() : env.facilities()) {
            List<ConditionPrecedent> existing = repo.findByApplicationReferenceAndFacilityRefOrderByIdAsc(
                    applicationReference, f.reference());
            if (!existing.isEmpty()) continue;
            MasterRecordDto pack = pickPack(cpMasters, f.facilityType(), jurisdiction);
            if (pack == null) continue;
            Object items = pack.payload().get("items");
            if (!(items instanceof List<?> list)) continue;
            for (Object it : list) {
                if (!(it instanceof Map<?, ?> raw)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) raw;
                ConditionPrecedent cp = new ConditionPrecedent();
                cp.setApplicationReference(applicationReference);
                cp.setFacilityRef(f.reference());
                Object code = m.get("code");
                cp.setCode(code == null ? ("CP-" + (seeded.size() + 1)) : String.valueOf(code));
                Object title = m.get("title");
                cp.setTitle(title == null ? "Condition" : String.valueOf(title));
                cp.setDescription(m.get("description") == null ? null : String.valueOf(m.get("description")));
                Object mandatoryFlag = m.get("mandatory");
                cp.setMandatory(mandatoryFlag == null || (mandatoryFlag instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(mandatoryFlag))));
                cp.setSource("TEMPLATE");
                seeded.add(repo.save(cp));
            }
            audit.engine("CP_SEEDED", "Application", applicationReference,
                    "Seeded %d CP(s) for facility %s from CP_MASTER %s".formatted(
                            seeded.size(), f.reference(), pack.recordKey()),
                    Map.of("facilityRef", f.reference(), "items", seeded.size(),
                            "masterKey", pack.recordKey()));
        }
        return seeded;
    }

    /**
     * Picks the most-specific CP_MASTER record for the facility. Master rows are
     * keyed by {@code recordKey=<facilityType>} plus an optional {@code jurisdiction}
     * column; a row with a matching jurisdiction wins over the default (null
     * jurisdiction) row.
     */
    private MasterRecordDto pickPack(List<MasterRecordDto> masters, String facilityType, String jurisdiction) {
        if (facilityType == null || masters == null) return null;
        MasterRecordDto override = null;
        MasterRecordDto def = null;
        for (MasterRecordDto m : masters) {
            if (!facilityType.equals(m.recordKey())) continue;
            String j = m.jurisdiction();
            if (jurisdiction != null && jurisdiction.equals(j)) override = m;
            else if (j == null || j.isBlank()) def = m;
        }
        return override != null ? override : def;
    }

    // ============================================================ register operations

    @Transactional
    public ConditionPrecedent addCustom(String applicationReference, String facilityRef, String code,
                                        String title, String description, boolean mandatory, String actor) {
        ConditionPrecedent cp = new ConditionPrecedent();
        cp.setApplicationReference(applicationReference);
        cp.setFacilityRef(facilityRef);
        cp.setCode(code == null || code.isBlank() ? "CUSTOM-" + System.currentTimeMillis() : code);
        cp.setTitle(title);
        cp.setDescription(description);
        cp.setMandatory(mandatory);
        cp.setSource("CUSTOM");
        ConditionPrecedent saved = repo.save(cp);
        audit.human(actor, "CP_ADDED", "ConditionPrecedent", String.valueOf(saved.getId()),
                "Added CP " + cp.getCode() + " on " + facilityRef,
                Map.of("facilityRef", facilityRef, "mandatory", mandatory));
        return saved;
    }

    @Transactional
    public ConditionPrecedent clear(Long id, String evidenceRef, String note, String actor) {
        ConditionPrecedent cp = get(id);
        if (!"OPEN".equals(cp.getStatus())) {
            throw ApiException.conflict("CP is " + cp.getStatus());
        }
        cp.setStatus("CLEARED");
        cp.setClearedBy(actor);
        cp.setClearedAt(Instant.now());
        cp.setEvidenceRef(evidenceRef);
        ConditionPrecedent saved = repo.save(cp);
        audit.human(actor, "CP_CLEARED", "ConditionPrecedent", String.valueOf(id),
                "Cleared CP " + cp.getCode() + (note == null ? "" : " — " + note),
                Map.of("facilityRef", cp.getFacilityRef(), "evidenceRef", evidenceRef == null ? "" : evidenceRef));
        return saved;
    }

    /**
     * Waives a mandatory-or-optional CP. SoD: the waiver must not be the requester
     * of any in-flight (DRAFT / AUTHORIZED) drawdown on this facility — otherwise
     * the person asking for the money could dissolve their own evidence gate.
     */
    @Transactional
    public ConditionPrecedent waive(Long id, String reason, String actor) {
        if (actor == null || actor.isBlank()) {
            throw ApiException.forbiddenAutonomy("A named human actor (X-Actor header) is required to waive a CP");
        }
        ConditionPrecedent cp = get(id);
        if (!"OPEN".equals(cp.getStatus())) {
            throw ApiException.conflict("CP is " + cp.getStatus());
        }
        for (Disbursement d : disbursements.findByApplicationReferenceAndFacilityRefOrderByDrawdownNoAsc(
                cp.getApplicationReference(), cp.getFacilityRef())) {
            boolean inFlight = "DRAFT".equals(d.getStatus()) || "AUTHORIZED".equals(d.getStatus());
            if (inFlight && actor.equals(d.getRequestedBy())) {
                throw ApiException.forbiddenAutonomy(
                        "CP waiver must differ from the requester of in-flight drawdown #"
                        + d.getDrawdownNo() + " on " + cp.getFacilityRef()
                        + " — a drawdown requester cannot waive their own conditions precedent");
            }
        }
        cp.setStatus("WAIVED");
        cp.setWaivedBy(actor);
        cp.setWaivedReason(reason);
        cp.setWaivedAt(Instant.now());
        ConditionPrecedent saved = repo.save(cp);
        audit.human(actor, "CP_WAIVED", "ConditionPrecedent", String.valueOf(id),
                "Waived CP " + cp.getCode() + " — " + reason,
                Map.of("facilityRef", cp.getFacilityRef(), "reason", reason));
        return saved;
    }

    @Transactional
    public ConditionPrecedent reject(Long id, String reason, String actor) {
        ConditionPrecedent cp = get(id);
        if (!"OPEN".equals(cp.getStatus())) {
            throw ApiException.conflict("CP is " + cp.getStatus());
        }
        cp.setStatus("REJECTED");
        cp.setRejectedBy(actor);
        cp.setRejectedReason(reason);
        cp.setRejectedAt(Instant.now());
        ConditionPrecedent saved = repo.save(cp);
        audit.human(actor, "CP_REJECTED", "ConditionPrecedent", String.valueOf(id),
                "Rejected CP " + cp.getCode() + " — " + reason,
                Map.of("facilityRef", cp.getFacilityRef(), "reason", reason));
        return saved;
    }

    @Transactional(readOnly = true)
    public ConditionPrecedent get(Long id) {
        return repo.findById(id).orElseThrow(() -> ApiException.notFound("No CP: " + id));
    }

    @Transactional(readOnly = true)
    public List<ConditionPrecedent> register(String applicationReference) {
        return repo.findByApplicationReferenceOrderByIdAsc(applicationReference);
    }

    @Transactional(readOnly = true)
    public List<ConditionPrecedent> registerForFacility(String applicationReference, String facilityRef) {
        return repo.findByApplicationReferenceAndFacilityRefOrderByIdAsc(applicationReference, facilityRef);
    }

    // ============================================================ pre-disbursement gate

    /**
     * Computes whether the facility can draw down: counts mandatory CPs and surfaces
     * the OPEN ones as a {@code blockers} list. Used by both the disbursement gate
     * and the UI to render a clear "X of N CPs cleared" status before the user even
     * tries to authorize.
     */
    @Transactional(readOnly = true)
    public CpGateResult gate(String applicationReference, String facilityRef) {
        List<ConditionPrecedent> all = repo.findByApplicationReferenceAndFacilityRefOrderByIdAsc(
                applicationReference, facilityRef);
        int mandatoryTotal = 0, mandatoryOpen = 0;
        List<CpBlocker> blockers = new ArrayList<>();
        for (ConditionPrecedent cp : all) {
            if (!cp.isMandatory()) continue;
            mandatoryTotal++;
            if ("OPEN".equals(cp.getStatus())) {
                mandatoryOpen++;
                blockers.add(new CpBlocker(cp.getCode(), cp.getTitle(), cp.getDescription()));
            }
            // CLEARED / WAIVED / REJECTED — REJECTED still blocks (it failed), but it's not
            // a "blocker that can be cleared by collecting evidence"; we treat REJECTED as a
            // hard stop on this facility's drawdown.
            if ("REJECTED".equals(cp.getStatus())) {
                mandatoryOpen++;
                blockers.add(new CpBlocker(cp.getCode(),
                        "REJECTED: " + cp.getTitle(),
                        cp.getRejectedReason() == null ? cp.getDescription() : cp.getRejectedReason()));
            }
        }
        boolean canDrawdown = mandatoryOpen == 0;
        return new CpGateResult(facilityRef, canDrawdown, mandatoryOpen, mandatoryTotal, blockers);
    }
}
