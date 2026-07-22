package com.helix.decision.dto;

import com.helix.decision.entity.DocumentExecution;
import com.helix.decision.entity.ExecutionPackage;
import com.helix.decision.entity.Signatory;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/** DTOs for the document-execution workflow + signatory matrix ({@code /api/execution}). */
public final class ExecutionDtos {

    private ExecutionDtos() {
    }

    /** One document to enrol into the package — {@code docRef} is a GeneratedDocument id / ref. */
    public record DocRef(@NotBlank String docRef, String title) {
    }

    /** Create a package over a subject (deal) from a set of generated documents. */
    public record CreatePackageRequest(@NotBlank String subjectRef, List<DocRef> documents) {
    }

    /** Add a signatory to a document's matrix. {@code side} ∈ INTERNAL | CUSTOMER. */
    public record AddSignatoryRequest(@NotBlank String name, String role, @NotBlank String side) {
    }

    /** Set a document's execution status. {@code status} ∈ PENDING | SENT | SIGNED | RECEIVED. */
    public record StatusRequest(@NotBlank String status) {
    }

    /** Tag a document as deferred. */
    public record DeferRequest(@NotBlank String deferralTag) {
    }

    /** Tag a document as waived. */
    public record WaiveRequest(@NotBlank String waiverTag) {
    }

    /** A document plus its signatory matrix. */
    public record DocumentView(DocumentExecution document, List<Signatory> signatories) {
    }

    /** A package plus each of its documents (with signatories). */
    public record PackageView(ExecutionPackage executionPackage, List<DocumentView> documents) {
    }
}
