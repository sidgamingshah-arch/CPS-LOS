package com.helix.risk.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic weighted-composite scorer. Per scored+visible+answered question:
 * a 0-100 score (the chosen DROPDOWN option's score, or the first matching NUMBER
 * band). Per section: weighted mean of its scored questions. Overall: weighted
 * mean of sections that scored. Bands from the definition. No AI — given the
 * answers, the figure is fully reproducible.
 */
public final class Scorer {

    private Scorer() { }

    public record QuestionScore(String questionKey, Double score) { }

    public record SectionScore(String sectionKey, double score, String band, List<QuestionScore> questions) { }

    public record Result(double composite, String band, List<SectionScore> sections) { }

    public static Result score(ModelDefs.Def def, Map<String, String> answers,
                               java.util.function.Function<ModelDefs.Question, List<ModelDefs.Option>> options) {
        List<SectionScore> sectionScores = new java.util.ArrayList<>();
        double overallWeighted = 0, overallWeight = 0;
        for (ModelDefs.Section s : def.sections()) {
            List<QuestionScore> qScores = new java.util.ArrayList<>();
            double secWeighted = 0, secWeight = 0;
            for (ModelDefs.Question q : s.questions()) {
                if (!VisibilityEvaluator.visible(q.visibleWhen(), answers)) continue;
                Double qs = questionScore(q, answers.get(q.key()), options);
                qScores.add(new QuestionScore(q.key(), qs));
                if (qs != null && q.scored() && q.weight() > 0) {
                    secWeighted += qs * q.weight();
                    secWeight += q.weight();
                }
            }
            double secScore = secWeight > 0 ? round1(secWeighted / secWeight) : 0.0;
            String secBand = secWeight > 0 ? ModelDefs.bandFor(secScore, def.bands()) : "N/A";
            sectionScores.add(new SectionScore(s.key(), secScore, secBand, qScores));
            if (secWeight > 0 && s.weight() > 0) {
                overallWeighted += secScore * s.weight();
                overallWeight += s.weight();
            }
        }
        double composite = overallWeight > 0 ? round1(overallWeighted / overallWeight) : 0.0;
        String band = overallWeight > 0 ? ModelDefs.bandFor(composite, def.bands()) : "N/A";
        return new Result(composite, band, sectionScores);
    }

    /** Null when the question is not scored or not answered. */
    public static Double questionScore(ModelDefs.Question q, String answer,
                                       java.util.function.Function<ModelDefs.Question, List<ModelDefs.Option>> options) {
        if (answer == null || answer.isBlank() || !q.scored()) return null;
        if (q.dropdown()) {
            for (ModelDefs.Option o : options.apply(q)) {
                if (o.label().equalsIgnoreCase(answer)) return o.score();
            }
            return null;   // answer not among the resolved options
        }
        if (q.number()) {
            double v;
            try { v = Double.parseDouble(answer); } catch (Exception e) { return null; }
            for (ModelDefs.ScoreBand b : q.scoreBands()) {
                if ("max".equals(b.edge()) && v <= b.threshold()) return b.score();
                if ("min".equals(b.edge()) && v >= b.threshold()) return b.score();
            }
            return null;
        }
        return null;
    }

    public static Map<String, Object> toMap(Result r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("composite", r.composite());
        m.put("band", r.band());
        return m;
    }

    private static double round1(double v) { return Math.round(v * 10.0) / 10.0; }
}
