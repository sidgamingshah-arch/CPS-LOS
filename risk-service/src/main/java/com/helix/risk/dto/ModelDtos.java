package com.helix.risk.dto;

import java.util.List;
import java.util.Map;

public class ModelDtos {

    /** One answer to submit. {@code itemIndex}/{@code itemFieldKey} only for ITERATIVE questions. */
    public record AnswerInput(String questionKey, Integer itemIndex, String itemFieldKey, String value) {
    }

    public record AnswerRequest(List<AnswerInput> answers) {
    }

    /**
     * The full render of a model instance for a deal: the resolved definition
     * (with resolved options + computed visibility + current answers + per-question
     * scores), the section/overall composite + band, the constraint validation,
     * and the authoritative grade (read-only — the model never moves it).
     */
    public record ModelView(String applicationReference, String modelKey, int modelVersion,
                            String displayName, String status,
                            double compositeScore, String compositeBand, boolean advisory,
                            boolean valid, int answeredCount, List<String> errors,
                            List<Map<String, Object>> sections,
                            String authoritativeGrade, boolean gradeUnchanged,
                            String confirmedBy) {
    }
}
