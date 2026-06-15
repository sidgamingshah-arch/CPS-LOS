package com.helix.common.governance;

/**
 * Catalogue of governed AI capabilities. Wherever an AI endpoint is enforced, it
 * references one of these. Keeping the catalogue centralised means the master-data
 * admin UI can be a fixed list (not free-text), and a regulator-facing inventory
 * of "what AI is present in this platform" is a compile-time artefact, not a doc.
 *
 * <p>Per-jurisdiction overrides are resolved by {@link AiGovernanceClient}. The
 * canonical authority is the {@code AI_GOVERNANCE} master type in config-service.</p>
 */
public enum AiCapability {
    DOC_INTEL("doc-intel", "Document classification + structured extraction"),
    COLLATERAL_INTEL("collateral-intel", "Type-aware collateral data extraction"),
    RAG_OVERLAY("rag-overlay", "Statistical RAG band over the deterministic rating"),
    MACRO_IMPACT("macro-impact", "Macro directional impact on PD"),
    PRICING_OPTIMISER("pricing-optimiser", "Goal-seek pricing scenario optimiser"),
    PRICING_EXCEPTION("pricing-exception", "Concession-approval sub-workflow optimiser"),
    COMMENTARY("commentary", "Narrative commentary drafter"),
    COVENANT_INTEL("covenant-intel", "Covenant extraction + compliance assessment"),
    CPT("cpt", "Client Planning Template (wallet sizing + nudges)"),
    GROUP_SUGGEST("group-suggest", "Group identification advisory match"),
    COPILOT("copilot", "Conversational read-only copilot"),
    QUALITATIVE_SCORECARD("qualitative-scorecard",
            "Qualitative rating-parameter scoring (advisory, prompt-driven, human-confirmed)");

    private final String key;
    private final String description;

    AiCapability(String key, String description) {
        this.key = key;
        this.description = description;
    }

    /** Stable master {@code recordKey} for this capability. */
    public String key() { return key; }
    public String description() { return description; }
}
