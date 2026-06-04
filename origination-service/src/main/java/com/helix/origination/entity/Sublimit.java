package com.helix.origination.entity;

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
 * A sublimit within a facility (e.g. a working-capital facility broken into
 * CC + LC + BG + PCFC). Two structural roles:
 *
 *  - <b>Hard sublimit</b> — {@code interchangeableGroup == null}: utilisation
 *    is capped strictly at {@code amount}.
 *  - <b>Fungible sublimit</b> — {@code interchangeableGroup != null}: members
 *    of the same group share a pool whose combined cap is the sum of their
 *    {@code amount} values; utilisation may move freely within the group.
 *
 * Validation (enforced by {@code OriginationService}): the sum of all sublimits
 * under a facility must not exceed the parent facility amount.
 */
@Entity
@Table(name = "sublimits", indexes = {
        @Index(name = "idx_sublimit_facility", columnList = "facilityId"),
        @Index(name = "idx_sublimit_group", columnList = "facilityId,interchangeableGroup")
})
@Getter
@Setter
public class Sublimit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long facilityId;

    @Column(nullable = false, length = 40)
    private String code;                  // CC, LC_INLAND, LC_FOREIGN, BG_PERFORMANCE, PCFC, …

    @Column(nullable = false, length = 40)
    private String productType;           // CASH_CREDIT, LETTER_OF_CREDIT, BANK_GUARANTEE, …

    @Column(nullable = false)
    private double amount;

    @Column(nullable = false, length = 5)
    private String currency;

    private Integer tenorMonths;          // optional override of the parent

    private String purpose;

    /** Members of the same group share their combined cap (interchangeable). */
    @Column(length = 40)
    private String interchangeableGroup;

    @Column(nullable = false)
    private int ordinal;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    public boolean isFungible() {
        return interchangeableGroup != null && !interchangeableGroup.isBlank();
    }
}
