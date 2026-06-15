package com.helix.risk.dto;

import java.util.List;

public class QualitativeDtos {

    /** One parameter line in the qualitative view. */
    public record QualLine(Long id, String parameterKey, String displayName, double weight,
                           double suggestedScore, double score, String band, String rationale,
                           String prompt, String promptSource, String status, String confirmedBy) {
    }

    /**
     * The qualitative assessment for a deal: per-parameter advisory scores and the
     * weighted composite + band. A pure advisory READOUT — it proposes no notch and
     * has no mechanical link to the authoritative grade. Any rating change is a
     * completely manual, human-entered override (notch-limited + SoD) elsewhere.
     */
    public record QualitativeView(String applicationReference, double compositeScore, String compositeBand,
                                  boolean allConfirmed, int parameterCount,
                                  List<QualLine> parameters,
                                  String authoritativeGrade, boolean gradeUnchanged) {
    }

    public record ConfirmRequest(Double score, String note) {
    }
}
