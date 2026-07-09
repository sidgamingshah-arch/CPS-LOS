package com.helix.risk.service;

import com.helix.common.audit.AuditService;
import com.helix.common.governance.AiCapability;
import com.helix.common.governance.AiGovernanceClient;
import com.helix.common.web.ApiException;
import com.helix.risk.client.ModelDefinitionClient;
import com.helix.risk.client.ModelDefinitionClient.ResolvedModel;
import com.helix.risk.client.OriginationClient;
import com.helix.risk.dto.CreditInputsDto;
import com.helix.risk.dto.ModelDtos.AnswerInput;
import com.helix.risk.dto.ModelDtos.ModelView;
import com.helix.risk.entity.ModelAnswer;
import com.helix.risk.entity.ModelInstance;
import com.helix.risk.entity.Rating;
import com.helix.risk.model.ConstraintValidator;
import com.helix.risk.model.ModelDefs;
import com.helix.risk.model.ModuleSourceResolver;
import com.helix.risk.model.OptionResolver;
import com.helix.risk.model.Scorer;
import com.helix.risk.model.StandaloneScorer;
import com.helix.risk.model.VisibilityEvaluator;
import com.helix.risk.repo.ModelAnswerRepository;
import com.helix.risk.repo.ModelInstanceRepository;
import com.helix.risk.repo.RatingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The configurable scoring-model engine. Resolves the right MODEL_DEFINITION for a
 * deal (jurisdiction/sector/segment), renders the typed questionnaire (with
 * visibility rules, master-driven options, iterative groups), captures answers,
 * validates min/max/mandatory constraints, and computes a deterministic weighted
 * composite -> band.
 *
 * <p>Governance, identical to the qualitative scorecard it replaces: the optional
 * AI {@code /suggest} pre-fill is gated by the {@code MODEL_SCORING} capability
 * (off-switch); a named human answers/confirms; and the composite is purely
 * advisory — it never mutates the authoritative grade (asserted by the e2e).</p>
 */
@Service
public class ModelEngine {

    private static final List<String> GRADE_LADDER =
            List.of("AAA", "AA", "A", "BBB", "BB", "B", "CCC", "CC", "C", "D");

    private final ModelInstanceRepository instances;
    private final ModelAnswerRepository answers;
    private final ModelDefinitionClient definitions;
    private final OriginationClient origination;
    private final RatingRepository ratings;
    private final OptionResolver optionResolver;
    private final ModuleSourceResolver moduleSource;
    private final StandaloneScorer standaloneScorer;
    private final AiGovernanceClient governance;
    private final AuditService audit;

    public ModelEngine(ModelInstanceRepository instances, ModelAnswerRepository answers,
                       ModelDefinitionClient definitions, OriginationClient origination,
                       RatingRepository ratings, OptionResolver optionResolver,
                       ModuleSourceResolver moduleSource, StandaloneScorer standaloneScorer,
                       AiGovernanceClient governance, AuditService audit) {
        this.instances = instances;
        this.answers = answers;
        this.definitions = definitions;
        this.origination = origination;
        this.ratings = ratings;
        this.optionResolver = optionResolver;
        this.moduleSource = moduleSource;
        this.standaloneScorer = standaloneScorer;
        this.governance = governance;
        this.audit = audit;
    }

    // ============================================================ resolve / render

    /** Idempotent: returns the existing instance or resolves + creates one. */
    @Transactional
    public ModelInstance instance(String reference) {
        return instances.findByApplicationReference(reference).orElseGet(() -> resolveInternal(reference, null));
    }

    /** Explicit (re)resolve — re-points the instance's pinned model + clears answers (in place). */
    @Transactional
    public ModelView resolve(String reference, String sectorOverride) {
        resolveInternal(reference, sectorOverride);
        return render(reference);
    }

