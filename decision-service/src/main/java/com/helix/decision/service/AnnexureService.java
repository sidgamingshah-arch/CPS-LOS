package com.helix.decision.service;

import com.helix.common.audit.AuditService;
import com.helix.common.llm.LlmClient;
import com.helix.common.llm.LlmRequest;
import com.helix.common.llm.LlmResult;
import com.helix.common.web.ApiException;
import com.helix.decision.client.AnnexureTypeMasterClient;
import com.helix.decision.client.AnnexureTypeMasterClient.AnnexureTypeSpec;
import com.helix.decision.client.AnnexureTypeMasterClient.Section;
import com.helix.decision.client.UpstreamClient;
import com.helix.decision.client.UpstreamClient.DealEnvelopeDto;
import com.helix.decision.client.UpstreamClient.RiskSummaryDto;
import com.helix.decision.entity.Annexure;
import com.helix.decision.entity.AnnexureStatus;
import com.helix.decision.repo.AnnexureRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The CAM-annexure engine. ONE governed authoring lifecycle for every annexure type
 * (CRI sheet · industry-scenario · ESG · exchange-risk · project-deferment ·
 * group-analysis), driven by the {@code ANNEXURE_TYPE} master (config-as-data — a new
 * type is a new master row, not a code change). Modelled on the monitoring-artifact
 * engine.
 *
 * <p><b>Governance</b>: every write stamps an audit event. The optional AI section draft
 * runs at the governed {@link LlmClient} boundary and is stamped {@code audit.ai}; it is
 * grounded, quotes figures verbatim, invents nothing, and remains an unconfirmed DRAFT.
 * The review / approve gates are name-equality SoD'd (reviewer ≠ author, approver ≠
 * author) — a violation is a {@code forbiddenAutonomy} 403. A reject requires a reason.</p>
 *
 * <p><b>Advisory invariant</b>: an annexure is an authoring artefact — it makes no write
 * call to origination / risk / limits and NEVER mutates an authoritative figure. The
 * subject application's grade / PD / spread live upstream and are byte-identical across
 * the whole annexure lifecycle.</p>
 */
@Service
public class AnnexureService {

    private static final Logger log = LoggerFactory.getLogger(AnnexureService.class);
    private static final String SUBJECT = "Annexure";

    private final AnnexureRepository repo;
    private final AnnexureTypeMasterClient masters;
    private final UpstreamClient upstream;
    private final AuditService audit;
    private final LlmClient llm;

    public AnnexureService(AnnexureRepository repo, AnnexureTypeMasterClient masters,
                           UpstreamClient upstream, AuditService audit, LlmClient llm) {
        this.repo = repo;
        this.masters = masters;
        this.upstream = upstream;
        this.audit = audit;
        this.llm = llm;
    }

    @Transactional
    public Annexure create(String annexureType, String subjectType, String subjectRef,
                           String title, String author) {
        if (annexureType == null || annexureType.isBlank()) {
            throw ApiException.badRequest("annexureType is required (an ANNEXURE_TYPE master key)");
        }
        if (author == null || author.isBlank()) {
            throw ApiException.badRequest("author (X-Actor) is required");
        }
        AnnexureTypeSpec spec = masters.annexureType(annexureType.trim());

        Annexure a = new Annexure();
        a.setAnnexureRef(uniqueRef());
        a.setAnnexureType(annexureType.trim());
        a.setSubjectType(subjectType == null || subjectType.isBlank() ? "APPLICATION" : subjectType.trim());
        a.setSubjectRef(subjectRef == null ? null : subjectRef.trim());
        a.setTitle(title == null || title.isBlank() ? annexureType.trim() : title.trim());
        a.setSections(materialiseSections(spec.sections()));
        a.setTypeVersion(spec.version());
        a.setAdvisory(true);
        a.setStatus(AnnexureStatus.DRAFT);
        a.setAuthor(author);
        Annexure saved = repo.save(a);

        audit.human(author, "ANNEXURE_CREATED", SUBJECT, saved.getAnnexureRef(),
                "Created %s annexure '%s' on %s (master v%d)".formatted(saved.getAnnexureType(),
                        saved.getTitle(), safe(subjectRef), spec.version()),
                Map.of("annexureType", saved.getAnnexureType(), "subjectRef", safe(subjectRef),
                        "typeVersion", spec.version()));
        return saved;
    }

