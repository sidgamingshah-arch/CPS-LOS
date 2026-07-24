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
 * One document tracked inside an {@link ExecutionPackage}. It references an existing
 * {@link GeneratedDocument} by {@code docRef} (its id / reference) and tracks only the
 * execution lifecycle — never the document content. The e-sign integration is a facade:
 * moving to SENT stamps an {@code esignEnvelopeId} string (no external call). Deferral and
 * waiver are governance tags recorded against the document for the audit trail.
 *
 * <p>The source GeneratedDocument is read-only from here — its clauses, html and
 * confirm-lock stay byte-identical.</p>
 */
@Entity
@Table(name = "document_executions", indexes = {
        @Index(name = "idx_docexec_pkg", columnList = "execRef"),
        @Index(name = "idx_docexec_doc", columnList = "docRef")
})
@Getter
@Setter
public class DocumentExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Parent {@link ExecutionPackage#getExecRef()}. */
    @Column(nullable = false, length = 40)
    private String execRef;

    /** The source {@link GeneratedDocument} id / reference — read-only pointer, never edited. */
    @Column(nullable = false, length = 60)
    private String docRef;

    @Column(nullable = false, length = 200)
    private String documentTitle;

    @Column(nullable = false, length = 20)
    private String status;            // PENDING | SENT | SIGNED | RECEIVED

    /** Facade e-sign envelope id, stamped when the document is SENT (no external call). */
    @Column(length = 60)
    private String esignEnvelopeId;

    /** Optional deferral tag — the execution of this document is deferred to a later date. */
    @Column(length = 120)
    private String deferralTag;

    /** Optional waiver tag — this document is waived (counts as closed for package completion). */
    @Column(length = 120)
    private String waiverTag;

    /** DMS {@code StoredDocument} id of the uploaded executed/received file (set on receive-with-upload). */
    @Column(length = 60)
    private String receivedDocId;

    /** Original filename of the uploaded received document. */
    @Column(length = 200)
    private String receivedFileName;

    /** When the executed document was received (uploaded). */
    private Instant receivedAt;

    /** Who recorded receipt of the executed document. */
    @Column(length = 60)
    private String receivedBy;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
