package com.helix.counterparty.service;

import com.helix.common.audit.AuditService;
import com.helix.common.util.References;
import com.helix.common.web.ApiException;
import com.helix.counterparty.dto.InitiationDtos.AssignOwnershipRequest;
import com.helix.counterparty.dto.InitiationDtos.CreateGroupRequest;
import com.helix.counterparty.entity.Counterparty;
import com.helix.counterparty.entity.CounterpartyGroup;
import com.helix.counterparty.entity.OwnershipAssignment;
import com.helix.counterparty.repo.CounterpartyGroupRepository;
import com.helix.counterparty.repo.CounterpartyRepository;
import com.helix.counterparty.repo.OwnershipAssignmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * RM ownership (assign/claim subject to acceptance) and borrower groups
 * (creation, tagging, exposure summary) — PRD §1 configurable RM management,
 * automated ownership resolution, group fetching, group exposure summary.
 */
@Service
public class RelationshipService {

    private final CounterpartyRepository counterparties;
    private final CounterpartyGroupRepository groups;
    private final OwnershipAssignmentRepository assignments;
    private final AuditService audit;

    public RelationshipService(CounterpartyRepository counterparties, CounterpartyGroupRepository groups,
                               OwnershipAssignmentRepository assignments, AuditService audit) {
        this.counterparties = counterparties;
        this.groups = groups;
        this.assignments = assignments;
        this.audit = audit;
    }

    // ----------------------------------------------------------- ownership

    /** Assign (push) or claim (pull) ownership — pending until the receiving RM accepts. */
    @Transactional
    public OwnershipAssignment requestOwnership(Long counterpartyId, AssignOwnershipRequest req, String actor) {
        Counterparty cp = cp(counterpartyId);
        OwnershipAssignment a = new OwnershipAssignment();
        a.setCounterpartyId(counterpartyId);
        a.setFromRm(cp.getRmId());
        a.setToRm(req.toRm());
        a.setMode(req.mode() == null ? "ASSIGN" : req.mode().toUpperCase());
        a.setStatus("PENDING");
        a.setRequestedBy(actor);
        a.setNote(req.note());
        OwnershipAssignment saved = assignments.save(a);
        audit.human(actor, "OWNERSHIP_REQUESTED", "Counterparty", cp.getReference(),
                "%s ownership %s -> %s (pending acceptance)".formatted(a.getMode(), a.getFromRm(), a.getToRm()),
                Map.of("mode", a.getMode(), "toRm", a.getToRm()));
        // Notification to existing RM / stakeholders (template-driven, logged).
        audit.engine("NOTIFICATION_SENT", "Counterparty", cp.getReference(),
                "EMAIL_TEMPLATE OWNERSHIP_CLAIM -> %s".formatted(cp.getRmId()),
                Map.of("template", "OWNERSHIP_CLAIM"));
        return saved;
    }

    @Transactional
    public OwnershipAssignment decideOwnership(Long assignmentId, boolean accept, String actor) {
        OwnershipAssignment a = assignments.findById(assignmentId)
                .orElseThrow(() -> ApiException.notFound("No assignment: " + assignmentId));
        if (!"PENDING".equals(a.getStatus())) {
            throw ApiException.conflict("Assignment already decided");
        }
        if (!a.getToRm().equalsIgnoreCase(actor)) {
            throw ApiException.forbiddenAutonomy("Only the receiving RM (%s) can accept/reject".formatted(a.getToRm()));
        }
        a.setDecidedAt(Instant.now());
        if (accept) {
            a.setStatus("ACCEPTED");
            Counterparty cp = cp(a.getCounterpartyId());
            cp.setRmId(a.getToRm());
            counterparties.save(cp);
            audit.human(actor, "OWNERSHIP_ACCEPTED", "Counterparty", cp.getReference(),
                    "Ownership reassigned to %s".formatted(a.getToRm()), Map.of("newRm", a.getToRm()));
            audit.engine("NOTIFICATION_SENT", "Counterparty", cp.getReference(),
                    "EMAIL_TEMPLATE OWNERSHIP_CHANGE -> %s".formatted(a.getToRm()), Map.of("template", "OWNERSHIP_CHANGE"));
        } else {
            a.setStatus("REJECTED");
            audit.human(actor, "OWNERSHIP_REJECTED", "Counterparty", String.valueOf(a.getCounterpartyId()),
                    "Ownership transfer rejected", Map.of());
        }
        return assignments.save(a);
    }

    @Transactional(readOnly = true)
    public List<OwnershipAssignment> ownershipHistory(Long counterpartyId) {
        return assignments.findByCounterpartyIdOrderByRequestedAtDesc(counterpartyId);
    }

    // --------------------------------------------------------------- groups

    @Transactional
    public CounterpartyGroup createGroup(CreateGroupRequest req, String actor) {
        CounterpartyGroup g = new CounterpartyGroup();
        g.setReference("GRP-" + References.forCounterparty());
        g.setName(req.name());
        g.setGroupRmId(req.groupRmId() == null ? actor : req.groupRmId());
        g.setCountry(req.country());
        g.setMultiCountry(req.multiCountry());
        CounterpartyGroup saved = groups.save(g);
        audit.human(actor, "GROUP_CREATED", "Group", saved.getReference(),
                "Created group %s%s".formatted(saved.getName(), saved.isMultiCountry() ? " (multi-country)" : ""),
                Map.of("groupRm", saved.getGroupRmId()));
        return saved;
    }

    @Transactional
    public Counterparty tagToGroup(Long counterpartyId, Long groupId, String actor) {
        CounterpartyGroup g = groups.findById(groupId).orElseThrow(() -> ApiException.notFound("No group: " + groupId));
        Counterparty cp = cp(counterpartyId);
        cp.setGroupId(groupId);
        Counterparty saved = counterparties.save(cp);
        audit.human(actor, "GROUP_TAGGED", "Counterparty", cp.getReference(),
                "Tagged to group %s; group RM %s".formatted(g.getName(), g.getGroupRmId()),
                Map.of("groupId", groupId, "groupRm", String.valueOf(g.getGroupRmId())));
        return saved;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> groupExposureSummary(Long groupId) {
        CounterpartyGroup g = groups.findById(groupId).orElseThrow(() -> ApiException.notFound("No group: " + groupId));
        List<Counterparty> members = counterparties.findByGroupId(groupId);
        long withAdverse = members.stream().filter(Counterparty::isAdverseMedia).count();
        long obligors = members.stream().filter(m -> "OBLIGOR".equals(m.getRecordType())).count();
        return Map.of(
                "group", Map.of("reference", g.getReference(), "name", g.getName(),
                        "groupRm", String.valueOf(g.getGroupRmId()), "multiCountry", g.isMultiCountry()),
                "memberCount", members.size(),
                "obligorCount", obligors,
                "members", members.stream().map(m -> Map.of(
                        "reference", m.getReference(), "name", m.getLegalName(),
                        "recordType", m.getRecordType(), "segment", m.getSegment(), "rm", String.valueOf(m.getRmId()))).toList(),
                "riskFlags", Map.of("adverseMediaMembers", withAdverse));
    }

    private Counterparty cp(Long id) {
        return counterparties.findById(id).orElseThrow(() -> ApiException.notFound("No counterparty: " + id));
    }
}
