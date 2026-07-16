package com.helix.counterparty.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

public final class InitiationDtos {

    private InitiationDtos() {
    }

    public record CreateProspectRequest(
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
            String industry,
            String subIndustry,
            String businessSegment,
            String subSegment,
            String country,
            String borrowerType,            // NTB | ETB | DUAL_OBLIGOR (default NTB)
            boolean pep, boolean adverseMedia, boolean highRiskJurisdiction, boolean complexOwnership) {
    }

    public record DedupMatch(Long id, String reference, String legalName, String rmId, String classification,
                             String kycStatus, String lifecycleStatus, String matchType, double score, String updatedAt) {
    }

    public record DedupResult(Long prospectId, String strategy, List<String> identifierFields,
                              int matchCount, List<DedupMatch> matches) {
    }

    public record NegativeHit(String type, String value, String reason, String matchedOn) {
    }

    public record NegativeResult(boolean hit, List<NegativeHit> matches) {
    }

    public record DecisionRequest(boolean proceed, String reason) {
    }

    public record CreationSummary(Long prospectId, String legalName, String recordType, String lifecycleStatus,
                                  DedupResult dedup, NegativeResult negative, List<Map<String, Object>> externalChecks,
                                  Map<String, Object> groupExposure, Map<String, Object> industryInsight,
                                  List<String> blockers) {
    }

    public record AssignOwnershipRequest(@NotBlank String toRm, String mode, String note) {
    }

    public record CreateGroupRequest(@NotBlank String name, String groupRmId, String country, boolean multiCountry) {
    }

    public record FetchCheckRequest(String entityType, String entityName, String checkType) {
    }

    // ---- group identification (advisory, AI-assisted, PRD §1 group fetching) ----

    public record GroupCandidate(Long groupId, String reference, String name, String groupRm,
                                 int memberCount, double score, List<String> signals) {
    }

    public record SiblingCandidate(Long counterpartyId, String reference, String legalName,
                                   String country, String sector, double score, List<String> signals) {
    }

    public record GroupSuggestionResult(Long subjectId, String subjectReference, String subjectLegalName,
                                        Long currentGroupId, String currentGroupReference,
                                        List<GroupCandidate> groupMatches,
                                        List<SiblingCandidate> ungroupedSiblings,
                                        String recommendation,           // TAG_TO_EXISTING_GROUP | CREATE_NEW_GROUP | NO_STRONG_MATCH
                                        double topScore,
                                        boolean advisory) {
    }
}
