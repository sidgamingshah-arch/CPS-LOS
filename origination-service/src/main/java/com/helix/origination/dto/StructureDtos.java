package com.helix.origination.dto;

import com.helix.origination.entity.DealParticipant;
import com.helix.origination.entity.DealStructure;

import java.util.List;

public final class StructureDtos {

    private StructureDtos() {
    }

    public record SetStructureRequest(String structureType, Boolean islamic, String groupReference,
                                      String leadArranger, Double totalDealAmount, Double ourShareAmount,
                                      String notes) {
    }

    public record AddParticipantRequest(String role, String name, String externalRef, Double sharePct,
                                        Double obligationAmount, Double committedAmount, String liabilityType) {
    }

    public record ValidationFinding(String level, String message) {
    }

    /** The structure, its participants, and the validation findings for the chosen variant. */
    public record StructureView(DealStructure structure, List<DealParticipant> participants,
                                boolean valid, List<ValidationFinding> findings,
                                double obligorShareSumPct, double lenderCommittedSum) {
    }
}
