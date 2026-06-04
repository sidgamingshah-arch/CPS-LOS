package com.helix.counterparty.entity;

import com.helix.common.util.JsonAttributeConverters;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.Map;

/**
 * The status of a check performed in a source system (PRD §1 / Stage 3): internal
 * screening, external screening, credit bureau, KYC/AML, external rating. Helix
 * does not perform these — it fetches status from the source and can request a
 * refresh, which the source re-runs and hands back. One generic record covers all
 * five repeated "check is performed in source systems" features.
 */
@Entity
@Table(name = "external_checks", indexes = {
        @Index(name = "idx_extcheck_entity", columnList = "entityType,entityRef"),
        @Index(name = "idx_extcheck_cp", columnList = "counterpartyId")
})
@Getter
@Setter
public class ExternalCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long counterpartyId;

    /** The screened party — obligor, co-obligor, parent, guarantor, third party. */
    @Column(nullable = false, length = 30)
    private String entityType;          // OBLIGOR | CO_OBLIGOR | PARENT | GUARANTOR | THIRD_PARTY

    @Column(nullable = false)
    private String entityName;

    @Column(length = 40)
    private String entityRef;

    @Column(nullable = false, length = 40)
    private String checkType;           // SCREENING_INTERNAL | SCREENING_EXTERNAL | CREDIT_BUREAU | KYC_AML | EXTERNAL_RATING

    @Column(nullable = false, length = 40)
    private String sourceSystem;        // e.g. WorldCheck, FircoSoft, CIBIL, KYC-Hub, S&P

    @Column(nullable = false, length = 30)
    private String status;              // PENDING | CLEAR | HIT | ERROR | REFRESH_REQUESTED

    @Convert(converter = JsonAttributeConverters.MapConverter.class)
    @Column(length = 4000)
    private Map<String, Object> result;

    private String rmAssigned;
    private String classification;      // entity classification at the source
    private Instant lastFetchedAt;
    private Instant refreshRequestedAt;
    private String refreshRequestedBy;
}
