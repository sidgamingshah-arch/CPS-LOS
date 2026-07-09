package com.helix.common.ingest;

import com.helix.common.ingest.Ingestion.Provenance;

import java.time.LocalDate;
import java.util.List;

/**
 * Canonical, jurisdiction-agnostic shapes that external source data is mapped onto
 * (PRD §7/§8). These are the contracts a connector must produce; each carries
 * provenance so every downstream figure traces to its source and version.
 */
public final class Canonical {

    private Canonical() {
    }

    /** Credit bureau pull (CIBIL / Experian / Equifax …). */
    public record BureauReport(String subjectName, String identifier, Integer creditScore, String scoreModel,
                               int inquiriesLast6m, int delinquenciesLast24m, int openTradelines,
                               double totalOutstanding, Integer oldestAccountMonths, Provenance provenance) {
    }

    /** Corporate registry record (MCA / UAE registry). */
    public record RegistryRecord(String legalName, String registrationNo, String legalForm,
                                 LocalDate incorporationDate, String status, String registeredAddress,
                                 String country, List<Director> directors, List<OwnershipEdge> ownership,
                                 Provenance provenance) {
        public record Director(String name, String role) {
        }

        /** Declared ownership edge — parent owns child by pct (0..1). */
        public record OwnershipEdge(String parentKey, String parentName, String childKey,
                                    double ownershipPct, String holderType) {
        }
    }

    /** GST / tax filing summary. */
    public record GstRecord(String gstin, double annualTurnover, String filingStatus,
                            String lastReturnPeriod, Provenance provenance) {
    }

    /** Sanctions / PEP / adverse-media screening result. */
    public record ScreeningResult(String subjectName, List<Hit> hits, Provenance provenance) {
        public record Hit(String listSource, String matchedName, double matchScore, String severity,
                          List<String> matchedAttributes) {
        }
    }

    /** Core-banking facility position & conduct (booking round-trip + servicing). */
    public record CoreBankingPosition(String facilityRef, double limit, double drawn, double undrawn,
                                      String currency, int daysPastDue, Double conductScore, String status,
                                      Provenance provenance) {
    }

    /** A repayment event from the servicing system (principal/interest split, value-dated). */
    public record RepaymentEvent(String facilityRef, double amount, double principalComponent,
                                 double interestComponent, String currency, LocalDate valueDate,
                                 String externalRef, Provenance provenance) {
    }

    /** A single market-data observation (for SA-CCR/CVA inputs, peer/macro). */
    public record MarketDataPoint(String instrument, LocalDate asOf, double value, String unit,
                                  Provenance provenance) {
    }
}
