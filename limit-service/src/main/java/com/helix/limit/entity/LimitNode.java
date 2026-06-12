package com.helix.limit.entity;

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

import com.helix.common.money.Money;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A node in the limit tree. The root is the obligor (or group) limit; children are
 * facilities and then sub-limits (up to 5 levels). Fungibility is defined per node:
 * a fungible parent lets utilisation move freely among its children; a non-fungible
 * node is capped independently. Revolving vs non-revolving governs whether a release
 * replenishes availability.
 */
@Entity
@Table(name = "limit_nodes", indexes = {
        @Index(name = "idx_limit_ref", columnList = "reference", unique = true),
        @Index(name = "idx_limit_cif", columnList = "cif"),
        @Index(name = "idx_limit_root", columnList = "rootId"),
        @Index(name = "idx_limit_parent", columnList = "parentId")
})
@Getter
@Setter
public class LimitNode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String reference;        // line id

    @Column(nullable = false, length = 30)
    private String cif;              // counterparty reference / CIF

    private String applicationRef;   // originating deal, if any
    private String facilityRef;      // the upstream ProposedFacility.reference, when this node represents a facility
    private Long groupId;            // group, for group-level roll-up

    private Long parentId;           // null at root
    private Long rootId;             // self at root
    @Column(nullable = false)
    private int level;               // 0 = root … max 4

    @Column(nullable = false, length = 60)
    private String code;             // OBLIGOR, TERM_LOAN, CC, LC, BG …
    private String productType;
    private String classification;   // FUND_BASED | NON_FUND_BASED

    @Column(nullable = false)
    private boolean revolving;

    @Column(nullable = false, precision = 22, scale = 2)
    private BigDecimal sanctionedAmount = Money.ZERO;
    @Column(nullable = false, length = 5)
    private String currency;
    @Column(precision = 22, scale = 2)
    private BigDecimal baseAmount = Money.ZERO;     // sanctioned converted to platform base currency

    private Integer tenorMonths;
    private LocalDate expiryDate;
    private String seniority;        // SENIOR | SUBORDINATE
    private double facilityCover;

    @Column(nullable = false)
    private boolean fungible;
    private String interchangeableGroup;

    @Column(nullable = false, length = 20)
    private String status = "ACTIVE"; // ACTIVE | FROZEN | CLOSED

    // ---- balances (BigDecimal: scale-2 ledger, no double accumulation drift) ----
    @Column(precision = 22, scale = 2)
    private BigDecimal outstanding = Money.ZERO;     // current utilised (revolving view)
    @Column(precision = 22, scale = 2)
    private BigDecimal cumulativeDrawn = Money.ZERO; // lifetime drawn (non-revolving view)
    @Column(precision = 22, scale = 2)
    private BigDecimal reserved = Money.ZERO;

    // ---- classification for exposure norms ----
    private String segment;
    private String sector;
    private String country;

    @Column(nullable = false)
    private int ordinal;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    /**
     * Available headroom in the platform base currency (balances are stored in base
     * for cross-currency roll-up), honouring revolving vs non-revolving semantics.
     */
    public BigDecimal available() {
        BigDecimal used = revolving ? outstanding : cumulativeDrawn;
        return Money.nonNegative(Money.sub(Money.sub(baseAmount, used), reserved));
    }
}
