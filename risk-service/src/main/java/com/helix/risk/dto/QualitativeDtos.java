package com.helix.risk.dto;

import java.util.List;

public class QualitativeDtos {

    /** One parameter line in the qualitative view. */
    public record QualLine(Long id, String parameterKey, String displayName, double weight,
                           double suggestedScore, double score, String band, String rationale,
                           String prompt, String promptSource, String status, String confirmedBy) {
    }

    /**
     * The qualitative assessment for a deal: per-parameter advisory scores, the
     * weighted composite + band, and an ADVISORY notch suggestion to the rating
     * (never applied automatically — the deterministic grade is unchanged).
     */
    public record QualitativeView(String applicationReference, double compositeScore, String compositeBand,
                                  int suggestedNotch, boolean allConfirmed, int parameterCount,
                                  List<QualLine> parameters,
                                  String authoritativeGrade, boolean gradeUnchanged) {
    }

    public record ConfirmRequest(Double score, String note) {
    }
}