    /**
     * Creates the instance, or re-resolves an existing one IN PLACE (updating the
     * pinned model + clearing answers). Updating in place avoids a delete+insert on
     * the same unique applicationReference within one transaction (SQLite would see
     * the old row at insert time and fail the unique constraint).
     */
    private ModelInstance resolveInternal(String reference, String sectorOverride) {
        CreditInputsDto in = origination.creditInputs(reference);
        if (in == null) throw ApiException.notFound("No credit inputs for " + reference);
        // The deal's own sector drives resolution; an explicit override (e.g. to preview a
        // sector-specific model) wins when supplied.
        String sector = sectorOverride != null && !sectorOverride.isBlank() ? sectorOverride : in.sector();
        ResolvedModel rm = definitions.resolve(in.jurisdiction(), sector, in.segment());
        ModelInstance wf = instances.findByApplicationReference(reference).orElseGet(ModelInstance::new);
        boolean reResolve = wf.getId() != null;
        if (reResolve) {
            answers.deleteAll(answers.findByInstanceIdOrderByIdAsc(wf.getId()));
        }
        wf.setApplicationReference(reference);
        wf.setModelKey(rm.recordKey());
        wf.setModelVersion(rm.version());
        wf.setJurisdiction(in.jurisdiction());
        wf.setSegment(in.segment());
        wf.setSector(sector);
        wf.setStatus("DRAFT");
        wf.setCompositeScore(0);
        wf.setCompositeBand(null);
        wf.setConfirmedBy(null);
        wf.setConfirmedAt(null);
        ModelInstance saved = instances.save(wf);
        audit.engine("MODEL_RESOLVED", "Application", reference,
                "Resolved scoring model " + rm.recordKey() + " v" + rm.version()
                        + " for " + in.jurisdiction() + "/" + in.segment(),
                Map.of("modelKey", rm.recordKey(), "version", rm.version(),
                        "jurisdiction", in.jurisdiction() == null ? "" : in.jurisdiction(),
                        "segment", in.segment() == null ? "" : in.segment()));
        return saved;
    }

    @Transactional
    public ModelView render(String reference) {
        ModelInstance wf = instance(reference);
        return build(wf, current(wf));
    }

    // ============================================================ answer (human or AI)

    @Transactional
    public ModelView answer(String reference, List<AnswerInput> inputs, String actorType, String actor) {
        ModelInstance wf = instance(reference);
        Resolved r = current(wf);
        ModelDefs.Def def = r.def();
        List<ModelAnswer> existing = answers.findByInstanceIdOrderByIdAsc(wf.getId());
        for (AnswerInput a : inputs == null ? List.<AnswerInput>of() : inputs) {
            if (a.questionKey() == null) continue;
            ModelDefs.Question q = def.question(a.questionKey());
            if (q == null) throw ApiException.badRequest("Unknown question '" + a.questionKey() + "'");
            upsert(wf, existing, def, q, a, actorType, null);   // human entry — no system rationale
        }
        recompute(wf, def);
        wf.setStatus("DRAFT");
        instances.save(wf);
        return build(wf, r);
    }

    // ============================================================ suggest (AI advisory, governed)

    @Transactional
    public ModelView suggest(String reference, String actor) {
        ModelInstance wf = instance(reference);
        CreditInputsDto in = origination.creditInputs(reference);
        if (in == null) throw ApiException.notFound("No credit inputs for " + reference);
        // The off-switch: the AI pre-fill is a governed capability (jurisdiction-overridable).
        governance.enforce(AiCapability.MODEL_SCORING, in.jurisdiction());
        Resolved r = current(wf);
        ModelDefs.Def def = r.def();
        Rating rating = ratings.findFirstByApplicationReferenceOrderByCreatedAtDesc(reference).orElse(null);
        double gradeStrength = gradeStrength(rating == null ? null : rating.getFinalGrade());

        List<ModelAnswer> existing = answers.findByInstanceIdOrderByIdAsc(wf.getId());
        int moduleFilled = 0, standaloneScored = 0;
        for (ModelDefs.Section s : def.sections()) {
            for (ModelDefs.Question q : s.questions()) {
                if (q.iterative() || !q.scored()) continue;
                ModelDefs.Source src = q.source();
                if (src != null && src.isModule()) {
                    // Sourced from another CPS module/screen — pull the live datapoint.
                    String value = moduleSource.resolve(src.ref(), in, rating);
                    if (value == null) continue;
                    if (q.dropdown() && !matchesAnOption(q, value)) continue;   // categorical mismatch — skip
                    upsert(wf, existing, def, q, new AnswerInput(q.key(), null, null, value),
                            "SYSTEM", "Sourced from " + src.ref());
                    moduleFilled++;
                } else {
                    // STANDALONE — not fed by another module: score it with the advisory recommender.
                    StandaloneScorer.Recommendation rec = standaloneScorer.recommend(q, in, gradeStrength);
                    String value = q.dropdown() ? closestOption(q, rec.score()) : null;
                    if (value != null) {
                        upsert(wf, existing, def, q, new AnswerInput(q.key(), null, null, value),
                                "AI", rec.rationale());
                        standaloneScored++;
                    }
                }
            }
        }
        recompute(wf, def);
        wf.setStatus("DRAFT");
        instances.save(wf);
        ModelView view = build(wf, r);
        audit.ai("model-scoring", "MODEL_SUGGESTED", "Application", reference,
                ("Auto-scored: %d module-sourced + %d standalone parameter(s); advisory composite %s (%.1f) "
                        + "— authoritative grade %s unchanged")
                        .formatted(moduleFilled, standaloneScored, view.compositeBand(), view.compositeScore(),
                                rating == null ? "n/a" : rating.getFinalGrade()),
                Map.of("moduleFilled", moduleFilled, "standaloneScored", standaloneScored,
                        "composite", view.compositeScore(), "band", view.compositeBand(), "advisory", true));
        return view;
    }

