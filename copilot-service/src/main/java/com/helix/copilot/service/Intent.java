package com.helix.copilot.service;

/** The topics the copilot can answer, plus the guardrail outcomes. */
public enum Intent {
    SUMMARY,
    RATING,
    CAPITAL,
    PRICING,
    SPREAD,
    COVENANTS,
    DECISION,
    ECL,
    EWS,
    CONCENTRATION,
    PORTFOLIO,
    KYC,
    SCREENING,
    UBO,
    ACTION_BLOCKED,   // a credit-consequential action — refused, routed to the gated workflow
    UNKNOWN
}
