package com.helix.counterparty.api;

import com.helix.counterparty.dto.InitiationDtos.AssignOwnershipRequest;
import com.helix.counterparty.dto.InitiationDtos.CreateGroupRequest;
import com.helix.counterparty.dto.InitiationDtos.CreateProspectRequest;
import com.helix.counterparty.dto.InitiationDtos.CreationSummary;
import com.helix.counterparty.dto.InitiationDtos.DecisionRequest;
import com.helix.counterparty.dto.InitiationDtos.DedupResult;
import com.helix.counterparty.dto.InitiationDtos.FetchCheckRequest;
import com.helix.counterparty.dto.InitiationDtos.GroupSuggestionResult;
import com.helix.counterparty.dto.InitiationDtos.NegativeResult;
import com.helix.counterparty.entity.Counterparty;
import com.helix.counterparty.entity.CounterpartyGroup;
import com.helix.counterparty.entity.ExternalCheck;
import com.helix.counterparty.entity.OwnershipAssignment;
import com.helix.counterparty.repo.CounterpartyGroupRepository;
import com.helix.counterparty.service.ExternalCheckService;
import com.helix.counterparty.service.GroupIdentificationService;
import com.helix.counterparty.service.InitiationService;
import com.helix.counterparty.service.RelationshipService;
import com.helix.common.web.ApiException;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/initiation")
public class InitiationController {

    private final InitiationService initiation;
    private final ExternalCheckService externalChecks;
    private final RelationshipService relationship;
    private final GroupIdentificationService groupIdentification;
    private final CounterpartyGroupRepository groupRepository;

    public InitiationController(InitiationService initiation, ExternalCheckService externalChecks,
                                RelationshipService relationship,
                                GroupIdentificationService groupIdentification,
                                CounterpartyGroupRepository groupRepository) {
        this.initiation = initiation;
        this.externalChecks = externalChecks;
        this.relationship = relationship;
        this.groupIdentification = groupIdentification;
        this.groupRepository = groupRepository;
    }

    // ---- prospect lifecycle ----

    @PostMapping("/prospects")
    public Counterparty createProspect(@Valid @RequestBody CreateProspectRequest req,
                                       @RequestHeader(value = "X-Actor", defaultValue = "rm.user") String actor) {
        return initiation.createProspect(req, actor);
    }

    @GetMapping("/prospects/{id}/dedup")
    public DedupResult dedup(@PathVariable Long id) {
        return initiation.dedupCheck(id);
    }

    @GetMapping("/prospects/{id}/negative-check")
    public NegativeResult negative(@PathVariable Long id) {
        return initiation.negativeCheck(id);
    }

    @GetMapping("/prospects/{id}/summary")
    public CreationSummary summary(@PathVariable Long id) {
        return initiation.creationSummary(id);
    }

    @PostMapping("/prospects/{id}/decision")
    public Counterparty decide(@PathVariable Long id, @Valid @RequestBody DecisionRequest req,
                               @RequestHeader(value = "X-Actor", defaultValue = "rm.user") String actor) {
        return initiation.decide(id, req.proceed(), req.reason(), actor);
    }

    @PostMapping("/prospects/{id}/approve")
    public Counterparty approve(@PathVariable Long id,
                                @RequestHeader(value = "X-Actor", defaultValue = "rm.user") String actor) {
        return initiation.approveObligor(id, actor);
    }

    @PostMapping("/auto-cleanup")
    public Map<String, Object> autoCleanup(@RequestHeader(value = "X-Actor", defaultValue = "system") String actor) {
        return initiation.autoCleanup(actor);
    }

    // ---- external / source-system checks ----

    @PostMapping("/prospects/{id}/checks/fetch")
    public ExternalCheck fetchCheck(@PathVariable Long id, @RequestBody FetchCheckRequest req,
                                    @RequestHeader(value = "X-Actor", defaultValue = "compliance.officer") String actor) {
        return externalChecks.fetch(id, req.entityType(), req.entityName(), req.checkType(), actor);
    }

    @PostMapping("/checks/{checkId}/refresh")
    public ExternalCheck refreshCheck(@PathVariable Long checkId,
                                      @RequestHeader(value = "X-Actor", defaultValue = "compliance.officer") String actor) {
        return externalChecks.refresh(checkId, actor);
    }

    @GetMapping("/prospects/{id}/checks")
    public List<ExternalCheck> checks(@PathVariable Long id) {
        return externalChecks.unifiedView(id);
    }

    // ---- RM ownership ----

    @PostMapping("/counterparties/{id}/ownership/request")
    public OwnershipAssignment requestOwnership(@PathVariable Long id, @Valid @RequestBody AssignOwnershipRequest req,
                                                @RequestHeader(value = "X-Actor", defaultValue = "rm.user") String actor) {
        return relationship.requestOwnership(id, req, actor);
    }

    @PostMapping("/ownership/{assignmentId}/decision")
    public OwnershipAssignment decideOwnership(@PathVariable Long assignmentId, @RequestParam boolean accept,
                                               @RequestHeader(value = "X-Actor", defaultValue = "rm.user") String actor) {
        return relationship.decideOwnership(assignmentId, accept, actor);
    }

    @GetMapping("/counterparties/{id}/ownership")
    public List<OwnershipAssignment> ownership(@PathVariable Long id) {
        return relationship.ownershipHistory(id);
    }

    // ---- groups ----

    @PostMapping("/groups")
    public CounterpartyGroup createGroup(@Valid @RequestBody CreateGroupRequest req,
                                         @RequestHeader(value = "X-Actor", defaultValue = "rm.user") String actor) {
        return relationship.createGroup(req, actor);
    }

    @GetMapping("/groups")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<CounterpartyGroup> listGroups() {
        return groupRepository.findAll();
    }

    @GetMapping("/groups/{groupId}")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public CounterpartyGroup getGroup(@PathVariable Long groupId) {
        return groupRepository.findById(groupId)
                .orElseThrow(() -> ApiException.notFound("No group: " + groupId));
    }

    @PostMapping("/counterparties/{id}/group/{groupId}")
    public Counterparty tagGroup(@PathVariable Long id, @PathVariable Long groupId,
                                 @RequestHeader(value = "X-Actor", defaultValue = "rm.user") String actor) {
        return relationship.tagToGroup(id, groupId, actor);
    }

    @GetMapping("/groups/{groupId}/exposure")
    public Map<String, Object> groupExposure(@PathVariable Long groupId) {
        return relationship.groupExposureSummary(groupId);
    }

    @GetMapping("/groups/by-reference/{reference}")
    public CounterpartyGroup groupByReference(@PathVariable String reference) {
        return groupRepository.findByReference(reference)
                .orElseThrow(() -> ApiException.notFound("No group: " + reference));
    }

    @GetMapping("/groups/by-reference/{reference}/exposure")
    public Map<String, Object> groupExposureByReference(@PathVariable String reference) {
        CounterpartyGroup g = groupRepository.findByReference(reference)
                .orElseThrow(() -> ApiException.notFound("No group: " + reference));
        return relationship.groupExposureSummary(g.getId());
    }

    // ---- advisory group identification (AI-assisted; human still tags) ----

    @PostMapping("/counterparties/{id}/group/suggest")
    public GroupSuggestionResult suggestGroup(@PathVariable Long id,
                                              @RequestHeader(value = "X-Actor", defaultValue = "rm.user") String actor) {
        return groupIdentification.suggest(id, actor);
    }
}