    /** The option whose score is nearest the recommended 0-100 (for standalone dropdown scoring). */
    private String closestOption(ModelDefs.Question q, double recScore) {
        ModelDefs.Option best = null;
        double bestDist = Double.MAX_VALUE;
        for (ModelDefs.Option o : optionResolver.resolve(q)) {
            double d = Math.abs(o.score() - recScore);
            if (d < bestDist) { bestDist = d; best = o; }
        }
        return best == null ? null : best.label();
    }

    private boolean matchesAnOption(ModelDefs.Question q, String value) {
        for (ModelDefs.Option o : optionResolver.resolve(q)) {
            if (o.label().equalsIgnoreCase(value)) return true;
        }
        return false;
    }

    // ============================================================ confirm (human gate)

    @Transactional
    public ModelView confirm(String reference, String actor) {
        if (actor == null || actor.isBlank()) {
            throw ApiException.forbiddenAutonomy("A named human actor is required to confirm the model");
        }
        ModelInstance wf = instance(reference);
        Resolved r = current(wf);
        ModelDefs.Def def = r.def();
        AnswerState st = assemble(wf);
        ConstraintValidator.Result v = ConstraintValidator.validate(def, st.scalar, st.iterCounts);
        if (!v.valid()) {
            throw ApiException.badRequest("Model cannot be confirmed — unmet constraints: "
                    + String.join("; ", v.errors()));
        }
        recompute(wf, def);
        wf.setStatus("CONFIRMED");
        wf.setConfirmedBy(actor);
        wf.setConfirmedAt(Instant.now());
        instances.save(wf);
        audit.human(actor, "MODEL_CONFIRMED", "ModelInstance", String.valueOf(wf.getId()),
                "Confirmed model %s — composite %s (%.1f); advisory, authoritative grade unchanged"
                        .formatted(wf.getModelKey(), wf.getCompositeBand(), wf.getCompositeScore()),
                Map.of("modelKey", wf.getModelKey(), "composite", wf.getCompositeScore(),
                        "band", wf.getCompositeBand()));
        return build(wf, r);
    }

    // ============================================================ internals

    private record Resolved(int version, ModelDefs.Def def) { }

    /**
     * Resolves the currently-active definition for this instance's selector and
     * parses it once. The engine intentionally uses the live active definition
     * (definitions are versioned, maker-checker masters); {@link #build} refreshes
     * the instance's pinned {@code modelVersion} so it never goes stale. Resolved
     * once per public operation and threaded through, so one request sees one
     * consistent snapshot rather than re-resolving per helper call.
     */
    private Resolved current(ModelInstance wf) {
        ResolvedModel rm = definitions.resolve(wf.getJurisdiction(), wf.getSector(), wf.getSegment());
        return new Resolved(rm.version(), ModelDefs.parse(rm.payload()));
    }

