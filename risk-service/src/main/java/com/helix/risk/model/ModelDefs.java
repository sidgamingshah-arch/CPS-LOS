package com.helix.risk.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Typed, read-only view of a {@code MODEL_DEFINITION} payload (a JSON Map from
 * config-service). Parsing here keeps the engine, evaluators and scorer clean
 * and free of map-casting. Tolerant: unknown fields ignored, missing fields default.
 */
public final class ModelDefs {

    private ModelDefs() { }

    public record Option(String label, double score) { }

    public record ScoreBand(String edge, double threshold, double score) { }

    public record ItemField(String key, String type, String label) { }

    /**
     * Where a parameter's value comes from:
     * <ul>
     *   <li>{@code MODULE} + {@code ref} (e.g. {@code RATIO:NET_LEVERAGE}, {@code RATING:GRADE})
     *       — auto-sourced from another CPS module/screen (spreading, rating, deal).</li>
     *   <li>{@code STANDALONE} (default) — no upstream source; scored by the model's own
     *       advisory recommender (the {@code prompt} grounds it) and/or answered by a human.</li>
     * </ul>
     */
    public record Source(String kind, String ref, String prompt) {
        public boolean isModule() { return "MODULE".equalsIgnoreCase(kind); }
    }

    public record Question(String key, String type, String label, double weight, boolean required,
                           String visibleWhen, List<Option> options, String optionsFromMaster,
                           List<ScoreBand> scoreBands, Integer min, Integer max, List<ItemField> itemFields,
                           Source source) {
        public boolean iterative() { return "ITERATIVE".equalsIgnoreCase(type); }
        public boolean dropdown() { return "DROPDOWN".equalsIgnoreCase(type); }
        public boolean number()   { return "NUMBER".equalsIgnoreCase(type); }
        /** A question contributes to the score iff it has option scores or numeric bands. */
        public boolean scored() {
            return (dropdown() && (optionsFromMaster != null || (options != null && !options.isEmpty())))
                    || (number() && scoreBands != null && !scoreBands.isEmpty());
        }
    }

    public record Section(String key, String kind, String label, double weight, List<Question> questions) { }

    public record Constraints(int minAnswered, int maxAnswered, List<String> mandatory) { }

    public record Band(String band, double min) { }

