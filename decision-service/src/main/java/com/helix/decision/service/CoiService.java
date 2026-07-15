package com.helix.decision.service;

import com.helix.common.audit.AuditService;
import com.helix.common.web.ApiException;
import com.helix.decision.entity.CoiAttestation;
import com.helix.decision.entity.CoiAttestation.Declaration;
import com.helix.decision.repo.CoiAttestationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Conflict-of-interest (COI) attestations. A named human records their own
 * declaration against a subject; the record is a human-accountable trail entry.
 *
 * <p>The gate ({@link #assertCleared}) is only consulted by the decision workflow
 * when the DOA_MATRIX pack opts in via {@code require_coi_attestation}. An actor is
 * <b>cleared</b> for a subject iff they hold at least one live (ATTESTED) attestation
 * for it and none of their live attestations declares {@code CONFLICTED} — so a
 * conflicted attester can never self-approve their own conflict away.</p>
 */
@Service
public class CoiService {

    private final CoiAttestationRepository repo;
    private final AuditService audit;

    public CoiService(CoiAttestationRepository repo, AuditService audit) {
        this.repo = repo;
        this.audit = audit;
    }

    @Transactional
    public CoiAttestation attest(String subjectType, String subjectRef, String role,
                                 String declaration, String note, String actor) {
        if (actor == null || actor.isBlank()) {
            throw ApiException.forbiddenAutonomy("A named human actor is required to attest");
        }
        Declaration decl;
        try {
            decl = Declaration.parse(declaration);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Unknown COI declaration: " + declaration
                    + " (expected NONE | DECLARED_MANAGED | CONFLICTED)");
        }
        CoiAttestation a = new CoiAttestation();
        a.setCoiRef(generateRef());
        a.setSubjectType(subjectType.trim());
        a.setSubjectRef(subjectRef.trim());
        a.setActor(actor);
        a.setAttesterRole(role == null || role.isBlank() ? null : role.trim());
        a.setDeclaration(decl.name());
        a.setNote(note);
        a.setStatus(CoiAttestation.Status.ATTESTED.name());
        CoiAttestation saved = repo.save(a);

        audit.human(actor, "COI_ATTESTED", subjectType, subjectRef,
                "%s declared %s COI for %s".formatted(actor, decl.name(), subjectRef),
                Map.of("coiRef", saved.getCoiRef(), "declaration", decl.name(),
                        "subjectType", subjectType, "subjectRef", subjectRef,
                        "role", saved.getAttesterRole() == null ? "" : saved.getAttesterRole()));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<CoiAttestation> listBySubject(String subjectRef) {
        return repo.findBySubjectRefOrderByIdDesc(subjectRef);
    }

    @Transactional(readOnly = true)
    public CoiAttestation get(String coiRef) {
        return repo.findByCoiRef(coiRef)
                .orElseThrow(() -> ApiException.notFound("No COI attestation: " + coiRef));
    }

    /**
     * True iff {@code actor} is cleared to decide/vote on {@code subjectRef}: they hold
     * at least one live ATTESTED attestation for the subject and none of their live
     * attestations is CONFLICTED.
     */
    @Transactional(readOnly = true)
    public boolean isCleared(String subjectRef, String actor) {
        if (actor == null || actor.isBlank()) return false;
        List<CoiAttestation> live = repo.findBySubjectRefAndStatusOrderByIdDesc(
                subjectRef, CoiAttestation.Status.ATTESTED.name());
        boolean attested = false;
        for (CoiAttestation a : live) {
            if (!actor.equalsIgnoreCase(a.getActor())) continue;
            attested = true;
            if (Declaration.CONFLICTED.name().equals(a.getDeclaration())) {
                return false;   // a recorded conflict stands — cannot self-clear
            }
        }
        return attested;
    }

    /** Gate: throws 403 (forbiddenAutonomy) unless {@code actor} is cleared for the subject. */
    @Transactional(readOnly = true)
    public void assertCleared(String subjectRef, String actor) {
        if (!isCleared(subjectRef, actor)) {
            throw ApiException.forbiddenAutonomy(
                    "Conflict-of-interest attestation required: actor '" + actor
                    + "' has no ATTESTED, non-CONFLICTED COI declaration for " + subjectRef
                    + " — a decision/vote cannot proceed until it is recorded");
        }
    }

    private String generateRef() {
        for (int i = 0; i < 8; i++) {
            String ref = "COI-" + UUID.randomUUID().toString().replace("-", "")
                    .substring(0, 6).toUpperCase();
            if (!repo.existsByCoiRef(ref)) {
                return ref;
            }
        }
        return "COI-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }
}
