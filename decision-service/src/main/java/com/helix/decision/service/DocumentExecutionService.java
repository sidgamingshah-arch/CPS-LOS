package com.helix.decision.service;

import com.helix.common.audit.AuditService;
import com.helix.common.web.ApiException;
import com.helix.decision.dto.ExecutionDtos.AddSignatoryRequest;
import com.helix.decision.dto.ExecutionDtos.CreatePackageRequest;
import com.helix.decision.dto.ExecutionDtos.DocRef;
import com.helix.decision.dto.ExecutionDtos.DocumentView;
import com.helix.decision.dto.ExecutionDtos.PackageView;
import com.helix.decision.entity.DocumentExecution;
import com.helix.decision.entity.ExecutionPackage;
import com.helix.decision.entity.GeneratedDocument;
import com.helix.decision.entity.Signatory;
import com.helix.decision.repo.DocumentExecutionRepository;
import com.helix.decision.repo.ExecutionPackageRepository;
import com.helix.decision.repo.GeneratedDocumentRepository;
import com.helix.decision.repo.SignatoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Document Execution Workflow + Signatory Matrix (CLoM R1-14 / F73-F74).
 *
 * <p>Tracks the execution (signing / receipt) of a set of {@link GeneratedDocument}s on a
 * deal. An {@link ExecutionPackage} enrols one {@link DocumentExecution} per document (by
 * reference only) and tracks its lifecycle — PENDING → SENT (stamps a facade e-sign
 * envelope id) → SIGNED → RECEIVED — plus a per-document {@link Signatory} matrix with
 * INTERNAL / CUSTOMER sides. Documents can also be tagged deferred or waived.</p>
 *
 * <p><b>Invariant:</b> the source GeneratedDocument (content + confirm-lock) is never
 * touched here — execution tracks status only, so the authoritative document stays
 * byte-identical. The e2e asserts this directly.</p>
 *
 * <p>The package status auto-derives from its children: every document closed (RECEIVED,
 * or waived) → COMPLETED; any activity short of that → IN_PROGRESS; otherwise OPEN.</p>
 */
@Service
public class DocumentExecutionService {

    private static final Set<String> DOC_STATUSES = Set.of("PENDING", "SENT", "SIGNED", "RECEIVED");
    private static final Set<String> SIDES = Set.of("INTERNAL", "CUSTOMER");

    private final ExecutionPackageRepository packages;
    private final DocumentExecutionRepository documents;
    private final SignatoryRepository signatories;
    private final GeneratedDocumentRepository generatedDocs;
    private final AuditService audit;

    public DocumentExecutionService(ExecutionPackageRepository packages, DocumentExecutionRepository documents,
                                    SignatoryRepository signatories, GeneratedDocumentRepository generatedDocs,
                                    AuditService audit) {
        this.packages = packages;
        this.documents = documents;
        this.signatories = signatories;
        this.generatedDocs = generatedDocs;
        this.audit = audit;
    }

    // =============================================================== create + read

    @Transactional
    public PackageView createPackage(CreatePackageRequest req, String actor) {
        if (req.documents() == null || req.documents().isEmpty()) {
            throw ApiException.badRequest("At least one document is required to open an execution package");
        }
        ExecutionPackage p = new ExecutionPackage();
        p.setExecRef(newRef());
        p.setSubjectRef(req.subjectRef().trim());
        p.setStatus("OPEN");
        p.setCreatedBy(actor);
        ExecutionPackage saved = packages.save(p);

        for (DocRef dr : req.documents()) {
            if (dr == null || dr.docRef() == null || dr.docRef().isBlank()) {
                throw ApiException.badRequest("Each document requires a docRef (GeneratedDocument id / reference)");
            }
            DocumentExecution d = new DocumentExecution();
            d.setExecRef(saved.getExecRef());
            d.setDocRef(dr.docRef().trim());
            d.setDocumentTitle(resolveTitle(dr));
            d.setStatus("PENDING");
            documents.save(d);
        }
        audit.human(actor, "EXECUTION_PACKAGE_CREATED", "ExecutionPackage", saved.getExecRef(),
                "Opened execution package for %s with %d document(s)".formatted(
                        saved.getSubjectRef(), req.documents().size()),
                Map.of("subjectRef", saved.getSubjectRef(), "documents", req.documents().size()));
        return view(saved.getExecRef());
    }

    @Transactional(readOnly = true)
    public List<ExecutionPackage> list(String subjectRef) {
        if (subjectRef != null && !subjectRef.isBlank()) {
            return packages.findBySubjectRefOrderByIdDesc(subjectRef.trim());
        }
        return packages.findAllByOrderByIdDesc();
    }