    private void upsert(ModelInstance wf, List<ModelAnswer> existing, ModelDefs.Def def,
                        ModelDefs.Question q, AnswerInput a, String source, String rationale) {
        boolean blank = a.value() == null || a.value().isBlank();
        if (q.iterative()) {
            if (a.itemIndex() == null) {
                throw ApiException.badRequest("Iterative question '" + q.key()
                        + "' requires an itemIndex on each answer");
            }
            // Match on (questionKey, itemIndex, itemFieldKey).
            ModelAnswer row = existing.stream()
                    .filter(r -> r.getQuestionKey().equals(q.key())
                            && eq(r.getItemIndex(), a.itemIndex())
                            && java.util.Objects.equals(r.getItemFieldKey(), a.itemFieldKey()))
                    .findFirst().orElse(null);
            if (blank) {
                if (row != null) { answers.delete(row); existing.remove(row); }
                return;
            }
            if (row == null) {
                row = new ModelAnswer();
                row.setInstanceId(wf.getId());
                row.setSectionKey(sectionKeyOf(def, q.key()));
                row.setQuestionKey(q.key());
                row.setItemIndex(a.itemIndex());
                row.setItemFieldKey(a.itemFieldKey());
                existing.add(row);
            }
            row.setValueText(a.value());
            row.setSource(source);
            answers.save(row);
            return;
        }
        // Scalar question: one row keyed by questionKey (itemIndex null).
        ModelAnswer row = existing.stream()
                .filter(r -> r.getQuestionKey().equals(q.key()) && r.getItemIndex() == null)
                .findFirst().orElse(null);
        if (blank) {
            if (row != null) { answers.delete(row); existing.remove(row); }
            return;
        }
        if (row == null) {
            row = new ModelAnswer();
            row.setInstanceId(wf.getId());
            row.setSectionKey(sectionKeyOf(def, q.key()));
            row.setQuestionKey(q.key());
            existing.add(row);
        }
        row.setValueText(a.value());
        try { row.setValueNum(Double.parseDouble(a.value())); } catch (Exception ignored) { row.setValueNum(null); }
        row.setSource(source);
        row.setRationale(rationale);
        answers.save(row);
    }

    private void recompute(ModelInstance wf, ModelDefs.Def def) {
        AnswerState st = assemble(wf);
        Scorer.Result r = Scorer.score(def, st.scalar, optionResolver::resolve);
        wf.setCompositeScore(r.composite());
        wf.setCompositeBand(r.band());
    }

    /** Builds the full render view for a model instance against an already-resolved definition. */
    private ModelView build(ModelInstance wf, Resolved r) {
        ModelDefs.Def def = r.def();
        // Refresh the pinned version so it reflects the definition actually used (never stale).
        if (wf.getModelVersion() != r.version()) wf.setModelVersion(r.version());
        AnswerState st = assemble(wf);
        Scorer.Result scored = Scorer.score(def, st.scalar, optionResolver::resolve);
        ConstraintValidator.Result valid = ConstraintValidator.validate(def, st.scalar, st.iterCounts);

        Map<String, Scorer.SectionScore> bySection = new LinkedHashMap<>();
        for (Scorer.SectionScore ss : scored.sections()) bySection.put(ss.sectionKey(), ss);

        List<Map<String, Object>> sections = new ArrayList<>();
        for (ModelDefs.Section s : def.sections()) {
            Scorer.SectionScore ss = bySection.get(s.key());
            Map<String, Scorer.QuestionScore> qScores = new LinkedHashMap<>();
            if (ss != null) for (Scorer.QuestionScore q : ss.questions()) qScores.put(q.questionKey(), q);

            List<Map<String, Object>> questions = new ArrayList<>();
            for (ModelDefs.Question q : s.questions()) {
                boolean visible = VisibilityEvaluator.visible(q.visibleWhen(), st.scalar);
                Map<String, Object> qv = new LinkedHashMap<>();
                qv.put("key", q.key());
                qv.put("type", q.type());
                qv.put("label", q.label());
                qv.put("weight", q.weight());
                qv.put("required", q.required());
                qv.put("visible", visible);
                if (q.visibleWhen() != null) qv.put("visibleWhen", q.visibleWhen());
                // Parameter provenance: a CPS-module datapoint, or standalone (model-scored / human).
                qv.put("source", q.source() != null && q.source().isModule() ? q.source().ref() : "STANDALONE");
                if (q.dropdown()) {
                    List<Map<String, Object>> opts = new ArrayList<>();
                    for (ModelDefs.Option o : optionResolver.resolve(q)) {
                        opts.add(Map.of("label", o.label(), "score", o.score()));
                    }
                    qv.put("options", opts);
                    if (q.optionsFromMaster() != null) qv.put("optionsFromMaster", q.optionsFromMaster());
                }
                if (q.number() && q.scoreBands() != null) {
                    List<Map<String, Object>> bands = new ArrayList<>();
                    for (ModelDefs.ScoreBand b : q.scoreBands()) bands.add(Map.of(b.edge(), b.threshold(), "score", b.score()));
                    qv.put("scoreBands", bands);
                }
                if (q.iterative()) {
                    qv.put("min", q.min());
                    qv.put("max", q.max());
                    List<Map<String, Object>> fields = new ArrayList<>();
                    for (ModelDefs.ItemField f : q.itemFields()) fields.add(Map.of("key", f.key(), "type", f.type(), "label", f.label()));
                    qv.put("itemFields", fields);
                    qv.put("items", st.iterItems.getOrDefault(q.key(), List.of()));
                } else {
                    qv.put("answer", st.scalar.get(q.key()));
                    qv.put("answerSource", st.source.get(q.key()));
                    qv.put("rationale", st.rationale.get(q.key()));
                }
                Scorer.QuestionScore qsc = qScores.get(q.key());
                qv.put("score", qsc == null ? null : qsc.score());
                questions.add(qv);
            }
            Map<String, Object> sv = new LinkedHashMap<>();
            sv.put("key", s.key());
            sv.put("kind", s.kind());
            sv.put("label", s.label());
            sv.put("weight", s.weight());
            sv.put("sectionScore", ss == null ? null : ss.score());
            sv.put("sectionBand", ss == null ? "N/A" : ss.band());
            sv.put("questions", questions);
            sections.add(sv);
        }

        Rating rating = ratings.findFirstByApplicationReferenceOrderByCreatedAtDesc(wf.getApplicationReference()).orElse(null);
        String grade = rating == null ? null : rating.getFinalGrade();
        return new ModelView(wf.getApplicationReference(), wf.getModelKey(), wf.getModelVersion(),
                def.displayName(), wf.getStatus(), wf.getCompositeScore(), wf.getCompositeBand(),
                true, valid.valid(), valid.answeredCount(), valid.errors(), sections, grade, true,
                wf.getConfirmedBy());
    }

