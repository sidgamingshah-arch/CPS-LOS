package com.helix.counterparty.entity;

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

/**
 * A sanctions/PEP/adverse-media screening hit (PRD §1, US-1.2). Disposition is
 * always a named human action; high-severity hits cannot be auto-cleared.
 */
@Entity
@Table(name = "screening_hits", indexes = {
        @Index(name = "idx_hit_cp", columnList = "counterpartyId")
})
@Getter
@Setter
public class ScreeningHit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long counterpartyId;

    @Column(nullable = false, length = 40)
    private String listSource;         // OFAC | UN | EU | PEP | ADVERSE_MEDIA

    // PII (matched sanctions/PEP/adverse-media name). Free text, never used in a
    // query/dedup/join/order — safe to encrypt at rest.
    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false)
    private String matchedName;

    @Column(nullable = false)
    private double matchScore;         // 0..1

    @Column(nullable = false, length = 20)
    private String severity;           // LOW | MEDIUM | HIGH | SEVERE

    @Convert(converter = JsonAttributeConverters.StringListConverter.class)
    @Column(length = 1000)
    private List<String> matchedAttributes;

    /** AI-generated rationale citing the matched fields (decision-support only). NULL when no LLM
     *  is configured — a canned template is NOT fabricated; a named human enters {@link #humanRationale}. */
    // Sensitive free text (names the matched party); never queried — encrypted at rest.
    @Convert(converter = EncryptedStringConverter.class)
    @Lob
    @Column(length = 2000)
    private String aiRationale;

    /** Named-human-authored rationale, recorded when no model drafted one (governed: no simulated text). */
    // Sensitive free text; never queried — encrypted at rest.
    @Convert(converter = EncryptedStringConverter.class)
    @Lob
    @Column(length = 2000)
    private String humanRationale;

    /** Provenance of the rationale shown to the user: AI | HUMAN | EXTERNAL | NONE. */
    @Column(length = 20)
    private String rationaleSource = "NONE";

    @Column(nullable = false, length = 30)
    private String disposition;        // Enums.ScreeningDisposition name

    private String dispositionedBy;
    private Instant dispositionedAt;
    // KYC/disposition remark free text; never queried — encrypted at rest.
    @Convert(converter = EncryptedStringConverter.class)
    private String dispositionNote;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;
}
