package com.helix.decision.entity;

import com.helix.common.util.EncryptedStringConverter;
import com.helix.common.util.JsonAttributeConverters;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * An ADVISORY AI verification of a CAD checklist item's supporting document (PRD CAD /
 * documentation-perfection). Two flavours, keyed by {@code verificationType}:
 *
 * <ul>
 *   <li>{@code SIGNATURE} — compares a claimed signatory against the on-file specimen name
 *       (deterministic token heuristic, optionally sharpened by a governed LLM overlay) and
 *       produces a MATCH / MISMATCH / INCONCLUSIVE verdict.</li>
 *   <li>{@code PROPERTY_DOC} — extracts key title/property-document fields from the document
 *       text (deterministic regex extraction, optional LLM overlay), flags missing mandatory
 *       fields, and produces a COMPLETE / INCOMPLETE / INCONCLUSIVE verdict.</li>
 * </ul>
 *
 * <p>It is <b>advisory only</b> — it NEVER moves a {@link ChecklistItem} to COMPLIED. It
 * produces a finding that a named human confirms ({@code accept}/{@code reject}); the human
 * still sets the item status through the existing CAD workflow. The AI run stamps
 * {@code audit.ai("cad-doc-ai", ...)}; the accept/reject gate stamps {@code audit.human(...)}.</p>
 */
@Entity
@Table(name = "cad_doc_verifications", indexes = {
        @Index(name = "idx_cadv_case", columnList = "cadCaseId"),
        @Index(name = "idx_cadv_item", columnList = "checklistItemId")
})
@Getter
@Setter
public class CadDocVerification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long cadCaseId;

    @Column(nullable = false)
    private Long checklistItemId;

    /** Convenience — the CAD case's application ref, so findings are auditable per deal. */
    @Column(length = 30)
    private String applicationRef;

    /** The checklist item's description at verify time (grounding for the reviewer). */
    @Column(length = 400)
    private String itemDescription;

    @Column(nullable = false, length = 30)
    private String verificationType;   // SIGNATURE | PROPERTY_DOC

    /**
     * SIGNATURE: MATCH | MISMATCH | INCONCLUSIVE.
     * PROPERTY_DOC: COMPLETE | INCOMPLETE | INCONCLUSIVE.
     */
    @Column(length = 30)
    private String verdict;

    private double confidence;

    @Column(length = 500)
    private String summary;

    /** Operational signatory reference names (SIGNATURE). Plain — reference data, not free text. */
    @Column(length = 200)
    private String claimedSignatory;
    @Column(length = 200)
    private String specimenSignatory;

    /** Human-readable reasoning for the finding. */
    @Convert(converter = JsonAttributeConverters.StringListConverter.class)
    @Column(length = 2000)
    private List<String> findings;

    /** Mandatory fields the document text did not evidence (PROPERTY_DOC). */
    @Convert(converter = JsonAttributeConverters.StringListConverter.class)
    @Column(length = 1000)
    private List<String> missingFields;

    /** Key fields extracted from the document text (PROPERTY_DOC). */
    @Convert(converter = JsonAttributeConverters.MapConverter.class)
    @Column(length = 2000)
    private Map<String, Object> extractedFields;

    /** Bounded excerpt of the analysed document text (grounding); never queried — encrypted at rest. */
    @Convert(converter = EncryptedStringConverter.class)
    @Lob
    @Column(length = 2000)
    private String sourceExcerpt;

    private boolean llmDrafted;
    private String llmModel;

    private boolean advisory = true;

    @Column(nullable = false, length = 20)
    private String status = "DRAFT";   // DRAFT | ACCEPTED | REJECTED

    private String createdBy;          // AI run initiator (X-Actor)
    private String reviewedBy;
    private Instant reviewedAt;

    /** Reviewer remark free text; never queried — encrypted at rest. */
    @Convert(converter = EncryptedStringConverter.class)
    private String reviewNote;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;
}
