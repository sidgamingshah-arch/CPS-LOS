package com.helix.portfolio.service;

import com.helix.portfolio.client.PortfolioUpstreamClient;
import com.helix.portfolio.client.PortfolioUpstreamClient.RulePackDto;
import com.helix.portfolio.dto.Dtos.ConcentrationDimension;
import com.helix.portfolio.dto.Dtos.ConcentrationLine;
import com.helix.portfolio.dto.Dtos.MultiDimConcentrationView;
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

    @Transactional(readOnly = true)
    public MultiDimConcentrationView concentration(String jurisdiction) {
        List<ExposureRecord> all = exposures.findAll();
        RulePackDto pack = upstream.pack(jurisdiction, "CONCENTRATION_LIMITS", fallbackPack());
        double capitalBase = pack.number("capital_base", 50_000_000_000d);
        Map<String, Object> dims = pack.map("dimensions");

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
            int dimBreaches = 0;
            for (Map.Entry<String, Double> en : agg.entrySet()) {
                double exp = en.getValue();
                double share = total > 0 ? exp / total : 0;
                sumSq += share * share;
                topShare = Math.max(topShare, share);
                double utilisation = limitAmount > 0 ? exp / limitAmount : 0;
                boolean breach = exp > limitAmount + 1e-6;
                if (breach) {
                    dimBreaches++;
                    breaches.add("%s: %s at %.0f exceeds limit %.0f (%.0f%% of basis)".formatted(
                            d.name(), en.getKey(), exp, limitAmount, limitPct * 100));
                }
                lines.add(new ConcentrationLine(en.getKey(), en.getKey(), round(exp), round4(share),
                        limitPct, round(limitAmount), round4(utilisation), breach));
            }
            lines.sort((a, b) -> Double.compare(b.exposure(), a.exposure()));
            totalBreaches += dimBreaches;
            out.add(new ConcentrationDimension(d.name(), basis, limitPct, round(limitAmount),
                    round4(sumSq), agg.size(), round4(topShare), dimBreaches, lines));
        }

        return new MultiDimConcentrationView(jurisdiction, round(total), capitalBase,
                out.size(), totalBreaches, out, breaches);
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
