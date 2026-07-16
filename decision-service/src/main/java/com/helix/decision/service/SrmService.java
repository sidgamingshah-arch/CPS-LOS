package com.helix.decision.service;

import com.helix.common.audit.AuditService;
import com.helix.common.web.ApiException;
import com.helix.decision.client.UpstreamClient;
import com.helix.decision.client.UpstreamClient.MasterRecordDto;
import com.helix.decision.dto.NotingDtos.CreateNotingRequest;
import com.helix.decision.dto.SrmDtos.CreateSrmRequest;
import com.helix.decision.entity.MerItem;
import com.helix.decision.entity.Noting;
import com.helix.decision.entity.SrmReview;
import com.helix.decision.repo.SrmReviewRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SRM (Structured Review / renewal) — a thin renewal workflow built <b>on the Noting
 * engine</b>, not a new engine. Creating an SRM review materialises a renewal checklist
 * from the {@code SRM_CHECKLIST} master (config-as-data) and delegates the governed
 * decision to a {@code NOTING_TYPE=SRM_RENEWAL} noting (via {@link NotingService},
 * in-process — the noting lifecycle is never duplicated here). When that noting is
 * observed to have reached {@code AUTHORIZED}, the SRM fires a minimal, additive MER
 * hook ({@link MerService#advanceReviewForSrm}) that advances the subject's next
 * review / renewal due date. An SRM review is a RECORD only — it mutates no
 * authoritative figure.
 */
@Service
public class SrmService {

    private static final Logger log = LoggerFactory.getLogger(SrmService.class);

    /** Conservative built-in checklist when no SRM_CHECKLIST master row is seeded. */
    private static final List<String> DEFAULT_ITEMS = List.of(
            "Updated audited financials obtained",
            "Account conduct reviewed",
            "Covenant compliance certified",
            "Security & insurance current",
            "Internal rating refreshed");

    private final SrmReviewRepository reviews;
    private final NotingService notings;
    private final MerService mer;
    private final UpstreamClient upstream;
    private final AuditService audit;

    public SrmService(SrmReviewRepository reviews, NotingService notings, MerService mer,
                      UpstreamClient upstream, AuditService audit) {
        this.reviews = reviews;
        this.notings = notings;
        this.mer = mer;
        this.upstream = upstream;
        this.audit = audit;
    }

    // ---- create / read ----

    /**
     * Opens an SRM review: materialises the checklist from the SRM_CHECKLIST master and
     * raises the linked SRM_RENEWAL noting (DRAFT) via the Noting engine.
     */
    @Transactional
    public SrmReview create(CreateSrmRequest req, String actor) {
        SrmReview r = new SrmReview();
        r.setSrmRef(generateRef());
        r.setSubjectType(req.subjectType() == null || req.subjectType().isBlank() ? "Counterparty" : req.subjectType());
        r.setSubjectRef(req.subjectRef());
        r.setCounterpartyName(req.counterpartyName());
        r.setTitle(req.title() == null || req.title().isBlank()
                ? "Structured review / renewal — " + req.subjectRef() : req.title());
        r.setRaisedBy(actor);
        r.setStatus(SrmReview.Status.OPEN);

        MasterRecordDto chosen = pickChecklistMaster(req.checklistKey());
        r.setChecklistKey(chosen == null ? "default" : chosen.recordKey());
        List<String> labels = chosen == null ? DEFAULT_ITEMS : coerceItems(chosen.payload());
        List<Map<String, Object>> items = new ArrayList<>();
        int i = 0;
        for (String label : labels) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", "SRM-" + (++i));
            item.put("label", label);
            item.put("mandatory", true);
            item.put("done", false);
            items.add(item);
        }
        Map<String, Object> checklist = new LinkedHashMap<>();
        checklist.put("items", items);
        r.setChecklist(checklist);

        // Delegate the governed decision to the Noting engine (SRM_RENEWAL). We create the
        // noting in DRAFT here and never duplicate its lifecycle — submit/approve/CAD run
        // through NotingService (in-process) and /api/notings.
        CreateNotingRequest nr = new CreateNotingRequest(
                "SRM_RENEWAL", r.getSubjectType(), r.getSubjectRef(),
                "SRM renewal — " + r.getSubjectRef(),
                "Structured review / renewal decision record.",
                Map.of("srmChecklistKey", r.getChecklistKey()));
        Noting noting = notings.create(nr, actor);
        r.setNotingRef(noting.getNotingRef());
        r.setNotingStatus(noting.getStatus().name());

        SrmReview saved = reviews.save(r);
        audit.human(actor, "SRM_CREATED", "SrmReview", saved.getSrmRef(),
                "SRM review for %s — %d checklist item(s) from %s; noting %s".formatted(
                        saved.getSubjectRef(), items.size(), saved.getChecklistKey(), saved.getNotingRef()),
                Map.of("subjectRef", saved.getSubjectRef(), "notingRef", saved.getNotingRef(),
                        "checklistKey", saved.getChecklistKey(), "items", items.size()));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<SrmReview> list(String subjectRef) {
        if (subjectRef != null && !subjectRef.isBlank()) {
            return reviews.findBySubjectRefOrderByCreatedAtDesc(subjectRef);
        }
        return reviews.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public SrmReview get(Long id) {
        return reviews.findById(id).orElseThrow(() -> ApiException.notFound("No SRM review: " + id));
    }

    // ---- checklist ----

    /** Marks (or unmarks) a materialised checklist item — a named human action. */
    @Transactional
    @SuppressWarnings("unchecked")
    public SrmReview markItem(Long id, String code, boolean done, String actor) {
        SrmReview r = get(id);
        Map<String, Object> checklist = r.getChecklist() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(r.getChecklist());
        List<Map<String, Object>> items = (List<Map<String, Object>>) checklist.getOrDefault("items", new ArrayList<>());
        boolean found = false;
        for (Map<String, Object> item : items) {
            if (code.equalsIgnoreCase(String.valueOf(item.get("code")))) {
                item.put("done", done);
                found = true;
            }
        }
        if (!found) {
            throw ApiException.notFound("No checklist item '" + code + "' on SRM " + r.getSrmRef());
        }
        checklist.put("items", items);
        r.setChecklist(checklist);
        SrmReview saved = reviews.save(r);
        audit.human(actor, "SRM_CHECKLIST_MARKED", "SrmReview", saved.getSrmRef(),
                "Checklist item %s -> %s".formatted(code, done ? "done" : "open"),
                Map.of("code", code, "done", done));
        return saved;
    }

    // ---- noting delegation ----

    /** Submits the linked SRM_RENEWAL noting for approval (delegated to the Noting engine). */
    @Transactional
    public SrmReview submitNoting(Long id, String actor) {
        SrmReview r = get(id);
        if (r.getNotingRef() == null) {
            throw ApiException.conflict("SRM " + r.getSrmRef() + " has no linked noting to submit");
        }
        Noting n = notings.get(r.getNotingRef());
        if (n.getStatus() == Noting.Status.DRAFT) {
            n = notings.submit(r.getNotingRef(), actor);   // NotingService owns the transition + SoD/routing
        }
        r.setNotingStatus(n.getStatus().name());
        if (r.getStatus() == SrmReview.Status.OPEN) {
            r.setStatus(SrmReview.Status.NOTING_RAISED);
        }
        SrmReview saved = reviews.save(r);
        audit.human(actor, "SRM_NOTING_SUBMITTED", "SrmReview", saved.getSrmRef(),
                "SRM renewal noting %s submitted (%s)".formatted(saved.getNotingRef(), n.getStatus()),
                Map.of("notingRef", saved.getNotingRef(), "notingStatus", n.getStatus().name()));
        return saved;
    }

    /**
     * Observes the linked noting's status. When it has reached AUTHORIZED (and the SRM
     * has not already completed), fires the MER hook to advance the subject's next
     * review / renewal due date, then marks the SRM COMPLETED. Idempotent — a second
     * refresh after completion is a no-op for the hook.
     */
    @Transactional
    public SrmReview refresh(Long id, String actor) {
        SrmReview r = get(id);
        if (r.getNotingRef() == null) {
            return r;
        }
        Noting n;
        try {
            n = notings.get(r.getNotingRef());
        } catch (ApiException e) {
            log.warn("SRM {} linked noting {} not found on refresh ({})", r.getSrmRef(), r.getNotingRef(), e.getMessage());
            return r;
        }
        r.setNotingStatus(n.getStatus().name());
        if (n.getStatus() == Noting.Status.AUTHORIZED && r.getStatus() != SrmReview.Status.COMPLETED) {
            List<MerItem> advanced = mer.advanceReviewForSrm(r.getSubjectRef(), r.getNotingRef(), actor);
            if (!advanced.isEmpty()) {
                r.setRenewalDueDate(advanced.get(0).getDueDate().toString());
            }
            r.setStatus(SrmReview.Status.COMPLETED);
            audit.human(actor, "SRM_COMPLETED", "SrmReview", r.getSrmRef(),
                    "SRM renewal %s AUTHORIZED — advanced %d MER review item(s) for %s".formatted(
                            r.getNotingRef(), advanced.size(), r.getSubjectRef()),
                    Map.of("notingRef", r.getNotingRef(), "advanced", advanced.size(),
                            "subjectRef", r.getSubjectRef()));
        }
        return reviews.save(r);
    }

    // ---- helpers ----

    private MasterRecordDto pickChecklistMaster(String requestedKey) {
        List<MasterRecordDto> masters = upstream.masters("SRM_CHECKLIST");
        if (masters.isEmpty()) {
            return null;
        }
        if (requestedKey != null && !requestedKey.isBlank()) {
            for (MasterRecordDto m : masters) {
                if (requestedKey.equalsIgnoreCase(m.recordKey())) {
                    return m;
                }
            }
        }
        return masters.get(0);
    }

    /** Reads checklist item labels from a master payload: a list of strings, or of {label|description}. */
    @SuppressWarnings("unchecked")
    private List<String> coerceItems(Map<String, Object> payload) {
        if (payload == null) {
            return DEFAULT_ITEMS;
        }
        Object raw = payload.get("items");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return DEFAULT_ITEMS;
        }
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof String s && !s.isBlank()) {
                out.add(s);
            } else if (o instanceof Map<?, ?> m) {
                Object label = m.get("label");
                if (label == null) label = m.get("description");
                if (label != null && !String.valueOf(label).isBlank()) {
                    out.add(String.valueOf(label));
                }
            }
        }
        return out.isEmpty() ? DEFAULT_ITEMS : out;
    }

    private String generateRef() {
        for (int i = 0; i < 8; i++) {
            String ref = "SRM-" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
            if (!reviews.existsBySrmRef(ref)) {
                return ref;
            }
        }
        return "SRM-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }
}
