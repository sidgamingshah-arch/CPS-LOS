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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * A document-execution package (CLoM R1-14 / F73-F74): the tracking envelope for the
 * signing / receipt of a set of generated documents on a deal. It carries no document
 * content of its own — each {@link DocumentExecution} child points to an existing
 * {@link GeneratedDocument} by reference and tracks only its <b>execution status</b>
 * (SENT / SIGNED / RECEIVED) plus a per-document signatory matrix. The source
 * GeneratedDocument (and its confirm-lock) is never touched — execution tracks status
 * only, so the authoritative document stays byte-identical.
 *
 * <p>The package status auto-derives from its children (every document closed — RECEIVED,
 * or waived — advances the package to COMPLETED).</p>
 */
@Entity
@Table(name = "execution_packages", indexes = {
        @Index(name = "uq_exec_ref", columnList = "execRef", unique = true),
        @Index(name = "idx_exec_subject", columnList = "subjectRef"),
        @Index(name = "idx_exec_status", columnList = "status")
})
@Getter
@Setter
public class ExecutionPackage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Human-readable reference, generated {@code EXE-XXXXXX}. */
    @Column(nullable = false, length = 40)
    private String execRef;

    /** The deal / application (or other subject) the documents are executed for. */
    @Column(nullable = false, length = 60)
    private String subjectRef;

    @Column(nullable = false, length = 20)
    private String status;            // OPEN | IN_PROGRESS | COMPLETED

    @Column(length = 120)
    private String createdBy;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