    /** Flattens answer rows into scalar map, source map, iterative counts + item lists. */
    private AnswerState assemble(ModelInstance wf) {
        AnswerState st = new AnswerState();
        // group iterative item fields by (questionKey, itemIndex)
        Map<String, TreeMap<Integer, Map<String, Object>>> iter = new LinkedHashMap<>();
        for (ModelAnswer a : answers.findByInstanceIdOrderByIdAsc(wf.getId())) {
            if (a.getItemIndex() == null) {
                st.scalar.put(a.getQuestionKey(), a.getValueText());
                if (a.getSource() != null) st.source.put(a.getQuestionKey(), a.getSource());
                if (a.getRationale() != null) st.rationale.put(a.getQuestionKey(), a.getRationale());
            } else {
                iter.computeIfAbsent(a.getQuestionKey(), k -> new TreeMap<>())
                        .computeIfAbsent(a.getItemIndex(), k -> new LinkedHashMap<>())
                        .put(a.getItemFieldKey() == null ? "value" : a.getItemFieldKey(), a.getValueText());
            }
        }
        for (var e : iter.entrySet()) {
            st.iterCounts.put(e.getKey(), e.getValue().size());
            List<Map<String, Object>> items = new ArrayList<>();
            for (var row : e.getValue().entrySet()) {
                Map<String, Object> item = new LinkedHashMap<>(row.getValue());
                item.put("_index", row.getKey());
                items.add(item);
            }
            st.iterItems.put(e.getKey(), items);
        }
        return st;
    }

    private static final class AnswerState {
        final Map<String, String> scalar = new LinkedHashMap<>();
        final Map<String, String> source = new LinkedHashMap<>();
        final Map<String, String> rationale = new LinkedHashMap<>();
        final Map<String, Integer> iterCounts = new LinkedHashMap<>();
        final Map<String, List<Map<String, Object>>> iterItems = new LinkedHashMap<>();
    }

    private String sectionKeyOf(ModelDefs.Def def, String questionKey) {
        for (ModelDefs.Section s : def.sections())
            for (ModelDefs.Question q : s.questions())
                if (q.key().equals(questionKey)) return s.key();
        return "";
    }

    private double gradeStrength(String grade) {
        if (grade == null) return 50;
        int i = GRADE_LADDER.indexOf(grade.toUpperCase());
        if (i < 0) return 50;
        return Math.round((1.0 - (double) i / (GRADE_LADDER.size() - 1)) * 100);
    }

    private static boolean eq(Integer a, Integer b) { return java.util.Objects.equals(a, b); }
}