    /**
     * Author edits to the sections. Explicit author content in {@code sectionUpdates} is applied
     * first (and always wins); when {@code aiDraft} is true the governed LLM boundary drafts prose
     * for any still-empty section (advisory, grounded in the deal envelope + authoritative rating,
     * quoting figures verbatim). Only the author may edit; sections lock after submission.
     */
    @Transactional
    public Annexure updateSections(String ref, Map<String, Object> sectionUpdates, boolean aiDraft,
                                   String hint, String actor) {
        Annexure a = require(ref);
        requireAuthor(a, actor, "edit sections");
        if (a.getStatus() != AnnexureStatus.DRAFT) {
            throw ApiException.conflict("Annexure " + ref + " is " + a.getStatus() + " — sections are locked");
        }
        Map<String, Object> merged = a.getSections() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(a.getSections());

        // 1) explicit author edits (human accountable) — always win over any AI draft.
        boolean humanEdited = false;
        if (sectionUpdates != null && !sectionUpdates.isEmpty()) {
            merged.putAll(sectionUpdates);
            humanEdited = true;
        }

        // 2) optional advisory AI draft for still-empty sections, at the governed LLM boundary.
        List<String> aiSections = new ArrayList<>();
        if (aiDraft) {
            aiSections = draftEmptySections(a, merged, hint);
        }

        a.setSections(merged);
        Annexure saved = repo.save(a);

        if (humanEdited) {
            audit.human(actor, "ANNEXURE_EDITED", SUBJECT, ref, "Sections edited on " + ref,
                    Map.of("sections", merged.keySet()));
        }
        if (!aiSections.isEmpty()) {
            audit.ai("cam-annexure", "ANNEXURE_AI_DRAFTED", SUBJECT, ref,
                    "AI drafted %d annexure section(s) (advisory, author-reviewed before submit)"
                            .formatted(aiSections.size()),
                    Map.of("sections", aiSections, "advisory", true));
        }
        return saved;
    }

    @Transactional
    public Annexure submit(String ref, String actor) {
        Annexure a = require(ref);
        requireAuthor(a, actor, "submit");
        if (a.getStatus() != AnnexureStatus.DRAFT) {
            throw ApiException.conflict("Only a DRAFT can be submitted; " + ref + " is " + a.getStatus());
        }
        a.setStatus(AnnexureStatus.SUBMITTED);
        a.setSubmittedAt(Instant.now());
        Annexure saved = repo.save(a);
        audit.human(actor, "ANNEXURE_SUBMITTED", SUBJECT, ref, "Annexure " + ref + " submitted for review",
                Map.of("annexureType", a.getAnnexureType()));
        return saved;
    }

    @Transactional
    public Annexure review(String ref, String notes, String actor) {
        Annexure a = require(ref);
        if (a.getStatus() != AnnexureStatus.SUBMITTED) {
            throw ApiException.conflict("Only a SUBMITTED annexure can be reviewed; " + ref + " is " + a.getStatus());
        }
        requireNotAuthor(a, actor, "review");
        a.setStatus(AnnexureStatus.REVIEWED);
        a.setReviewer(actor);
        a.setReviewedBy(actor);
        a.setReviewedAt(Instant.now());
        a.setReviewNotes(notes);
        Annexure saved = repo.save(a);
        audit.human(actor, "ANNEXURE_REVIEWED", SUBJECT, ref, "Annexure " + ref + " reviewed by " + actor,
                Map.of("reviewer", actor, "notes", safe(notes)));
        return saved;
    }

