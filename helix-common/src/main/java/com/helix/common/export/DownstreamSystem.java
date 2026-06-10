package com.helix.common.export;

/**
 * Downstream consumers of Helix canonical outbound feeds (PRD downstream
 * ERM/Finance/CPR interfaces). The outbound counterpart to {@link
 * com.helix.common.ingest.SourceSystem}.
 */
public enum DownstreamSystem {
    ERM,            // Enterprise Risk Management — obligor/exposure risk records
    FINANCE_GL,     // Finance / General Ledger — provisioning & accounting entries
    CPR,            // Credit Policy / Portfolio Reporting — composition & concentration
    REGULATORY,     // Regulatory reporting feeds
    SYNDICATION     // Syndicate participant statements — per-lender share/funded/fees feed
}
