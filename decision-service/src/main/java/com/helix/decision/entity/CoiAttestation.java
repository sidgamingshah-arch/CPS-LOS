package com.helix.decision.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * A conflict-of-interest (COI) self-attestation by a named human against a subject
 * (an application, a committee case, …). Each attester records <b>their own</b>
 * declaration — {@code NONE} (no conflict), {@code DECLARED_MANAGED} (a conflict
 * exists and is managed/recused-where-required), or {@code CONFLICTED} (a live,
 * disqualifying conflict). A {@code CONFLICTED} attestation is <b>recorded</b> but
 * never clears the attester: they cannot self-approve their own conflict away.
 *
 * <p>Attestations are advisory records by default. They only ever <i>gate</i> a
 * decision/committee-vote when the jurisdiction's DOA_MATRIX pack opts in via the
 * {@code require_coi_attestation} key (default-off — see {@code DecisionService}).</p>
 */
@Entity
@Table(name = "coi_attestations", indexes = {
        @Index(name = "idx_coi_subject", columnList = "subjectRef"),
        @Index(name = "idx_coi_ref", columnList = "coiRef")
})
@Getter
@Setter
public class CoiAttestation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Stable public identifier — {@code COI-XXXXXX}. */
    @Column(nullable = false, length = 20, unique = true)
    private String coiRef;

    /** What the attestation is about: e.g. {@code application} or {@code committee-case}. */
    @Column(nullable = false, length = 40)
    private String subjectType;

    /** The subject's reference (e.g. the application reference the decision is on). */
    @Column(nullable = false, length = 60)
    private String subjectRef;

    /** The named human who attested — taken from X-Actor, never the request body. */
    @Column(nullable = false, length = 80)
    private String actor;

    /** The actor's claimed role at the time of attestation (advisory, recorded only). */
    @Column(length = 60)
    private String attesterRole;

    /** NONE · DECLARED_MANAGED · CONFLICTED (see {@link Declaration}). */
    @Column(nullable = false, length = 20)
    private String declaration;

    @Column(length = 600)
    private String note;

    /** ATTESTED (live) · WITHDRAWN (superseded / retracted). */
    @Column(nullable = false, length = 20)
    private String status = "ATTESTED";

    @CreationTimestamp
    @Column(name = "attested_at", updatable = false)
    private Instant at;

    /** COI declaration taxonomy. */
    public enum Declaration {
        NONE, DECLARED_MANAGED, CONFLICTED;

        public static Declaration parse(String value) {
            if (value == null) throw new IllegalArgumentException("declaration is required");
            return Declaration.valueOf(value.trim().toUpperCase());
        }
    }

    public enum Status { ATTESTED, WITHDRAWN }
}
