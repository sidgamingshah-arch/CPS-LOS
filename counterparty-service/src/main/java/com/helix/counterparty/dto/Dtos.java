package com.helix.counterparty.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

/** Request/response payloads for the counterparty service. */
public final class Dtos {

    private Dtos() {
    }

    public record CreateCounterpartyRequest(
            @NotBlank String legalName,
            String legalForm,
            String registrationNo,
            String pan,
            String gstin,
            String lei,
            String cin,
            @NotBlank String jurisdiction,
            @NotBlank String segment,
            String sector,
            String country,
            String presentationCurrency,
            boolean listedEntity,
            boolean regulatedFi,
            boolean pep,
            boolean adverseMedia,
            boolean highRiskJurisdiction,
            boolean complexOwnership,
            // Config-defined risk flags BEYOND the six typed booleans above (RISK_FLAG master keys
            // -> boolean). Optional/nullable; lets a newly-authored catalogue flag be captured
            // without a schema change. The six known keys still ride the typed booleans.
            Map<String, Object> extraRiskFlags) {
    }

    /** A named-human rationale recorded against a screening hit when no LLM drafted one. */
    public record HumanRationaleRequest(@NotBlank String rationale) {
    }

    /** Declared ownership structure for UBO resolution (PRD §1, US-1.1). */
    public record UboStructureRequest(
            @NotNull List<NodeInput> nodes,
            @NotNull List<EdgeInput> edges) {

        public record NodeInput(@NotBlank String key, @NotBlank String name,
                                @NotBlank String type, String country, Double confidence) {
        }

        public record EdgeInput(@NotBlank String parent, @NotBlank String child, double ownershipPct) {
        }
    }

    public record DispositionRequest(@NotBlank String disposition, String note) {
    }

    public record CloseRequest(@NotBlank String reason) {
    }

    // ---- hygiene RAG (deterministic read-only aggregation — no figures touched) ----

    public record HygieneCheck(String key, String state, String detail) {
    }

    public record HygieneResult(Long counterpartyId, String reference, String status,
                                List<HygieneCheck> checks) {
    }
}
