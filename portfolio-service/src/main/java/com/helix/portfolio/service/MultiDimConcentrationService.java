package com.helix.portfolio.service;

import com.helix.portfolio.client.PortfolioUpstreamClient;
import com.helix.portfolio.client.PortfolioUpstreamClient.RulePackDto;
import com.helix.portfolio.dto.Dtos.BandCounts;
import com.helix.portfolio.dto.Dtos.ConcentrationDimension;
import com.helix.portfolio.dto.Dtos.ConcentrationLine;
import com.helix.portfolio.dto.Dtos.ConcentrationStressView;
import com.helix.portfolio.dto.Dtos.MultiDimConcentrationView;
import com.helix.portfolio.dto.Dtos.SectorStressRow;
import com.helix.portfolio.entity.ExposureRecord;
import com.helix.portfolio.repo.ExposureRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Multi-dimensional portfolio concentration. The legacy view cut three fixed
 * dimensions (single-name / sector / segment); this engine cuts a configurable
 * catalogue — single-name, counterparty-group, sector, geography, instrument,
 * duration bucket, rating, currency — plus cross-dimensional intersections
 * (sector × geography, rating × sector), each with its own limit-pack threshold.
 *
 * <p>Per dimension it reports every bucket's share + limit utilisation + breach,
 * the Herfindahl–Hirschman Index (HHI) as a single concentration-quality number,
 * and the top-bucket share. Thresholds come from the {@code CONCENTRATION_LIMITS}
 * rule pack (jurisdiction-scoped), so a new regime is data, not code.</p>
 */
@Service
public class MultiDimConcentrationService {

    private final ExposureRecordRepository exposures;
    private final PortfolioUpstreamClient upstream;

    public MultiDimConcentrationService(ExposureRecordRepository exposures, PortfolioUpstreamClient upstream) {
        this.exposures = exposures;
        this.upstream = upstream;
    }

    /** A dimension definition: how to key an exposure, and which limit basis applies. */
    private record DimDef(String name, Function<ExposureRecord, String> key, String defaultBasis,
                          double defaultLimitPct) {
    }

    private List<DimDef> catalogue() {
        return List.of(
                new DimDef("SINGLE_NAME", e -> safe(e.getCounterpartyName()), "CAPITAL", 0.15),
                new DimDef("GROUP", e -> safe(e.getGroupRef()), "CAPITAL", 0.25),
                new DimDef("SECTOR", e -> safe(e.getSector()), "PORTFOLIO", 0.20),
                new DimDef("GEOGRAPHY", e -> safe(e.getJurisdiction()), "PORTFOLIO", 0.40),
                new DimDef("INSTRUMENT", e -> safe(e.getFacilityType()), "PORTFOLIO", 0.50),
                new DimDef("DURATION_BUCKET", e -> durationBucket(e.getTenorMonths()), "PORTFOLIO", 0.45),
                new DimDef("RATING", e -> safe(e.getFinalGrade()), "PORTFOLIO", 0.35),
                new DimDef("CURRENCY", e -> safe(e.getCurrency()), "PORTFOLIO", 0.60),
                // intersections — the cells a CRO actually worries about
                new DimDef("SECTOR_x_GEOGRAPHY", e -> safe(e.getSector()) + " / " + safe(e.getJurisdiction()),
                        "PORTFOLIO", 0.15),
                new DimDef("RATING_x_SECTOR", e -> safe(e.getFinalGrade()) + " / " + safe(e.getSector()),
                        "PORTFOLIO", 0.12));
    }