    public record Def(String modelKey, String displayName, Map<String, Object> selector,
                      List<Section> sections, Constraints constraints, List<Band> bands,
                      boolean ratingModelOfRecord) {
        public Question question(String key) {
            for (Section s : sections) for (Question q : s.questions()) if (q.key().equals(key)) return q;
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static Def parse(Map<String, Object> payload) {
        if (payload == null) payload = Map.of();
        List<Section> sections = new ArrayList<>();
        Object secRaw = payload.get("sections");
        if (secRaw instanceof List<?> secs) {
            for (Object so : secs) {
                if (!(so instanceof Map)) continue;
                Map<String, Object> s = (Map<String, Object>) so;
                List<Question> qs = new ArrayList<>();
                Object qRaw = s.get("questions");
                if (qRaw instanceof List<?> ql) {
                    for (Object qo : ql) {
                        if (qo instanceof Map) qs.add(question((Map<String, Object>) qo));
                    }
                }
                sections.add(new Section(str(s.get("key")), str(s.get("kind")), str(s.get("label")),
                        num(s.get("weight"), 0), qs));
            }
        }
        Constraints cons = constraints((Map<String, Object>) asMap(payload.get("constraints")));
        List<Band> bands = bands((Map<String, Object>) asMap(payload.get("scoring")));
        Object morRaw = payload.get("ratingModelOfRecord");
        boolean ratingModelOfRecord = Boolean.TRUE.equals(morRaw)
                || "true".equalsIgnoreCase(String.valueOf(morRaw));   // absent/false -> advisory (default)
        return new Def(str(payload.get("modelKey")), str(payload.get("displayName")),
                (Map<String, Object>) asMap(payload.get("selector")), sections, cons, bands,
                ratingModelOfRecord);
    }

    @SuppressWarnings("unchecked")
    private static Question question(Map<String, Object> q) {
        List<Option> options = new ArrayList<>();
        if (q.get("options") instanceof List<?> ol) {
            for (Object oo : ol) {
                if (oo instanceof Map) {
                    Map<String, Object> o = (Map<String, Object>) oo;
                    options.add(new Option(str(o.get("label")), num(o.get("score"), 0)));
                }
            }
        }
        List<ScoreBand> bands = new ArrayList<>();
        if (q.get("scoreBands") instanceof List<?> bl) {
            for (Object bo : bl) {
                if (bo instanceof Map) {
                    Map<String, Object> b = (Map<String, Object>) bo;
                    if (b.containsKey("max")) bands.add(new ScoreBand("max", num(b.get("max"), 0), num(b.get("score"), 0)));
                    else if (b.containsKey("min")) bands.add(new ScoreBand("min", num(b.get("min"), 0), num(b.get("score"), 0)));
                }
            }
        }
        List<ItemField> fields = new ArrayList<>();
        if (q.get("itemFields") instanceof List<?> fl) {
            for (Object fo : fl) {
                if (fo instanceof Map) {
                    Map<String, Object> f = (Map<String, Object>) fo;
                    fields.add(new ItemField(str(f.get("key")), str(f.get("type")), str(f.get("label"))));
                }
            }
        }
        Integer min = q.get("min") instanceof Number n ? n.intValue() : null;
        Integer max = q.get("max") instanceof Number n ? n.intValue() : null;
        Source source = source(q.get("source"));
        return new Question(str(q.get("key")), str(q.get("type")), str(q.get("label")),
                num(q.get("weight"), 0), Boolean.TRUE.equals(q.get("required")),
                q.get("visibleWhen") == null ? null : str(q.get("visibleWhen")),
                options, q.get("optionsFromMaster") == null ? null : str(q.get("optionsFromMaster")),
                bands, min, max, fields, source);
    }

    @SuppressWarnings("unchecked")
    private static Source source(Object raw) {
        if (!(raw instanceof Map)) return new Source("STANDALONE", null, null);
        Map<String, Object> s = (Map<String, Object>) raw;
        String kind = s.get("kind") == null ? "STANDALONE" : str(s.get("kind"));
        return new Source(kind, s.get("ref") == null ? null : str(s.get("ref")),
                s.get("prompt") == null ? null : str(s.get("prompt")));
    }

    private static Constraints constraints(Map<String, Object> c) {
        if (c == null) return new Constraints(0, Integer.MAX_VALUE, List.of());
        int min = (int) num(c.get("minAnswered"), 0);
        int max = c.get("maxAnswered") instanceof Number n ? n.intValue() : Integer.MAX_VALUE;
        List<String> mandatory = new ArrayList<>();
        if (c.get("mandatory") instanceof List<?> ml) for (Object o : ml) mandatory.add(String.valueOf(o));
        return new Constraints(min, max, mandatory);
    }

    @SuppressWarnings("unchecked")
    private static List<Band> bands(Map<String, Object> scoring) {
        List<Band> out = new ArrayList<>();
        if (scoring != null && scoring.get("bands") instanceof List<?> bl) {
            for (Object bo : bl) {
                if (bo instanceof Map) {
                    Map<String, Object> b = (Map<String, Object>) bo;
                    out.add(new Band(str(b.get("band")), num(b.get("min"), 0)));
                }
            }
        }
        if (out.isEmpty()) {
            out.add(new Band("STRONG", 67));
            out.add(new Band("ADEQUATE", 45));
            out.add(new Band("WEAK", 0));
        }
        return out;
    }

    public static String bandFor(double score, List<Band> bands) {
        String chosen = "WEAK";
        double bestMin = -1;
        for (Band b : bands) {
            if (score >= b.min() && b.min() >= bestMin) { chosen = b.band(); bestMin = b.min(); }
        }
        return chosen;
    }

    private static Object asMap(Object o) { return o instanceof Map ? o : null; }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static double num(Object o, double dflt) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s) try { return Double.parseDouble(s); } catch (Exception ignored) { }
        return dflt;
    }
}
