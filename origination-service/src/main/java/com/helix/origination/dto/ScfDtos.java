package com.helix.origination.dto;

import com.helix.origination.entity.ScfProgram;
import com.helix.origination.entity.ScfSpoke;

import java.util.List;

/** Request / response DTOs for the SCF product-paper engine (/api/scf). DTOs are records. */
public final class ScfDtos {

    private ScfDtos() {
    }

    public record CreateProgramRequest(String anchorRef, String anchorName, String programType,
                                       Double programLimit, Double perSpokeCap, String currency) {
    }

    public record AddSpokeRequest(String spokeRef, String spokeName, Double requestedAmount) {
    }

    /** Optional decision note carried on approve / reject. */
    public record DecisionRequest(String note) {
    }

    /** The programme with its spokes and derived roll-ups. */
    public record ProgramView(ScfProgram program, List<ScfSpoke> spokes,
                              int spokeCount, long eligibleCount,
                              double requestedTotal, double approvedCapTotal) {
    }
}