    /**
     * Computes the multi-dimensional cut. By default the book is <em>scoped to the
     * named jurisdiction</em> — the local regulatory view: that regime's limit pack
     * applied to that regime's exposures, and no cross-jurisdiction counterparty
     * names leak into the dimension labels. {@code global=true} is the group-CRO
     * view: the whole book cut with the named jurisdiction's thresholds (this is
     * where the GEOGRAPHY and SECTOR_x_GEOGRAPHY dimensions earn their keep).
     */
    @Transactional(readOnly = true)
    public MultiDimConcentrationView concentration(String jurisdiction, boolean global) {
        List<ExposureRecord> all = global || jurisdiction == null || jurisdiction.isBlank()
                ? exposures.findAll()
                : exposures.findByJurisdiction(jurisdiction);
        RulePackDto pack = upstream.pack(jurisdiction, "CONCENTRATION_LIMITS", fallbackPack());
        double capitalBase = pack.number("capital_base", 50_000_000_000d);
        Map<String, Object> dims = pack.map("dimensions");
        double watchPct = pack.number("watch_pct", 0.80);
        double warningPct = pack.number("warning_pct", 0.90);

        double total = all.stream().mapToDouble(ExposureRecord::getEad).sum();

        List<ConcentrationDimension> out = new ArrayList<>();
        List<String> breaches = new ArrayList<>();
        int totalBreaches = 0;

        for (DimDef d : catalogue()) {
            Map<String, Object> cfg = subMap(dims, d.name());
            String basis = cfg.containsKey("basis") ? String.valueOf(cfg.get("basis")) : d.defaultBasis();
            double limitPct = cfg.containsKey("limitPct") ? num(cfg.get("limitPct"), d.defaultLimitPct())
                                                          : d.defaultLimitPct();
            double base = "CAPITAL".equalsIgnoreCase(basis) ? capitalBase : total;
            double limitAmount = base * limitPct;

            Map<String, Double> agg = aggregate(all, d.key());
            List<ConcentrationLine> lines = new ArrayList<>();
            double sumSq = 0;     // for HHI
            double topShare = 0;
            int dimBreaches = 0, nNormal = 0, nWatch = 0, nWarning = 0;
            for (Map.Entry<String, Double> en : agg.entrySet()) {
                double exp = en.getValue();
                double share = total > 0 ? exp / total : 0;
                sumSq += share * share;
                topShare = Math.max(topShare, share);
                double utilisation = limitAmount > 0 ? exp / limitAmount : 0;
                boolean breach = exp > limitAmount + 1e-6;
                String band = bandFor(utilisation, watchPct, warningPct);
                switch (band) {
                    case "BREACH" -> dimBreaches++;
                    case "WARNING" -> nWarning++;
                    case "WATCH" -> nWatch++;
                    default -> nNormal++;
                }
                if (breach) {
                    breaches.add("%s: %s at %.0f exceeds limit %.0f (%.0f%% of basis)".formatted(
                            d.name(), en.getKey(), exp, limitAmount, limitPct * 100));
                } else if ("WARNING".equals(band)) {
                    breaches.add("%s: %s at %.0f%% of limit — WARNING band (approaching %.0f%% cap)".formatted(
                            d.name(), en.getKey(), utilisation * 100, limitPct * 100));
                }
                lines.add(new ConcentrationLine(en.getKey(), en.getKey(), round(exp), round4(share),
                        limitPct, round(limitAmount), round4(utilisation), breach, band));
            }
            lines.sort((a, b) -> Double.compare(b.exposure(), a.exposure()));
            totalBreaches += dimBreaches;
            out.add(new ConcentrationDimension(d.name(), basis, limitPct, round(limitAmount),
                    round4(sumSq), agg.size(), round4(topShare), dimBreaches,
                    new BandCounts(nNormal, nWatch, nWarning, dimBreaches), lines));
        }

        return new MultiDimConcentrationView(jurisdiction, round(total), capitalBase,
                out.size(), totalBreaches, out, breaches);
    }

    /** Early-warning band from utilisation: NORMAL < watch ≤ WATCH < warning ≤ WARNING < 1.0 ≤ BREACH. */
    private static String bandFor(double utilisation, double watchPct, double warningPct) {
        if (utilisation > 1.0 + 1e-6) return "BREACH";
        if (utilisation >= warningPct) return "WARNING";
        if (utilisation >= watchPct) return "WATCH";
        return "NORMAL";
    }

    // ============================================================ correlation stress