    @Transactional(readOnly = true)
    public PackageView view(String execRef) {
        ExecutionPackage p = require(execRef);
        List<DocumentView> docViews = new ArrayList<>();
        for (DocumentExecution d : documents.findByExecRefOrderByIdAsc(p.getExecRef())) {
            docViews.add(new DocumentView(d, signatories.findByDocumentIdOrderByIdAsc(d.getId())));
        }
        return new PackageView(p, docViews);
    }

    // =============================================================== signatory matrix

    @Transactional
    public PackageView addSignatory(String execRef, Long docId, AddSignatoryRequest req, String actor) {
        require(execRef);
        DocumentExecution d = requireDoc(execRef, docId);
        String side = req.side() == null ? "" : req.side().trim().toUpperCase();
        if (!SIDES.contains(side)) {
            throw ApiException.badRequest("side must be INTERNAL or CUSTOMER (was '" + req.side() + "')");
        }
        Signatory s = new Signatory();
        s.setDocumentId(d.getId());
        s.setSignatoryName(req.name().trim());
        s.setSignatoryRole(req.role() == null || req.role().isBlank() ? null : req.role().trim());
        s.setSide(side);
        s.setStatus("PENDING");
        signatories.save(s);
        audit.human(actor, "EXECUTION_SIGNATORY_ADDED", "ExecutionPackage", execRef,
                "Added %s signatory '%s' to document %d".formatted(side, s.getSignatoryName(), docId),
                Map.of("docRef", d.getDocRef(), "side", side, "signatory", s.getSignatoryName()));
        return view(execRef);
    }

    @Transactional
    public PackageView sign(String execRef, Long docId, Long sigId, String actor) {
        ExecutionPackage p = require(execRef);
        DocumentExecution d = requireDoc(execRef, docId);
        Signatory s = signatories.findById(sigId)
                .orElseThrow(() -> ApiException.notFound("No signatory " + sigId));
        if (!s.getDocumentId().equals(d.getId())) {
            throw ApiException.badRequest("Signatory " + sigId + " is not on document " + docId);
        }
        if ("SIGNED".equals(s.getStatus())) {
            throw ApiException.conflict("Signatory '" + s.getSignatoryName() + "' has already signed");
        }
        s.setStatus("SIGNED");
        s.setSignedAt(Instant.now());
        signatories.save(s);
        audit.human(actor, "EXECUTION_SIGNATORY_SIGNED", "ExecutionPackage", execRef,
                "%s signatory '%s' signed document %d".formatted(s.getSide(), s.getSignatoryName(), docId),
                Map.of("docRef", d.getDocRef(), "signatory", s.getSignatoryName(), "side", s.getSide()));

        // Auto-advance the document to SIGNED once every signatory has signed.
        List<Signatory> all = signatories.findByDocumentIdOrderByIdAsc(d.getId());
        boolean allSigned = !all.isEmpty() && all.stream().allMatch(x -> "SIGNED".equals(x.getStatus()));
        if (allSigned && ("PENDING".equals(d.getStatus()) || "SENT".equals(d.getStatus()))) {
            d.setStatus("SIGNED");
            documents.save(d);
            audit.human(actor, "EXECUTION_DOCUMENT_STATUS", "ExecutionPackage", execRef,
                    "Document %d auto-advanced to SIGNED (all signatories signed)".formatted(docId),
                    Map.of("docRef", d.getDocRef(), "status", "SIGNED"));
        }
        // Signing is operational activity — move a still-OPEN package to IN_PROGRESS.
        if ("OPEN".equals(p.getStatus())) {
            p.setStatus("IN_PROGRESS");
            packages.save(p);
        }
        recompute(p);
        return view(execRef);
    }

    // =============================================================== document lifecycle

    @Transactional
    public PackageView setStatus(String execRef, Long docId, String status, String actor) {
        ExecutionPackage p = require(execRef);
        DocumentExecution d = requireDoc(execRef, docId);
        String target = status == null ? "" : status.trim().toUpperCase();
        if (!DOC_STATUSES.contains(target)) {
            throw ApiException.badRequest("status must be one of " + DOC_STATUSES + " (was '" + status + "')");
        }
        d.setStatus(target);
        // The e-sign integration is a facade: SENT stamps an envelope id (no external call).
        if ("SENT".equals(target) && (d.getEsignEnvelopeId() == null || d.getEsignEnvelopeId().isBlank())) {
            d.setEsignEnvelopeId(newEnvelopeId());
        }
        documents.save(d);
        audit.human(actor, "EXECUTION_DOCUMENT_STATUS", "ExecutionPackage", execRef,
                "Document %d set to %s%s".formatted(docId, target,
                        "SENT".equals(target) ? " (envelope " + d.getEsignEnvelopeId() + ")" : ""),
                Map.of("docRef", d.getDocRef(), "status", target,
                        "esignEnvelopeId", d.getEsignEnvelopeId() == null ? "" : d.getEsignEnvelopeId()));
        recompute(p);
        return view(execRef);
    }

