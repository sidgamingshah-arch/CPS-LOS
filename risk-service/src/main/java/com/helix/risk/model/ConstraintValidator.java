package com.helix.risk.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Validates the captured answers against the model's constraints, over VISIBLE
 * questions only (a hidden question is neither counted nor mandatory):
 * <ul>
 *   <li>{@code minAnswered}/{@code maxAnswered} — count of answered visible questions</li>
 *   <li>{@code mandatory[]} — each visible mandatory question must be answered</li>
 *   <li>ITERATIVE {@code min}/{@code max} — repeating-group cardinality</li>
 * </ul>
 */
public final class ConstraintValidator {

    private ConstraintValidator() { }

    public record Result(boolean valid, int answeredCount, List<String> errors) { }

    public static Result validate(ModelDefs.Def def, Map<String, String> answers,
                                  Map<String, Integer> iterativeCounts) {
        List<String> errors = new ArrayList<>();
        int answered = 0;
        for (ModelDefs.Section s : def.sections()) {
            for (ModelDefs.Question q : s.questions()) {
                if (!VisibilityEvaluator.visible(q.visibleWhen(), answers)) continue;
                boolean isAnswered;
                if (q.iterative()) {
                    int n = iterativeCounts.getOrDefault(q.key(), 0);
                    isAnswered = n > 0;
                    if (q.min() != null && n < q.min()) {
                        errors.add(q.label() + ": needs at least " + q.min() + " item(s) (" + n + " given)");
                    }
                    if (q.max() != null && n > q.max()) {
                        errors.add(q.label() + ": at most " + q.max() + " item(s) allowed (" + n + " given)");
                    }
                } else {
                    String v = answers.get(q.key());
                    isAnswered = v != null && !v.isBlank();
                }
                if (isAnswered) answered++;
                if (q.required() && !isAnswered) {
                    errors.add(q.label() + " is required");
                }
            }
        }
        if (def.constraints() != null) {
            for (String mandatoryKey : def.constraints().mandatory()) {
                ModelDefs.Question q = def.question(mandatoryKey);
                if (q == null) continue;
                if (!VisibilityEvaluator.visible(q.visibleWhen(), answers)) continue;   // hidden -> not enforced
                String v = answers.get(mandatoryKey);
                boolean ans = q.iterative()
                        ? iterativeCounts.getOrDefault(mandatoryKey, 0) > 0
                        : (v != null && !v.isBlank());
                if (!ans) errors.add("Mandatory: " + q.label() + " must be answered");
            }
            if (answered < def.constraints().minAnswered()) {
                errors.add("At least " + def.constraints().minAnswered()
                        + " questions must be answered (" + answered + " answered)");
            }
            if (answered > def.constraints().maxAnswered()) {
                errors.add("At most " + def.constraints().maxAnswered()
                        + " questions may be answered (" + answered + " answered)");
            }
        }
        // de-dupe while preserving order
        List<String> unique = new ArrayList<>(new java.util.LinkedHashSet<>(errors));
        return new Result(unique.isEmpty(), answered, unique);
    }
}
