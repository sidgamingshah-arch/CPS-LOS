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

    // ---- create-screen document autofill (advisory extraction; nothing persisted) --------------

    /** JSON body for the extract-doc endpoint when the caller pastes raw document text. */
    public record ExtractDocRequest(String text, String declaredType) {
    }

    /**
     * Advisory field suggestions parsed FROM an uploaded document (trade licence / MOA / AOA) or
     * pasted text, for pre-filling the counterparty create form. Every value is copied verbatim
     * from the document — a named human still reviews, edits and submits. NOTHING is persisted by
     * the extract call; this is a pure read/parse. {@code fields} carries the raw
     * {@code key -> {value, confidence, sourceLine}} map (with a 1-based line citation) and the
     * top-level fields are the convenience prefill values.
     */
    public record DocFieldSuggestion(
            String detectedType,
            boolean contentDerived,
            String extractionMethod,   // TEXT | PDFBOX | EMPTY | UNSUPPORTED
            int pageCount,
            double overallConfidence,
            String legalName,
            String registrationNo,
            String cin,
            String gstin,
            String incorporationDate,
            String registeredAddress,
            List<String> directors,
            Map<String, Object> fields,
            boolean advisory,
            List<String> notes) {
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
