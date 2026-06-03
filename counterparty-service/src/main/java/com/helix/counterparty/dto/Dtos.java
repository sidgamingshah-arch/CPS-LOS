package com.helix.counterparty.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** Request/response payloads for the counterparty service. */
public final class Dtos {

    private Dtos() {
    }

    public record CreateCounterpartyRequest(
            @NotBlank String legalName,
            String legalForm,
            String registrationNo,
            @NotBlank String jurisdiction,
            @NotBlank String segment,
            String sector,
            String country,
            boolean listedEntity,
            boolean regulatedFi,
            boolean pep,
            boolean adverseMedia,
            boolean highRiskJurisdiction,
            boolean complexOwnership) {
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
}
