package com.helix.decision.service;

import com.helix.common.audit.AuditService;
import com.helix.common.web.ApiException;
import com.helix.decision.client.LimitClient;
import com.helix.decision.client.UpstreamClient;
import com.helix.decision.client.UpstreamClient.MasterRecordDto;
import com.helix.decision.dto.CadDtos.CadCaseView;
import com.helix.decision.dto.CadDtos.InitiateCadRequest;
import com.helix.decision.dto.CadDtos.LimitReleaseRequest;
import com.helix.decision.dto.CadDtos.RaiseDeviationRequest;
import com.helix.decision.entity.CadCase;
import com.helix.decision.entity.ChecklistItem;
import com.helix.decision.entity.Deviation;
import com.helix.decision.repo.CadCaseRepository;
import com.helix.decision.repo.ChecklistItemRepository;
import com.helix.decision.repo.DeviationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Credit Administration (CAD) — post-CP documentation perfection (PRD CAD module):
 * checklist suggested from the CHECKLIST_MASTER, per-item status, a sequential
 * two-level waiver/deviation workflow (with SoD), and a limit-release checklist
 * that triggers the feed to limit management.
 */
@Service
public class CadService {

    private static final List<String> RESOLVED = List.of("COMPLIED", "WAIVED");

    private final CadCaseRepository cases;
    private final ChecklistItemRepository items;
    private final DeviationRepository deviations;
    private final UpstreamClient upstream;
    private final LimitClient limits;
    private final AuditService audit;

    /**
     * Optional best-effort case-management mirror; bean absent when the workflow-service
     * URL isn't configured. The CAD case / deviation workflow (checklist, two-level SoD
     * waiver, limit-release) is authoritative and completely unaffected — a mirror
     * failure is swallowed by the client and never reaches this transaction.
     */
    private final com.helix.common.workflow.TaskClient taskClient;

    public CadService(CadCaseRepository cases, ChecklistItemRepository items, DeviationRepository deviations,
                      UpstreamClient upstream, LimitClient limits, AuditService audit,
                      @org.springframework.beans.factory.annotation.Autowired(required = false)
                      com.helix.common.workflow.TaskClient taskClient) {
        this.cases = cases;
        this.items = items;
        this.deviations = deviations;
        this.upstream = upstream;
        this.limits = limits;
        this.audit = audit;
        this.taskClient = taskClient;
    }

    /** Opens a CAD case and suggests the checklist from the CHECKLIST_MASTER. */
    @Transactional
    @SuppressWarnings("unchecked")
    public CadCaseView initiate(InitiateCadRequest req, String actor) {
        CadCase c = new CadCase();
        c.setApplicationRef(req.applicationRef());
        c.setCounterpartyName(req.counterpartyName());
        c.setCpType(req.cpType() == null ? "NEW" : req.cpType().toUpperCase());
        c.setStatus("CHECKLIST");
        c.setCreatedBy(actor);

        List<MasterRecordDto> masters = upstream.masters("CHECKLIST_MASTER");
        MasterRecordDto chosen = masters.stream()
                .filter(m -> m.recordKey().contains("SECURED")).findFirst()
                .orElse(masters.isEmpty() ? null : masters.get(0));
        c.setChecklistKey(chosen == null ? "default" : chosen.recordKey());
        CadCase saved = cases.save(c);

        List<String> itemList = chosen == null ? List.of("Sanction letter", "Facility agreement")
                : (List<String>) chosen.payload().getOrDefault("items", List.of("Sanction letter", "Facility agreement"));
        int i = 0;
        for (String desc : itemList) {
            ChecklistItem item = new ChecklistItem();
            item.setCadCaseId(saved.getId());
            item.setCode("DOC_" + (++i));
            item.setDescription(desc);
            item.setMandatory(true);
            item.setStatus("PENDING");
            items.save(item);
        }
        audit.human(actor, "CAD_INITIATED", "Application", req.applicationRef(),
                "Opened CAD case (%s) with %d checklist items from %s".formatted(c.getCpType(), itemList.size(), c.getChecklistKey()),
                Map.of("checklistKey", c.getChecklistKey(), "items", itemList.size()));
        return view(saved.getId());
    }