    /**
     * Correlation-stressed concentration. A macro shock to one sector ({@code pdMultiplier}×
     * its PD) propagates through the correlation matrix to every co-moving sector, and the
     * stressed expected loss is rolled up against the capital buffer. This surfaces the
     * concentration that name-level diversification hides: a book that looks spread across
     * sectors can carry one correlated bet that a single downturn detonates.
     *
     * <p>Per exposure: {@code stressedPd = clamp(basePd × (1 + (mult − 1) × ρ), 0, 1)} where
     * ρ is the correlation of the exposure's sector to the shocked sector (1.0 for the shocked
     * sector itself). Expected loss is {@code EAD × PD × LGD} — deterministic figure path.</p>
     */
    @Transactional(readOnly = true)
    public ConcentrationStressView stress(String jurisdiction, String shockedSector, Double pdMultiplier,
                                          Double capitalBufferPct, Map<String, Double> correlationOverrides,
                                          boolean global) {
        if (shockedSector == null || shockedSector.isBlank()) {
            throw com.helix.common.web.ApiException.badRequest("shockedSector is required");
        }
        String shocked = shockedSector.toUpperCase();
        double mult = pdMultiplier == null ? 3.0 : Math.max(1.0, pdMultiplier);

        List<ExposureRecord> all = global || jurisdiction == null || jurisdiction.isBlank()
                ? exposures.findAll()
                : exposures.findByJurisdiction(jurisdiction);
        RulePackDto pack = upstream.pack(jurisdiction, "CONCENTRATION_LIMITS", fallbackPack());
        double capitalBase = pack.number("capital_base", 50_000_000_000d);
        double bufferPct = capitalBufferPct == null ? pack.number("capital_buffer_pct", 0.10) : capitalBufferPct;
        double capitalBuffer = capitalBase * bufferPct;
        Map<String, Double> corrRow = correlationRow(pack, shocked, correlationOverrides);

        double total = all.stream().mapToDouble(ExposureRecord::getEad).sum();

        // Per-sector roll-up of base / stressed expected loss.
        record Acc(double exposure, double baseEl, double stressedEl, double pdWeighted, double stressedPdWeighted) { }
        Map<String, double[]> bySector = new LinkedHashMap<>();   // [exposure, baseEl, stressedEl, pd*ead, spd*ead]
        double baseElTotal = 0, stressedElTotal = 0;
        for (ExposureRecord e : all) {
            String sector = safe(e.getSector()).toUpperCase();
            double rho = sector.equals(shocked) ? 1.0 : corrRow.getOrDefault(sector, 0.0);
            double stressedPd = Math.min(1.0, Math.max(0.0, e.getPd() * (1.0 + (mult - 1.0) * rho)));
            double baseEl = e.getEad() * e.getPd() * e.getLgd();
            double stressedEl = e.getEad() * stressedPd * e.getLgd();
            baseElTotal += baseEl;
            stressedElTotal += stressedEl;
            double[] a = bySector.computeIfAbsent(sector, k -> new double[5]);
            a[0] += e.getEad();
            a[1] += baseEl;
            a[2] += stressedEl;
            a[3] += e.getPd() * e.getEad();
            a[4] += stressedPd * e.getEad();
        }

        List<SectorStressRow> sectors = new ArrayList<>();
        for (Map.Entry<String, double[]> en : bySector.entrySet()) {
            double[] a = en.getValue();
            double exp = a[0];
            double rho = en.getKey().equals(shocked) ? 1.0 : corrRow.getOrDefault(en.getKey(), 0.0);
            sectors.add(new SectorStressRow(en.getKey(), round(exp), round4(total > 0 ? exp / total : 0),
                    round4(rho), round4(exp > 0 ? a[3] / exp : 0), round4(exp > 0 ? a[4] / exp : 0),
                    round(a[1]), round(a[2]), round(a[2] - a[1])));
        }
        sectors.sort((x, y) -> Double.compare(y.incrementalLoss(), x.incrementalLoss()));

        double incremental = stressedElTotal - baseElTotal;
        boolean capitalBreach = stressedElTotal > capitalBuffer + 1e-6;
        List<String> alerts = new ArrayList<>();
        if (capitalBreach) {
            alerts.add("Stressed expected loss %.0f exceeds the capital buffer %.0f (%.0f%% of capital base) — a %s shock alone would impair capital"
                    .formatted(stressedElTotal, capitalBuffer, bufferPct * 100, shocked));
        }
        // Flag any sector whose stressed loss more than doubles — the correlated cluster.
        for (SectorStressRow r : sectors) {
            if (r.baseExpectedLoss() > 0 && r.stressedExpectedLoss() > 2.0 * r.baseExpectedLoss()
                    && !r.sector().equals(shocked)) {
                alerts.add("%s stressed loss %.1f× base via %.0f%% correlation to %s — hidden correlated exposure"
                        .formatted(r.sector(), r.stressedExpectedLoss() / r.baseExpectedLoss(),
                                r.correlationToShock() * 100, shocked));
            }
        }

        return new ConcentrationStressView(jurisdiction, shocked, mult, capitalBase, round(total),
                round(baseElTotal), round(stressedElTotal), round(incremental),
                round4(capitalBase > 0 ? stressedElTotal / capitalBase : 0),
                capitalBreach, sectors, alerts);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Double> correlationRow(RulePackDto pack, String shocked,
                                               Map<String, Double> overrides) {
        Map<String, Double> row = new LinkedHashMap<>();
        Object corr = pack.payload() == null ? null : pack.payload().get("correlations");
        if (corr instanceof Map<?, ?> matrix) {
            Object r = ((Map<String, Object>) matrix).get(shocked);
            if (r instanceof Map<?, ?> rm) {
                for (Map.Entry<?, ?> en : rm.entrySet()) {
                    row.put(String.valueOf(en.getKey()).toUpperCase(), num(en.getValue(), 0.0));
                }
            }
        }
        if (row.isEmpty()) {
            // Conservative fallback matrix — moderate cross-sector correlation so the
            // stress still demonstrates propagation when the pack omits the block.
            for (String s : List.of("MANUFACTURING", "INFRASTRUCTURE", "CONSTRUCTION", "STEEL",
                    "LOGISTICS", "RETAIL", "TRADE", "REAL_ESTATE", "POWER")) {
                if (!s.equals(shocked)) row.put(s, 0.4);
            }
        }
        if (overrides != null) {
            overrides.forEach((k, v) -> row.put(k.toUpperCase(), v));
        }
        return row;
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Double> aggregate(List<ExposureRecord> all, Function<ExposureRecord, String> key) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (ExposureRecord e : all) {
            String k = key.apply(e);
            m.merge(k == null || k.isBlank() ? "UNKNOWN" : k, e.getEad(), Double::sum);
        }
        return m;
    }

    private static String durationBucket(Integer tenorMonths) {
        if (tenorMonths == null) return "UNKNOWN";
        int t = tenorMonths;
        if (t <= 12) return "0-12m";
        if (t <= 36) return "12-36m";
        if (t <= 60) return "36-60m";
        if (t <= 120) return "60-120m";
        return "120m+";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> subMap(Map<String, Object> parent, String key) {
        if (parent == null) return Map.of();
        Object v = parent.get(key);
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static String safe(String s) {
        return s == null || s.isBlank() ? "UNKNOWN" : s;
    }

    private static double num(Object o, double dflt) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s) try { return Double.parseDouble(s); } catch (Exception ignored) { }
        return dflt;
    }

    private RulePackDto fallbackPack() {
        Map<String, Object> dims = new LinkedHashMap<>();
        dims.put("SINGLE_NAME", Map.of("basis", "CAPITAL", "limitPct", 0.15));
        dims.put("GROUP", Map.of("basis", "CAPITAL", "limitPct", 0.25));
        dims.put("SECTOR", Map.of("basis", "PORTFOLIO", "limitPct", 0.20));
        dims.put("GEOGRAPHY", Map.of("basis", "PORTFOLIO", "limitPct", 0.40));
        dims.put("INSTRUMENT", Map.of("basis", "PORTFOLIO", "limitPct", 0.50));
        dims.put("DURATION_BUCKET", Map.of("basis", "PORTFOLIO", "limitPct", 0.45));
        dims.put("RATING", Map.of("basis", "PORTFOLIO", "limitPct", 0.35));
        dims.put("CURRENCY", Map.of("basis", "PORTFOLIO", "limitPct", 0.60));
        dims.put("SECTOR_x_GEOGRAPHY", Map.of("basis", "PORTFOLIO", "limitPct", 0.15));
        dims.put("RATING_x_SECTOR", Map.of("basis", "PORTFOLIO", "limitPct", 0.12));
        return new RulePackDto("CONCENTRATION_LIMITS_FALLBACK", 0,
                Map.of("capital_base", 50_000_000_000d, "dimensions", dims));
    }

    private static double round(double v) { return Math.round(v * 100.0) / 100.0; }
    private static double round4(double v) { return Math.round(v * 1_000_000.0) / 1_000_000.0; }
}
