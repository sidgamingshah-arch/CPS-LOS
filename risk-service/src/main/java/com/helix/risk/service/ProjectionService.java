package com.helix.risk.service;

import com.helix.common.audit.AuditService;
import com.helix.common.formula.FormulaEvaluator;
import com.helix.common.web.ApiException;
import com.helix.risk.client.OriginationClient;
import com.helix.risk.client.ProjectionTemplateClient;
import com.helix.risk.client.ProjectionTemplateClient.Template;
import com.helix.risk.dto.CreditInputsDto;
import com.helix.risk.dto.ProjectionDtos.DriverView;
import com.helix.risk.dto.ProjectionDtos.LineStat;
import com.helix.risk.dto.ProjectionDtos.MonteCarloDriver;
import com.helix.risk.dto.ProjectionDtos.MonteCarloView;
import com.helix.risk.dto.ProjectionDtos.ProjectionView;
import com.helix.risk.dto.ProjectionDtos.ProjectionYear;
import com.helix.risk.dto.ProjectionDtos.SensitivityView;
import com.helix.risk.entity.Projection;
import com.helix.risk.entity.Rating;
import com.helix.risk.repo.ProjectionRepository;
import com.helix.risk.repo.RatingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Multi-year financial projection engine. Seeds from the deal's base-year actuals
 * (the spread's latest period), applies the analyst's driver assumptions to the
 * resolved PROJECTION_TEMPLATE, and computes a deterministic year-by-year proforma
 * (revenue, EBITDA, debt, debt-service, projected DSCR, …). Includes single-driver
 * sensitivity. Advisory throughout — projections never move the authoritative
 * rating/capital/pricing (asserted by the e2e).
 */
@Service
public class ProjectionService {

    private final ProjectionRepository repo;
    private final ProjectionTemplateClient templates;
    private final OriginationClient origination;
    private final RatingRepository ratings;
    private final AuditService audit;
    private final com.helix.common.governance.AiGovernanceClient governance;

    public ProjectionService(ProjectionRepository repo, ProjectionTemplateClient templates,
                             OriginationClient origination, RatingRepository ratings, AuditService audit,
                             com.helix.common.governance.AiGovernanceClient governance) {
        this.repo = repo;
        this.templates = templates;
        this.origination = origination;
        this.ratings = ratings;
        this.audit = audit;
        this.governance = governance;
    }

    /** The three calibration inputs the driver distributions are drawn from (Stage-2/3 projection norms). */
    private static final List<String> MC_SOURCES = List.of("INDUSTRY_FEED", "PEER_STATS", "HISTORICAL_TREND");

    // ============================================================ view / compute

    @Transactional
    public ProjectionView view(String reference) {
        CreditInputsDto in = origination.creditInputs(reference);
        if (in == null) throw ApiException.notFound("No credit inputs for " + reference);
        Template t = templates.resolve(in.jurisdiction(), in.sector(), in.segment());
        Projection proj = repo.findByApplicationReference(reference).orElseGet(() -> {
            Projection p = new Projection();
            p.setApplicationReference(reference);
            p.setTemplateKey(t.templateKey());
            p.setTemplateVersion(t.version());
            p.setHorizonYears(t.horizonYears());
            p.setDrivers(new LinkedHashMap<>());   // empty -> all template defaults
            p.setStatus("DRAFT");
            return repo.save(p);
        });
        return build(reference, proj, t, in);
    }

    @Transactional
    public ProjectionView setDrivers(String reference, Map<String, Double> overrides) {
        CreditInputsDto in = origination.creditInputs(reference);
        Template t = templates.resolve(in.jurisdiction(), in.sector(), in.segment());
        Projection proj = repo.findByApplicationReference(reference).orElseGet(() -> {
            Projection p = new Projection();
            p.setApplicationReference(reference);
            p.setTemplateKey(t.templateKey());
            p.setTemplateVersion(t.version());
            p.setHorizonYears(t.horizonYears());
            return repo.save(p);
        });
        Map<String, Object> drivers = proj.getDrivers() == null ? new LinkedHashMap<>()
                : new LinkedHashMap<>(proj.getDrivers());
        if (overrides != null) {
            // Only accept driver keys the template declares.
            for (var d : t.drivers()) {
                if (overrides.containsKey(d.key())) drivers.put(d.key(), overrides.get(d.key()));
            }
        }
        proj.setDrivers(drivers);
        proj.setStatus("DRAFT");
        repo.save(proj);
        audit.engine("PROJECTION_DRIVERS_SET", "Application", reference,
                "Projection drivers updated for " + reference + " (advisory)",
                Map.of("drivers", drivers));
        return build(reference, proj, t, in);
    }

    @Transactional
    public SensitivityView sensitivity(String reference, String driverKey, double delta) {
        CreditInputsDto in = origination.creditInputs(reference);
        Template t = templates.resolve(in.jurisdiction(), in.sector(), in.segment());
        if (t.drivers().stream().noneMatch(d -> d.key().equals(driverKey))) {
            throw ApiException.badRequest("Unknown driver '" + driverKey + "'");
        }
        Projection proj = repo.findByApplicationReference(reference)
                .orElseThrow(() -> ApiException.notFound("No projection for " + reference + " — open it first"));
        Map<String, Double> baseDrivers = effectiveDrivers(t, proj);
        Map<String, Double> flexed = new LinkedHashMap<>(baseDrivers);
        flexed.put(driverKey, baseDrivers.getOrDefault(driverKey, 0.0) + delta);

        Map<String, Double> base = baseActuals(t, in);
        List<ProjectionYear> baseGrid = compute(t, baseDrivers, base);
        List<ProjectionYear> flexedGrid = compute(t, flexed, base);
        double baseDscr = finalDscr(baseGrid);
        double flexedDscr = finalDscr(flexedGrid);
        return new SensitivityView(driverKey, delta,
                baseDrivers.getOrDefault(driverKey, 0.0), flexed.get(driverKey),
                baseGrid, flexedGrid, baseDscr, flexedDscr);
    }

    @Transactional
    public ProjectionView confirm(String reference, String actor) {
        if (actor == null || actor.isBlank()) {
            throw ApiException.forbiddenAutonomy("A named human actor is required to confirm a projection");
        }
        Projection proj = repo.findByApplicationReference(reference)
                .orElseThrow(() -> ApiException.notFound("No projection for " + reference));
        proj.setStatus("CONFIRMED");
        proj.setConfirmedBy(actor);
        proj.setConfirmedAt(Instant.now());
        repo.save(proj);
        CreditInputsDto in = origination.creditInputs(reference);
        Template t = templates.resolve(in.jurisdiction(), in.sector(), in.segment());
        audit.human(actor, "PROJECTION_CONFIRMED", "Projection", String.valueOf(proj.getId()),
                "Confirmed %d-year projection %s for %s (advisory; authoritative figures unchanged)"
                        .formatted(t.horizonYears(), t.templateKey(), reference),
                Map.of("templateKey", t.templateKey(), "horizonYears", t.horizonYears()));
        return build(reference, proj, t, in);
    }

    // ============================================================ Monte-Carlo simulation

    /**
     * Monte-Carlo projection: run N stochastic iterations of the SAME deterministic per-line proforma
     * with each driver sampled from a Normal(mean, |mean|×volatility) distribution whose volatility is
     * calibrated (in the PROJECTION_TEMPLATE) from industry / peer / historical inputs. Reports the
     * final-year DSCR band (P10/P50/P90 + mean), the covenant-breach probability P(DSCR&lt;1), and the
     * final-year distribution of every line. The RNG is seeded deterministically from the deal
     * reference, so the same deal reproduces the same bands (auditable). ADVISORY — the authoritative
     * rating/capital/pricing are never read into or mutated by this path; only the confirmed spread's
     * base actuals + the template drive it. Governed by the MONTE_CARLO capability off-switch.
     */
    @Transactional
    public MonteCarloView simulate(String reference, Integer iterations, String actor) {
        CreditInputsDto in = origination.creditInputs(reference);
        if (in == null) throw ApiException.notFound("No credit inputs for " + reference);
        governance.enforce(com.helix.common.governance.AiCapability.MONTE_CARLO, in.jurisdiction());
        Template t = templates.resolve(in.jurisdiction(), in.sector(), in.segment());
        Projection proj = repo.findByApplicationReference(reference).orElse(null);
        Map<String, Double> meanDrivers = proj != null ? effectiveDrivers(t, proj)
                : ProjectionTemplateClient.defaults(t);
        Map<String, Double> base = baseActuals(t, in);

        int iters = iterations == null ? 2000 : Math.max(200, Math.min(iterations, 20000));
        long seed = 0x9E3779B97F4A7C15L ^ (long) reference.hashCode();   // deterministic per deal
        java.util.Random rng = new java.util.Random(seed);

        double[] dscr = new double[iters];
        Map<String, double[]> lineSamples = new LinkedHashMap<>();
        for (var l : t.lines()) lineSamples.put(l.key(), new double[iters]);

        for (int i = 0; i < iters; i++) {
            Map<String, Double> sampled = new LinkedHashMap<>();
            for (var d : t.drivers()) {
                double mean = meanDrivers.getOrDefault(d.key(), d.defaultValue());
                double sd = Math.abs(mean) * d.volatility();
                sampled.put(d.key(), mean + rng.nextGaussian() * sd);
            }
            List<ProjectionYear> grid = compute(t, sampled, base);
            Map<String, Double> last = grid.isEmpty() ? Map.of() : grid.get(grid.size() - 1).values();
            dscr[i] = last.getOrDefault("DSCR", 0.0);
            for (var l : t.lines()) lineSamples.get(l.key())[i] = last.getOrDefault(l.key(), 0.0);
        }

        int breaches = 0;
        for (double v : dscr) if (v < 1.0) breaches++;
        double breachProb = iters == 0 ? 0.0 : round4((double) breaches / iters);

        List<MonteCarloDriver> driverViews = new ArrayList<>();
        for (var d : t.drivers()) {
            driverViews.add(new MonteCarloDriver(d.key(), d.label(),
                    round4(meanDrivers.getOrDefault(d.key(), d.defaultValue())), d.volatility(), MC_SOURCES));
        }
        List<LineStat> lineStats = new ArrayList<>();
        for (var l : t.lines()) {
            double[] s = lineSamples.get(l.key());
            lineStats.add(new LineStat(l.key(), l.label(),
                    percentile(s, 0.10), percentile(s, 0.50), percentile(s, 0.90), mean(s)));
        }

        Rating rating = ratings.findFirstByApplicationReferenceOrderByCreatedAtDesc(reference).orElse(null);
        List<String> notes = List.of(
                "Per-line driver distributions Normal(mean, |mean|×volatility); volatility calibrated from "
                        + "industry feed / peer statistics / historical trend (PROJECTION_TEMPLATE).",
                "Deterministic RNG seeded from the deal reference — the same deal reproduces the same bands.",
                "Advisory only: the authoritative rating / capital / pricing are not read into or moved by this simulation.");
        audit.ai("monte-carlo-projection", "MONTE_CARLO_SIMULATED", "Application", reference,
                "Ran %d Monte-Carlo iterations (%s v%d): final-year DSCR P50 %.2f, P(DSCR<1) %.1f%%"
                        .formatted(iters, t.templateKey(), t.version(), percentile(dscr, 0.50), breachProb * 100),
                Map.of("iterations", iters, "seed", seed, "dscrP50", percentile(dscr, 0.50),
                        "breachProbability", breachProb, "templateKey", t.templateKey()));

        return new MonteCarloView(reference, t.templateKey(), t.version(), t.horizonYears(), iters, seed, true,
                driverViews, percentile(dscr, 0.10), percentile(dscr, 0.50), percentile(dscr, 0.90), mean(dscr),
                breachProb, lineStats, rating == null ? null : rating.getFinalGrade(), true, notes);
    }

    /** Nearest-rank percentile over a sample (q in [0,1]); returns 0 for an empty sample. */
    private static double percentile(double[] xs, double q) {
        if (xs.length == 0) return 0.0;
        double[] c = xs.clone();
        java.util.Arrays.sort(c);
        int idx = (int) Math.round(q * (c.length - 1));
        idx = Math.max(0, Math.min(c.length - 1, idx));
        return round4(c[idx]);
    }

    private static double mean(double[] xs) {
        if (xs.length == 0) return 0.0;
        double s = 0;
        for (double v : xs) s += v;
        return round4(s / xs.length);
    }

    private static double round4(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        return Math.round(v * 10000.0) / 10000.0;
    }

    // ============================================================ internals

    private ProjectionView build(String reference, Projection proj, Template t, CreditInputsDto in) {
        Map<String, Double> drivers = effectiveDrivers(t, proj);
        Map<String, Double> base = baseActuals(t, in);
        List<ProjectionYear> years = compute(t, drivers, base);

        List<DriverView> driverViews = new ArrayList<>();
        for (var d : t.drivers()) {
            driverViews.add(new DriverView(d.key(), d.label(),
                    drivers.getOrDefault(d.key(), d.defaultValue()), d.defaultValue()));
        }
        List<String> lineKeys = new ArrayList<>();
        List<String> lineLabels = new ArrayList<>();
        for (var l : t.lines()) { lineKeys.add(l.key()); lineLabels.add(l.label()); }

        Rating rating = ratings.findFirstByApplicationReferenceOrderByCreatedAtDesc(reference).orElse(null);
        return new ProjectionView(reference, t.templateKey(), t.version(), t.horizonYears(),
                proj.getStatus(), true, driverViews, lineKeys, lineLabels, base, years,
                rating == null ? null : rating.getFinalGrade(), true, proj.getConfirmedBy());
    }

    private Map<String, Double> effectiveDrivers(Template t, Projection proj) {
        Map<String, Double> m = ProjectionTemplateClient.defaults(t);
        if (proj.getDrivers() != null) {
            for (var e : proj.getDrivers().entrySet()) {
                if (e.getValue() instanceof Number n) m.put(e.getKey(), n.doubleValue());
                else if (e.getValue() != null) {
                    try { m.put(e.getKey(), Double.parseDouble(String.valueOf(e.getValue()))); }
                    catch (Exception ignored) { }
                }
            }
        }
        return m;
    }

    /** Base-year actuals keyed by LINE key (seedFrom resolves to the spread actual; default 0). */
    private Map<String, Double> baseActuals(Template t, CreditInputsDto in) {
        Map<String, Double> latest = in.latestFinancials() == null ? Map.of() : in.latestFinancials();
        Map<String, Double> base = new LinkedHashMap<>();
        for (var l : t.lines()) {
            String src = l.seedFrom() == null ? l.key() : l.seedFrom();
            base.put(l.key(), latest.getOrDefault(src, 0.0));
        }
        return base;
    }

    /** The deterministic year-by-year compute. */
    private List<ProjectionYear> compute(Template t, Map<String, Double> drivers, Map<String, Double> base) {
        List<ProjectionYear> years = new ArrayList<>();
        Map<String, Double> prev = new LinkedHashMap<>(base);   // year-1 prev == base
        for (int y = 1; y <= t.horizonYears(); y++) {
            Map<String, Double> vars = new LinkedHashMap<>(drivers);
            for (var e : base.entrySet()) vars.put("base_" + e.getKey(), e.getValue());
            for (var e : prev.entrySet()) vars.put("prev_" + e.getKey(), e.getValue());
            Map<String, Double> yv = new LinkedHashMap<>();
            for (var l : t.lines()) {
                double v = FormulaEvaluator.eval(l.formula(), vars);
                v = round2(v);
                vars.put(l.key(), v);   // available to later lines this year
                yv.put(l.key(), v);
            }
            years.add(new ProjectionYear(y, yv));
            prev = yv;
        }
        return years;
    }

    private double finalDscr(List<ProjectionYear> grid) {
        if (grid.isEmpty()) return 0.0;
        Double d = grid.get(grid.size() - 1).values().get("DSCR");
        return d == null ? 0.0 : d;
    }

    private static double round2(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        return Math.round(v * 100.0) / 100.0;
    }
}