    @Transactional(readOnly = true)
    public List<CadCase> inbox(String status) {
        return status == null ? cases.findAll() : cases.findByStatusOrderByCreatedAtAsc(status.toUpperCase());
    }

    @Transactional(readOnly = true)
    public CadCaseView view(Long caseId) {
        CadCase c = getCase(caseId);
        return new CadCaseView(c, items.findByCadCaseIdOrderByIdAsc(caseId), deviations.findByCadCaseIdOrderByCreatedAtDesc(caseId));
    }

    @Transactional
    public ChecklistItem updateItem(Long itemId, String status, String docRef, String comment, String actor) {
        ChecklistItem item = items.findById(itemId).orElseThrow(() -> ApiException.notFound("No item: " + itemId));
        item.setStatus(status.toUpperCase());
        item.setDocRef(docRef);
        item.setComment(comment);
        item.setUpdatedBy(actor);
        ChecklistItem saved = items.save(item);
        audit.human(actor, "CAD_ITEM_UPDATED", "CadItem", String.valueOf(itemId),
                "%s -> %s".formatted(item.getDescription(), status), Map.of("status", status));
        return saved;
    }

    // ---- sequential two-level waiver/deviation ----

    @Transactional
    public Deviation raiseDeviation(Long itemId, RaiseDeviationRequest req, String actor) {
        ChecklistItem item = items.findById(itemId).orElseThrow(() -> ApiException.notFound("No item: " + itemId));
        Deviation d = new Deviation();
        d.setCadCaseId(item.getCadCaseId());
        d.setChecklistItemId(itemId);
        d.setType(req.type().toUpperCase());
        d.setReason(req.reason());
        d.setStatus("PENDING_L1");
        d.setRaisedBy(actor);
        Deviation saved = deviations.save(d);
        item.setStatus("DEVIATION");
        items.save(item);
        CadCase c = getCase(item.getCadCaseId());
        c.setStatus("DEVIATION");
        cases.save(c);
        audit.human(actor, "CAD_DEVIATION_RAISED", "CadCase", String.valueOf(item.getCadCaseId()),
                "%s on '%s': %s".formatted(d.getType(), item.getDescription(), req.reason()),
                Map.of("type", d.getType()));
        // Best-effort case-management mirror: a deviation/waiver approval task on the CAD
        // deviations queue. Advisory task tracking only — the CadCase / Deviation above is
        // authoritative and unchanged by this call.
        if (taskClient != null) {
            taskClient.createTask("CadCase", c.getApplicationRef(), "CAD_DEVIATION_APPROVAL",
                    "CAD_DEVIATIONS", null, "DEV:" + saved.getId(), null, actor,
                    Map.of("deviationId", saved.getId(), "cadCaseId", item.getCadCaseId(),
                            "checklistItemId", itemId, "type", d.getType()));
        }
        return saved;
    }

