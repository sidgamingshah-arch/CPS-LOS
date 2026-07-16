package com.helix.origination.entity;

import com.helix.common.util.JsonAttributeConverters;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import java.util.Map;

/**
 * An <b>In-Principle (IP) note</b> — a lightweight sponsorship record raised by the RM
 * <i>before</i> a full loan application. It captures the prospect / obligor, a prospect
 * summary and the proposed deal structure, then routes for a named-human credit
 * sign-off. Once APPROVED it is <b>converted</b> into a real {@link LoanApplication} via
 * the existing origination application-creation path; the application then carries the
 * originating {@code ipNoteRef} and the note is stamped with the created {@code applicationRef}.
 *
 * <p>The note is authoritative <i>as a record</i> — it never mutates an authoritative
 * figure. SoD (approver ≠ raiser) and an authority-tier gate are enforced server-side.
 * Status machine: DRAFT → PENDING_APPROVAL → APPROVED → CONVERTED, with REJECTED /
 * WITHDRAWN terminals.</p>
 */
@Entity
@Table(name = "ip_notes", indexes = {
        @Index(name = "idx_ipnote_ref", columnList = "ipNoteRef", unique = true),
        @Index(name = "idx_ipnote_cp", columnList = "counterpartyRef"),
        @Index(name = "idx_ipnote_status", columnList = "status"),
        @Index(name = "idx_ipnote_app", columnList = "applicationRef")
})
@Getter
@Setter
public class IpNote {

    /** Lifecycle of an IP note. Persisted as a String (never an ordinal). */
    public enum Status {
        DRAFT, PENDING_APPROVAL, APPROVED, CONVERTED, REJECTED, WITHDRAWN
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20, unique = true)
    private String ipNoteRef;              // IPN-XXXXXX

    // ---- prospect / obligor identity ----
    @Column(nullable = false)
    private Long counterpartyId;

    @Column(nullable = false, length = 30)
    private String counterpartyRef;

    @Column(nullable = false)
    private String counterpartyName;

    // ---- proposed structure (feeds the LoanApplication on convert) ----
    @Column(nullable = false, length = 20)
    private String jurisdiction;

    @Column(nullable = false, length = 40)
    private String segment;

    @Column(nullable = false, length = 40)
    private String facilityType;

    @Column(nullable = false)
    private double proposedAmount;

    @Column(nullable = false, length = 5)
    private String currency;

    @Column(nullable = false)
    private int tenorMonths;

    private String purpose;

    /** Free-text prospect / relationship summary supporting the in-principle ask. */
    @Lob
    @Column(length = 16000)
    private String prospectSummary;

    /** Additional proposed-structure fields (collateral, sublimits, indicative pricing, …). */
    @Convert(converter = JsonAttributeConverters.MapConverter.class)
    @Column(length = 8000)
    private Map<String, Object> payload;

    @Column(nullable = false, length = 60)
    private String raisedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    /** The authority tier the approver held when signing off (nullable until approved). */
    @Column(length = 40)
    private String approverRole;

    private String decidedBy;
    private Instant decidedAt;

    @Column(length = 2000)
    private String decisionNote;

    /** The created application reference, set on convert (nullable until CONVERTED). */
    @Column(length = 30)
    private String applicationRef;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