    @Transactional
    public Annexure approve(String ref, String notes, String actor) {
        Annexure a = require(ref);
        if (a.getStatus() != AnnexureStatus.REVIEWED) {
            throw ApiException.conflict("Only a REVIEWED annexure can be approved; " + ref + " is " + a.getStatus());
        }
        requireNotAuthor(a, actor, "approve");
        a.setStatus(AnnexureStatus.APPROVED);
        a.setApprovedBy(actor);
        a.setApprovedAt(Instant.now());
        Annexure saved = repo.save(a);
        audit.human(actor, "ANNEXURE_APPROVED", SUBJECT, ref, "Annexure " + ref + " approved by " + actor,
                Map.of("approver", actor, "notes", safe(notes)));
        return saved;
    }

    @Transactional
    public Annexure reject(String ref, String reason, String actor) {
        Annexure a = require(ref);
        if (a.getStatus() != AnnexureStatus.SUBMITTED && a.getStatus() != AnnexureStatus.REVIEWED) {
            throw ApiException.conflict(
                    "Only a SUBMITTED or REVIEWED annexure can be rejected; " + ref + " is " + a.getStatus());
        }
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("A rejection reason is mandatory");
        }
        requireNotAuthor(a, actor, "reject");
        a.setStatus(AnnexureStatus.REJECTED);
        a.setRejectedBy(actor);
        a.setRejectedAt(Instant.now());
        a.setRejectReason(reason);
        Annexure saved = repo.save(a);
        audit.human(actor, "ANNEXURE_REJECTED", SUBJECT, ref, "Annexure " + ref + " rejected by " + actor,
                Map.of("reason", reason));
        return saved;
    }

    @Transactional(readOnly = true)
    public Annexure get(String ref) {
        return require(ref);
    }

    @Transactional(readOnly = true)
    public List<Annexure> list(String subjectRef, String status, String type) {
        if (subjectRef != null && !subjectRef.isBlank()) {
            return repo.findBySubjectRefOrderByIdDesc(subjectRef.trim());
        }
        if (status != null && !status.isBlank()) {
            return repo.findByStatusOrderByIdDesc(parseStatus(status));
        }
        if (type != null && !type.isBlank()) {
            return repo.findByAnnexureTypeOrderByIdDesc(type.trim());
        }
        return repo.findAllByOrderByIdDesc();
    }

    // =============================================================== internals

    /**
     * Advisory AI draft of the still-empty sections, at the governed {@link LlmClient} boundary.
     * Grounded in the deal envelope + the authoritative rating; the model quotes figures verbatim
     * and invents nothing. Fail-soft: provider 'none' (default) / any error / no subject leaves the
     * sections untouched. Returns the keys the model actually drafted (for the audit trail).
     */
    private List<String> draftEmptySections(Annexure a, Map<String, Object> sections, String hint) {
        List<String> drafted = new ArrayList<>();
        if (!llm.enabled()) {
            return drafted;   // no external model wired — fail-soft, no upstream grounding calls
        }
        String grounding = grounding(a);
        for (Map.Entry<String, Object> e : sections.entrySet()) {
            String key = e.getKey();
            Map<String, Object> cell = asCell(e.getValue(), key);
            String content = String.valueOf(cell.getOrDefault("content", ""));
            if (content != null && !content.isBlank()) {
                continue;   // never overwrite existing (author) content
            }
            String title = String.valueOf(cell.getOrDefault("title", key));
            String draft = llmDraft(a.getAnnexureType(), title, grounding, hint);
            if (draft == null || draft.isBlank()) {
                continue;   // fail-soft — leave the section empty for the author
            }
            cell.put("content", draft);
            e.setValue(cell);
            drafted.add(key);
        }
        return drafted;
    }

    private String llmDraft(String annexureType, String sectionTitle, String grounding, String hint) {
        String system = "You are drafting an ADVISORY '" + sectionTitle + "' section of a '" + annexureType
                + "' annexure to a wholesale-credit appraisal memorandum. Write grounded, professional prose "
                + "using ONLY the facts provided. Never invent or alter any figure, ratio, grade, PD or amount — "
                + "quote them exactly as given. This draft is advisory and will be reviewed and approved by named "
                + "humans before use. Reply with 2-4 sentences.";
        StringBuilder user = new StringBuilder();
        user.append("Grounded facts (JSON, source of truth): ").append(grounding).append('\n');
        user.append("Section to draft: ").append(sectionTitle);
        if (hint != null && !hint.isBlank()) {
            user.append("\nAuthor hint: ").append(hint);
        }
        LlmResult r = safeComplete(LlmRequest.of("annexure", system, user.toString()));
        return r.usable() ? r.text().strip() : null;
    }

    private LlmResult safeComplete(LlmRequest req) {
        try {
            LlmResult r = llm.complete(req);
            return r == null ? LlmResult.notConfigured() : r;
        } catch (Exception e) {
            return LlmResult.failed(e.getMessage());
        }
    }

    /** Compact, deterministically-assembled grounding for the AI drafter (best-effort, fail-soft). */
    private String grounding(Annexure a) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("annexureType", a.getAnnexureType());
        facts.put("subjectRef", safe(a.getSubjectRef()));
        String ref = a.getSubjectRef();
        if (ref != null && !ref.isBlank()) {
            try {
                DealEnvelopeDto env = upstream.envelopeOrNull(ref);
                if (env != null) {
                    facts.put("counterparty", env.counterpartyName());
                    facts.put("jurisdiction", env.jurisdiction());
                    facts.put("segment", env.segment());
                    facts.put("currency", env.currency());
                    facts.put("totalProposedAmount", env.totalProposedAmount());
                    if (env.ratios() != null && !env.ratios().isEmpty()) facts.put("ratios", env.ratios());
                }
                RiskSummaryDto rs = upstream.riskSummaryOrNull(ref);
                if (rs != null && rs.rating() != null) {
                    facts.put("grade", rs.rating().finalGrade());
                    facts.put("pd", rs.rating().pd());
                }
            } catch (Exception e) {
                log.warn("annexure grounding lookup failed for {} ({})", ref, e.getMessage());
            }
        }
        return facts.toString();
    }

    private Annexure require(String ref) {
        return repo.findByAnnexureRef(ref)
                .orElseThrow(() -> ApiException.notFound("Annexure " + ref + " not found"));
    }

    private void requireAuthor(Annexure a, String actor, String what) {
        if (actor == null || !actor.equalsIgnoreCase(a.getAuthor())) {
            throw ApiException.forbiddenAutonomy(
                    "Only the author ('" + a.getAuthor() + "') may " + what + " annexure " + a.getAnnexureRef());
        }
    }

    /** SoD gate: a reviewer / approver / rejecter must differ from the author. */
    private void requireNotAuthor(Annexure a, String actor, String what) {
        if (actor == null || actor.isBlank()) {
            throw ApiException.forbiddenAutonomy("A named human actor is required to " + what + " an annexure");
        }
        if (actor.equalsIgnoreCase(a.getAuthor())) {
            throw ApiException.forbiddenAutonomy(
                    "The actor who acts to " + what + " must differ from the author ('" + a.getAuthor()
                            + "') — segregation of duties");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asCell(Object value, String key) {
        Map<String, Object> cell = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> m) {
            cell.putAll((Map<String, Object>) m);
        }
        cell.putIfAbsent("title", key);
        cell.putIfAbsent("content", "");
        return cell;
    }

    private static Map<String, Object> materialiseSections(List<Section> template) {
        Map<String, Object> sections = new LinkedHashMap<>();
        for (Section s : template) {
            Map<String, Object> cell = new LinkedHashMap<>();
            cell.put("title", s.title());
            cell.put("content", "");
            sections.put(s.key(), cell);
        }
        return sections;
    }

    private static AnnexureStatus parseStatus(String status) {
        try {
            return AnnexureStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Unknown status '" + status + "'");
        }
    }

    private String uniqueRef() {
        for (int i = 0; i < 8; i++) {
            String ref = newRef();
            if (!repo.existsByAnnexureRef(ref)) {
                return ref;
            }
        }
        return newRef();
    }

    private static String newRef() {
        final String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder("ANX-");
        for (int i = 0; i < 6; i++) {
            sb.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