    @Transactional
    public Deviation decideDeviation(Long devId, boolean approve, String comment, String actor) {
        Deviation d = deviations.findById(devId).orElseThrow(() -> ApiException.notFound("No deviation: " + devId));
        if (actor.equalsIgnoreCase(d.getRaisedBy())) {
            throw ApiException.forbiddenAutonomy("Approver cannot be the raiser (segregation of duties)");
        }
        d.setDecisionComment(comment);
        if (!approve) {
            d.setStatus("REJECTED");
            audit.human(actor, "CAD_DEVIATION_REJECTED", "CadDeviation", String.valueOf(devId), "Rejected", Map.of());
            return deviations.save(d);
        }
        switch (d.getStatus()) {
            case "PENDING_L1" -> {
                d.setApproverL1(actor);
                d.setStatus("PENDING_L2");
                audit.human(actor, "CAD_DEVIATION_L1_APPROVED", "CadDeviation", String.valueOf(devId),
                        "Level-1 approved; pending level-2", Map.of());
            }
            case "PENDING_L2" -> {
                if (actor.equalsIgnoreCase(d.getApproverL1())) {
                    throw ApiException.forbiddenAutonomy("Level-2 approver must differ from level-1");
                }
                d.setApproverL2(actor);
                d.setStatus("APPROVED");
                ChecklistItem item = items.findById(d.getChecklistItemId()).orElseThrow();
                item.setStatus("WAIVED");
                items.save(item);
                audit.human(actor, "CAD_DEVIATION_APPROVED", "CadDeviation", String.valueOf(devId),
                        "Level-2 approved; item waived", Map.of());
            }
            default -> throw ApiException.conflict("Deviation already decided");
        }
        return deviations.save(d);
    }

    // ---- completion + limit release ----

    @Transactional
    public CadCaseView complete(Long caseId, String actor) {
        CadCase c = getCase(caseId);
        List<ChecklistItem> all = items.findByCadCaseIdOrderByIdAsc(caseId);
        List<ChecklistItem> unresolved = all.stream()
                .filter(ChecklistItem::isMandatory)
                .filter(i -> !RESOLVED.contains(i.getStatus()))
                .toList();
        if (!unresolved.isEmpty()) {
            throw ApiException.conflict("%d mandatory item(s) not complied/waived".formatted(unresolved.size()));
        }
        c.setStatus("COMPLETED");
        cases.save(c);
        audit.human(actor, "CAD_COMPLETED", "Application", c.getApplicationRef(),
                "All mandatory documentation complied/waived", Map.of("items", all.size()));
        return view(caseId);
    }

    @Transactional
    public CadCaseView limitRelease(Long caseId, LimitReleaseRequest req, String actor) {
        CadCase c = getCase(caseId);
        if (!"COMPLETED".equals(c.getStatus())) {
            throw ApiException.conflict("Complete the documentation checklist before limit release");
        }
        if (!(req.processingFeeAmortised() && req.lienMarked())) {
            throw ApiException.badRequest("Limit-release checklist incomplete (processing fee / lien)");
        }
        c.setStatus("LIMIT_RELEASED");
        cases.save(c);
        audit.human(actor, "CAD_LIMIT_RELEASE", "Application", c.getApplicationRef(),
                "Limit-release checklist complete", Map.of("lienMarked", req.lienMarked()));
        // REAL feed to limit-service: release (activate) the application's limit nodes so
        // disbursement can proceed. Governance SIDE-EFFECT of the already-committed CAD
        // release — a limit-service outage or a not-yet-built tree must never roll it back.
        LimitClient.AppStatusResultDto released =
                limits.releaseApplication(c.getApplicationRef(), "CAD limit release", actor);
        if (released.affectedCount() < 0) {
            audit.engine("LIMIT_RELEASE_SKIPPED", "Application", c.getApplicationRef(),
                    "limit-service unavailable; limits NOT released for %s (CAD stands, retry release)"
                            .formatted(c.getApplicationRef()),
                    Map.of("applicationRef", c.getApplicationRef(), "outcome", "SKIPPED"));
        } else {
            audit.engine("LIMIT_RELEASE_TRIGGER", "Application", c.getApplicationRef(),
                    "CAD released %d/%d limit node(s) for %s -> limit-service".formatted(
                            released.affectedCount(), released.totalNodes(), c.getApplicationRef()),
                    Map.of("applicationRef", c.getApplicationRef(),
                            "releasedCount", released.affectedCount(),
                            "totalNodes", released.totalNodes()));
        }
        return view(caseId);
    }

    private CadCase getCase(Long id) {
        return cases.findById(id).orElseThrow(() -> ApiException.notFound("No CAD case: " + id));
    }
}
