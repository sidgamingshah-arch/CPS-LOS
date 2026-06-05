package com.helix.origination.service;

import com.helix.common.audit.AuditService;
import com.helix.common.web.ApiException;
import com.helix.origination.dto.StructureDtos.AddParticipantRequest;
import com.helix.origination.dto.StructureDtos.SetStructureRequest;
import com.helix.origination.dto.StructureDtos.StructureView;
import com.helix.origination.dto.StructureDtos.ValidationFinding;
import com.helix.origination.entity.DealParticipant;
import com.helix.origination.entity.DealStructure;
import com.helix.origination.entity.LoanApplication;
import com.helix.origination.repo.DealParticipantRepository;
import com.helix.origination.repo.DealStructureRepository;
import com.helix.origination.repo.LoanApplicationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Specialised deal/CP structures (PRD: group, joint-obligor, dual-obligor (Islamic),
 * syndication, FI ICR, renewal/copy). The structure and its participants are captured
 * here; {@link #view} validates the participant set against the rules of the chosen
 * variant and surfaces findings (no hard block on partial entry — findings drive the
 * UI and the credit-proposal gate). {@link #copyFrom} clones a prior proposal's
 * structure for a renewal/amendment.
 */
@Service
public class DealStructureService {

    private static final Set<String> TYPES =
            Set.of("SINGLE", "GROUP", "JOINT_OBLIGOR", "DUAL_OBLIGOR", "SYNDICATION", "FI_ICR");
    private static final Set<String> OBLIGOR_ROLES = Set.of("PRIMARY_OBLIGOR", "CO_OBLIGOR");
    private static final Set<String> LENDER_ROLES = Set.of("LEAD_BANK", "PARTICIPANT_LENDER");
    private static final Set<String> ROLES = Set.of("PRIMARY_OBLIGOR", "CO_OBLIGOR", "GUARANTOR",
            "GROUP_MEMBER", "LEAD_BANK", "PARTICIPANT_LENDER");

    private final DealStructureRepository structures;
    private final DealParticipantRepository participants;
    private final LoanApplicationRepository applications;
    private final AuditService audit;

    public DealStructureService(DealStructureRepository structures, DealParticipantRepository participants,
                                LoanApplicationRepository applications, AuditService audit) {
        this.structures = structures;
        this.participants = participants;
        this.applications = applications;
        this.audit = audit;
    }

    @Transactional
    public StructureView setStructure(String reference, SetStructureRequest req, String actor) {
        LoanApplication app = app(reference);
        String type = req.structureType() == null ? "SINGLE" : req.structureType().toUpperCase();
        if (!TYPES.contains(type)) {
            throw ApiException.badRequest("Unknown structureType: " + type + " (expected " + TYPES + ")");
        }
        DealStructure s = structures.findByApplicationReference(reference).orElseGet(DealStructure::new);
        s.setApplicationId(app.getId());
        s.setApplicationReference(reference);
        s.setStructureType(type);
        s.setIslamic(Boolean.TRUE.equals(req.islamic()) || ("DUAL_OBLIGOR".equals(type) && req.islamic() == null));
        s.setGroupReference(req.groupReference());
        s.setLeadArranger(req.leadArranger());
        s.setTotalDealAmount(nz(req.totalDealAmount()));
        s.setOurShareAmount(nz(req.ourShareAmount()));
        s.setOurSharePct(s.getTotalDealAmount() > 0
                ? round2(s.getOurShareAmount() / s.getTotalDealAmount() * 100.0) : 0.0);
        s.setNotes(req.notes());
        structures.save(s);
        audit.human(actor, "DEAL_STRUCTURE_SET", "Application", reference,
                "Structure = %s%s".formatted(type, s.isIslamic() ? " (Islamic)" : ""),
                Map.of("structureType", type, "islamic", s.isIslamic()));
        return view(reference);
    }

    @Transactional
    public DealParticipant addParticipant(String reference, AddParticipantRequest req, String actor) {
        LoanApplication app = app(reference);
        if (structures.findByApplicationReference(reference).isEmpty()) {
            throw ApiException.conflict("Set the deal structure before adding participants");
        }
        String role = req.role() == null ? "" : req.role().toUpperCase();
        if (!ROLES.contains(role)) {
            throw ApiException.badRequest("Unknown role: " + role + " (expected " + ROLES + ")");
        }
        if (req.name() == null || req.name().isBlank()) {
            throw ApiException.badRequest("Participant name required");
        }
        List<DealParticipant> existing = participants.findByApplicationReferenceOrderByOrdinalAsc(reference);
        DealParticipant p = new DealParticipant();
        p.setApplicationId(app.getId());
        p.setApplicationReference(reference);
        p.setRole(role);
        p.setName(req.name());
        p.setExternalRef(req.externalRef());
        p.setSharePct(nz(req.sharePct()));
        p.setObligationAmount(nz(req.obligationAmount()));
        p.setCommittedAmount(nz(req.committedAmount()));
        p.setLiabilityType(req.liabilityType() == null ? null : req.liabilityType().toUpperCase());
        p.setOrdinal(existing.size());
        DealParticipant saved = participants.save(p);
        audit.human(actor, "DEAL_PARTICIPANT_ADDED", "Application", reference,
                "%s: %s".formatted(role, req.name()), Map.of("role", role));
        return saved;
    }

    @Transactional
    public void removeParticipant(Long id, String actor) {
        DealParticipant p = participants.findById(id).orElseThrow(() -> ApiException.notFound("No participant: " + id));
        participants.delete(p);
        audit.human(actor, "DEAL_PARTICIPANT_REMOVED", "Application", p.getApplicationReference(),
                "Removed %s: %s".formatted(p.getRole(), p.getName()), Map.of());
    }

    /** Renewal/amendment: clone a prior proposal's structure + participants into this deal. */
    @Transactional
    public StructureView copyFrom(String targetReference, String sourceReference, String actor) {
        LoanApplication target = app(targetReference);
        DealStructure src = structures.findByApplicationReference(sourceReference)
                .orElseThrow(() -> ApiException.notFound("No structure on source " + sourceReference));
        if (structures.findByApplicationReference(targetReference).isPresent()) {
            throw ApiException.conflict("Target already has a structure — remove it before copying");
        }
        DealStructure s = new DealStructure();
        s.setApplicationId(target.getId());
        s.setApplicationReference(targetReference);
        s.setStructureType(src.getStructureType());
        s.setIslamic(src.isIslamic());
        s.setGroupReference(src.getGroupReference());
        s.setLeadArranger(src.getLeadArranger());
        s.setTotalDealAmount(src.getTotalDealAmount());
        s.setOurShareAmount(src.getOurShareAmount());
        s.setOurSharePct(src.getOurSharePct());
        s.setCopiedFromReference(sourceReference);
        s.setNotes(src.getNotes());
        structures.save(s);
        int n = 0;
        for (DealParticipant sp : participants.findByApplicationReferenceOrderByOrdinalAsc(sourceReference)) {
            DealParticipant p = new DealParticipant();
            p.setApplicationId(target.getId());
            p.setApplicationReference(targetReference);
            p.setRole(sp.getRole());
            p.setName(sp.getName());
            p.setExternalRef(sp.getExternalRef());
            p.setSharePct(sp.getSharePct());
            p.setObligationAmount(sp.getObligationAmount());
            p.setCommittedAmount(sp.getCommittedAmount());
            p.setLiabilityType(sp.getLiabilityType());
            p.setOrdinal(n++);
            participants.save(p);
        }
        audit.human(actor, "DEAL_STRUCTURE_COPIED", "Application", targetReference,
                "Copied %s structure + %d participant(s) from %s".formatted(src.getStructureType(), n, sourceReference),
                Map.of("source", sourceReference, "participants", n));
        return view(targetReference);
    }

    @Transactional(readOnly = true)
    public StructureView view(String reference) {
        DealStructure s = structures.findByApplicationReference(reference)
                .orElseThrow(() -> ApiException.notFound("No structure for " + reference + " — set one first"));
        List<DealParticipant> ps = participants.findByApplicationReferenceOrderByOrdinalAsc(reference);
        double obligorShareSum = round2(ps.stream().filter(p -> OBLIGOR_ROLES.contains(p.getRole()))
                .mapToDouble(DealParticipant::getSharePct).sum());
        double lenderCommitted = round2(ps.stream().filter(p -> LENDER_ROLES.contains(p.getRole()))
                .mapToDouble(DealParticipant::getCommittedAmount).sum());
        List<ValidationFinding> findings = validate(s, ps, obligorShareSum, lenderCommitted);
        boolean valid = findings.stream().noneMatch(f -> "ERROR".equals(f.level()));
        return new StructureView(s, ps, valid, findings, obligorShareSum, lenderCommitted);
    }

    // --------------------------------------------------- validation per variant

    private List<ValidationFinding> validate(DealStructure s, List<DealParticipant> ps,
                                             double obligorShareSum, double lenderCommitted) {
        List<ValidationFinding> f = new ArrayList<>();
        long obligors = ps.stream().filter(p -> OBLIGOR_ROLES.contains(p.getRole())).count();
        long lenders = ps.stream().filter(p -> LENDER_ROLES.contains(p.getRole())).count();
        long groupMembers = ps.stream().filter(p -> "GROUP_MEMBER".equals(p.getRole())).count();
        boolean liabilitySet = ps.stream().filter(p -> OBLIGOR_ROLES.contains(p.getRole()))
                .allMatch(p -> p.getLiabilityType() != null);

        switch (s.getStructureType()) {
            case "SINGLE" -> {
                if (obligors != 1) f.add(err("SINGLE requires exactly one obligor (found " + obligors + ")"));
                else f.add(ok("Single obligor"));
            }
            case "JOINT_OBLIGOR" -> {
                if (obligors < 2) f.add(err("JOINT_OBLIGOR requires at least two obligors"));
                if (!liabilitySet) f.add(warn("Set a liability type (JOINT / SEVERAL / JOINT_AND_SEVERAL) on each obligor"));
                if (Math.abs(obligorShareSum - 100.0) > 0.5) f.add(warn("Obligor shares sum to %.1f%% (expected 100%%)".formatted(obligorShareSum)));
                if (obligors >= 2 && liabilitySet) f.add(ok("Joint-obligor set validated"));
            }
            case "DUAL_OBLIGOR" -> {
                if (obligors != 2) f.add(err("DUAL_OBLIGOR requires exactly two obligors"));
                else f.add(ok("Two obligors present"));
                if (!s.isIslamic()) f.add(warn("Dual-obligor structures are typically Islamic — confirm the Islamic flag"));
            }
            case "GROUP" -> {
                if (s.getGroupReference() == null || s.getGroupReference().isBlank())
                    f.add(err("GROUP requires a group reference"));
                if (groupMembers < 1) f.add(warn("Add the group member entities"));
                if (obligors < 1) f.add(warn("Add the borrowing obligor within the group"));
                if (s.getGroupReference() != null && groupMembers >= 1) f.add(ok("Group structure validated"));
            }
            case "SYNDICATION" -> {
                if (lenders < 2) f.add(err("SYNDICATION requires at least two lenders (lead + participant)"));
                if (ps.stream().noneMatch(p -> "LEAD_BANK".equals(p.getRole())))
                    f.add(warn("Designate a LEAD_BANK"));
                if (s.getTotalDealAmount() <= 0) f.add(warn("Set the total syndicated amount"));
                else if (Math.abs(lenderCommitted - s.getTotalDealAmount()) > 1.0)
                    f.add(warn("Lender commitments (%.0f) ≠ total deal amount (%.0f)".formatted(lenderCommitted, s.getTotalDealAmount())));
                if (s.getOurShareAmount() <= 0) f.add(warn("Set our committed share"));
                else f.add(ok("Our share %.1f%% of %.0f".formatted(s.getOurSharePct(), s.getTotalDealAmount())));
            }
            case "FI_ICR" -> {
                if (obligors < 1) f.add(err("FI_ICR requires the FI obligor"));
                else f.add(ok("FI internal-credit-review structure"));
            }
            default -> f.add(warn("Unrecognised structure type"));
        }
        return f;
    }

    private LoanApplication app(String reference) {
        return applications.findByReference(reference)
                .orElseThrow(() -> ApiException.notFound("No application: " + reference));
    }

    private ValidationFinding ok(String m) {
        return new ValidationFinding("OK", m);
    }

    private ValidationFinding warn(String m) {
        return new ValidationFinding("WARN", m);
    }

    private ValidationFinding err(String m) {
        return new ValidationFinding("ERROR", m);
    }

    private double nz(Double d) {
        return d == null ? 0.0 : d;
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
