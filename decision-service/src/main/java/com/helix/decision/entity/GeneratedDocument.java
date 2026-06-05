package com.helix.decision.entity;

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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A document generated from a DOC_TEMPLATE_MASTER template (PRD doc generation +
 * clause add/remove). Each {@link com.helix.decision.entity.GeneratedDocument} is an
 * <b>AI-drafted suggestion</b> until a human confirms it — and even then it's review
 * accountability, never auto-issued. Clauses can be added/removed/edited; each clause
 * carries its source (TNC_MASTER record key or "template" / "edited").
 */
@Entity
@Table(name = "generated_documents", indexes = {
        @Index(name = "idx_gendoc_app", columnList = "applicationReference"),
        @Index(name = "idx_gendoc_status", columnList = "status")
})
@Getter
@Setter
public class GeneratedDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String applicationReference;

    @Column(nullable = false, length = 60)
    private String templateKey;          // DOC_TEMPLATE_MASTER recordKey, e.g. FACILITY_AGREEMENT

    @Column(nullable = false, length = 20)
    private String format;               // DOCX | PDF | HTML

    @Column(nullable = false)
    private String title;

    @Lob
    @Column(nullable = false, length = 64000)
    private String html;

    @Convert(converter = JsonAttributeConverters.StringListConverter.class)
    @Column(length = 4000)
    private List<String> clauseOrder;    // ordered clause refs

    @Convert(converter = JsonAttributeConverters.MapConverter.class)
    @Column(length = 16000)
    private Map<String, Object> clauses; // clauseRef -> { title, text, source, addedBy?, editedBy? }

    @Convert(converter = JsonAttributeConverters.MapConverter.class)
    @Column(length = 4000)
    private Map<String, Object> variables; // borrower, amount, currency, etc.

    @Column(nullable = false, length = 16)
    private String status;               // DRAFT | CONFIRMED | ISSUED | WITHDRAWN

    @Column(nullable = false)
    private boolean advisory;

    private String generatedBy;
    private String confirmedBy;
    private Instant confirmedAt;
    private String confirmComment;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant generatedAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