    @Transactional
    public PackageView defer(String execRef, Long docId, String deferralTag, String actor) {
        ExecutionPackage p = require(execRef);
        DocumentExecution d = requireDoc(execRef, docId);
        d.setDeferralTag(deferralTag == null || deferralTag.isBlank() ? null : deferralTag.trim());
        documents.save(d);
        audit.human(actor, "EXECUTION_DOCUMENT_DEFERRED", "ExecutionPackage", execRef,
                "Document %d tagged deferred: %s".formatted(docId, d.getDeferralTag()),
                Map.of("docRef", d.getDocRef(), "deferralTag", d.getDeferralTag() == null ? "" : d.getDeferralTag()));
        recompute(p);
        return view(execRef);
    }

    @Transactional
    public PackageView waive(String execRef, Long docId, String waiverTag, String actor) {
        ExecutionPackage p = require(execRef);
        DocumentExecution d = requireDoc(execRef, docId);
        d.setWaiverTag(waiverTag == null || waiverTag.isBlank() ? null : waiverTag.trim());
        documents.save(d);
        audit.human(actor, "EXECUTION_DOCUMENT_WAIVED", "ExecutionPackage", execRef,
                "Document %d tagged waived: %s".formatted(docId, d.getWaiverTag()),
                Map.of("docRef", d.getDocRef(), "waiverTag", d.getWaiverTag() == null ? "" : d.getWaiverTag()));
        recompute(p);
        return view(execRef);
    }

    // =============================================================== internals

    /**
     * Derives the package status from its documents. A document is "closed" when it is
     * RECEIVED or waived; when every document is closed the package is COMPLETED. Any
     * activity short of that (a non-PENDING status, a deferral or waiver tag) → IN_PROGRESS.
     */
    private void recompute(ExecutionPackage p) {
        List<DocumentExecution> docs = documents.findByExecRefOrderByIdAsc(p.getExecRef());
        String next;
        if (docs.isEmpty()) {
            next = "OPEN";
        } else {
            boolean allClosed = docs.stream().allMatch(this::isClosed);
            boolean anyActivity = docs.stream().anyMatch(d ->
                    !"PENDING".equals(d.getStatus()) || d.getDeferralTag() != null || d.getWaiverTag() != null);
            next = allClosed ? "COMPLETED" : (anyActivity ? "IN_PROGRESS" : "OPEN");
        }
        if (!next.equals(p.getStatus())) {
            p.setStatus(next);
            packages.save(p);
            if ("COMPLETED".equals(next)) {
                audit.human(p.getCreatedBy() == null ? "system" : p.getCreatedBy(),
                        "EXECUTION_PACKAGE_COMPLETED", "ExecutionPackage", p.getExecRef(),
                        "All documents received / waived — package COMPLETED",
                        Map.of("documents", docs.size()));
            }
        }
    }

    private boolean isClosed(DocumentExecution d) {
        return "RECEIVED".equals(d.getStatus()) || d.getWaiverTag() != null;
    }

    /**
     * Resolves the document title. If the request supplies one it is used verbatim; otherwise
     * the title is read (read-only) from the source GeneratedDocument when {@code docRef} is a
     * numeric id. The GeneratedDocument is never mutated.
     */
    private String resolveTitle(DocRef dr) {
        if (dr.title() != null && !dr.title().isBlank()) {
            return dr.title().trim();
        }
        try {
            long genId = Long.parseLong(dr.docRef().trim());
            GeneratedDocument gd = generatedDocs.findById(genId).orElse(null);
            if (gd != null && gd.getTitle() != null && !gd.getTitle().isBlank()) {
                return gd.getTitle();
            }
        } catch (NumberFormatException ignored) {
            // docRef is a non-numeric reference — fall through to a generic title.
        }
        return "Document " + dr.docRef().trim();
    }

    private ExecutionPackage require(String execRef) {
        return packages.findByExecRef(execRef)
                .orElseThrow(() -> ApiException.notFound("Execution package " + execRef + " not found"));
    }

    private DocumentExecution requireDoc(String execRef, Long docId) {
        DocumentExecution d = documents.findById(docId)
                .orElseThrow(() -> ApiException.notFound("No document " + docId + " on " + execRef));
        if (!execRef.equals(d.getExecRef())) {
            throw ApiException.badRequest("Document " + docId + " does not belong to package " + execRef);
        }
        return d;
    }

    private String newRef() {
        for (int i = 0; i < 8; i++) {
            String ref = "EXE-" + randomAlnum(6);
            if (!packages.existsByExecRef(ref)) {
                return ref;
            }
        }
        return "EXE-" + randomAlnum(10);
    }

    private String newEnvelopeId() {
        return "ESN-" + randomAlnum(10);
    }

    private static String randomAlnum(int n) {
        final String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        }
        return sb.toString();
    }
}
