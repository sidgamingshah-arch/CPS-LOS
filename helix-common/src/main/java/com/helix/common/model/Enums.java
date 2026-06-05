package com.helix.common.model;

/**
 * Canonical, jurisdiction-agnostic enumerations shared across the credit lifecycle.
 * These are projections-independent reference values per PRD §7 (Canonical Model).
 */
public final class Enums {

    private Enums() {
    }

    /** Wholesale credit segments (PRD §2.2). Retail is explicitly out of scope. */
    public enum Segment {
        LARGE_CORPORATE,
        MID_CORPORATE,
        SME,
        PROJECT_FINANCE,
        TRADE_FINANCE,
        FINANCIAL_INSTITUTION
    }

    /** Facility / product type. */
    public enum FacilityType {
        TERM_LOAN,
        WORKING_CAPITAL,
        REVOLVING_CREDIT,
        TRADE_LINE,
        PROJECT_LOAN,
        GUARANTEE,
        DERIVATIVE_LINE
    }

    /** Internal master rating scale (obligor + facility). Maps to PD bands. */
    public enum RatingGrade {
        AAA, AA, A, BBB, BB, B, CCC, CC, C, D
    }

    /** Basel exposure classes used by the capital engine (PRD §6). */
    public enum ExposureClass {
        SOVEREIGN,
        BANK,
        CORPORATE,
        SME_CORPORATE,
        SPECIALISED_LENDING,
        RETAIL,        // present for completeness; not originated here
        EQUITY,
        OTHER
    }

    /** Lifecycle status of an application (PRD §5). */
    public enum ApplicationStatus {
        DRAFT,
        INTAKE,
        SPREADING,
        RATING,
        CAPITAL,
        PRICING,
        PENDING_APPROVAL,
        APPROVED,
        CONDITIONALLY_APPROVED,
        DECLINED,
        DISBURSED,
        WITHDRAWN
    }

    /** Human decision outcomes at the approval gate (PRD §8). AI never produces these. */
    public enum DecisionOutcome {
        APPROVE,
        CONDITIONAL_APPROVE,
        DECLINE,
        REFER
    }

    /** IFRS 9 / Ind AS 109 staging (PRD §12). */
    public enum EclStage {
        STAGE_1,   // 12-month ECL
        STAGE_2,   // lifetime ECL, not credit-impaired (SICR)
        STAGE_3    // lifetime ECL, credit-impaired
    }

    /** RBI IRAC asset classification (parallel provisioning view, PRD §12). */
    public enum IracClass {
        STANDARD,
        SUB_STANDARD,
        DOUBTFUL,
        LOSS
    }

    /**
     * AI autonomy levels per PRD §5/§6.
     * AUTONOMOUS agents may act within bounds; COPILOT requires human confirmation;
     * DECISION_SUPPORT is advisory only and never binding.
     */
    public enum AutonomyLevel {
        AUTONOMOUS,        // [A]
        COPILOT,           // [C]
        DECISION_SUPPORT   // [D]
    }

    /** KYC/CDD intensity tier (PRD §1, derived from jurisdiction rule pack). */
    public enum CddTier {
        SIMPLIFIED,
        STANDARD,
        ENHANCED
    }

    /** Counterparty KYC lifecycle state. */
    public enum KycStatus {
        NOT_STARTED,
        IN_PROGRESS,
        PENDING_REVIEW,
        VERIFIED,
        REJECTED,
        RE_KYC_DUE
    }

    /** Screening hit disposition (PRD §1, US-1.2). Always a named human action. */
    public enum ScreeningDisposition {
        OPEN,
        FALSE_POSITIVE,
        TRUE_POSITIVE_CLEARED,
        ESCALATED,
        EXIT
    }

    /** Document classification types (PRD §3). */
    public enum DocumentType {
        FINANCIAL_STATEMENT,
        MOA_AOA,
        FACILITY_DOC,
        SECURITY_DOC,
        BUREAU_REPORT,
        BANK_STATEMENT,
        TAX_GST,
        KYC_ID,
        OTHER
    }

    /** Covenant breach severity (PRD §7 sample covenant). */
    public enum BreachSeverity {
        MINOR,
        MAJOR,
        CRITICAL
    }

    /** Early-warning signal severity (PRD §11). */
    public enum SignalSeverity {
        LOW,
        MEDIUM,
        HIGH,
        SEVERE
    }

    /** Capital approach selected by the jurisdiction profile (PRD §6/§10). */
    public enum CapitalApproach {
        SA,                // Standardised Approach
        IRB_FOUNDATION     // Internal Ratings-Based, foundation
    }
}
