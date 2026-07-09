package com.helix.risk.service;

import com.helix.risk.client.RiskMasterClient;
import com.helix.risk.client.RiskMasterClient.MasterRecordDto;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Funds-transfer-pricing (FTP) engine — replaces the flat {@code cost_of_funds}
 * number with a <b>term-structured, behaviourally-adjusted</b> transfer rate read
 * from the {@code FTP_CURVE} master (currency-keyed, jurisdiction-overridable).
 *
 * <p>Two adjustments make this realistic versus a single funding number:
 * <ol>
 *   <li><b>Term structure</b> — the curve is a set of (tenor, rate) points; the FTP
 *       is interpolated at the facility's <i>behavioural</i> maturity, then a term
 *       liquidity premium that grows with maturity is added.</li>
 *   <li><b>Behavioural life</b> — a revolving/demand product (WC, OD) does not fund
 *       at its contractual tenor; it prices off a short behavioural maturity. An
 *       amortising product (TL, PF) funds at its weighted-average life, a fraction
 *       of the contractual tenor. Both come from the master's {@code behavioural}
 *       map per facility type.</li>
 * </ol>
 *
 * <p>Deterministic, explainable, and graceful: if the {@code FTP_CURVE} master is
 * absent or unreadable, {@link #computeFtp} returns the supplied flat fallback so
 * the pricing path keeps working.</p>
 */
@Service
public class FtpService {

    private final RiskMasterClient masters;

    public FtpService(RiskMasterClient masters) {
        this.masters = masters;
    }

    /** The resolved FTP plus a full, audit-ready breakdown of how it was derived. */
    public record FtpResult(double ftp, boolean fromCurve, int contractualMonths, double behaviouralMonths,
                            String behaviourType, double baseCurveRate, double liquidityPremium,
                            Map<String, Object> breakdown) {
    }

    /**
     * @param currency        facility currency (curve is currency-keyed)
     * @param jurisdiction    used to prefer a jurisdiction-specific curve, if present
     * @param facilityType    drives the behavioural-life adjustment
     * @param tenorMonths     contractual tenor
     * @param flatFallback    the PRICING pack's {@code cost_of_funds} — used if no curve
     */
    public FtpResult computeFtp(String currency, String jurisdiction, String facilityType,
                                int tenorMonths, double flatFallback) {
        MasterRecordDto curve = pickCurve(currency, jurisdiction);
        if (curve == null || curve.payload() == null) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("source", "FLAT_FALLBACK");
            b.put("reason", "no FTP_CURVE master for " + currency);
            b.put("ftp", flatFallback);
            return new FtpResult(flatFallback, false, tenorMonths, tenorMonths, "FLAT",
                    flatFallback, 0.0, b);
        }
        Map<String, Object> payload = curve.payload();

        // 1. Behavioural maturity.
        BehaviourResolution beh = resolveBehaviour(payload, facilityType, tenorMonths);

        // 2. Interpolate the funding curve at the behavioural maturity.
        List<double[]> points = parsePoints(payload.get("tenorPoints"));
        double baseRate = points.isEmpty() ? flatFallback : interpolate(points, beh.behaviouralMonths);

        // 3. Term liquidity premium (bps per year of behavioural life).
        double lpBpsPerYear = num(payload.get("liquidityPremiumBpsPerYear"), 0.0);
        double liquidityPremium = (lpBpsPerYear * (beh.behaviouralMonths / 12.0)) / 10_000.0;

        double ftp = baseRate + liquidityPremium;

        Map<String, Object> b = new LinkedHashMap<>();
        b.put("source", "FTP_CURVE");
        b.put("curveKey", curve.recordKey());
        b.put("curveJurisdiction", curve.jurisdiction());
        b.put("currency", currency);
        b.put("facilityType", facilityType);
        b.put("contractualMonths", tenorMonths);
        b.put("behaviouralMonths", round2(beh.behaviouralMonths));
        b.put("behaviourType", beh.type);
        b.put("baseCurveRate", round6(baseRate));
        b.put("liquidityPremiumBpsPerYear", lpBpsPerYear);
        b.put("liquidityPremium", round6(liquidityPremium));
        b.put("ftp", round6(ftp));
        return new FtpResult(ftp, true, tenorMonths, beh.behaviouralMonths, beh.type,
                baseRate, liquidityPremium, b);
    }

    // ------------------------------------------------------------------ curve selection

    private MasterRecordDto pickCurve(String currency, String jurisdiction) {
        if (currency == null) return null;
        List<MasterRecordDto> all = masters.listActive("FTP_CURVE");
        MasterRecordDto defaultCurve = null;
        for (MasterRecordDto m : all) {
            if (!currency.equalsIgnoreCase(m.recordKey())) continue;
            String j = m.jurisdiction();
            if (jurisdiction != null && jurisdiction.equals(j)) return m;   // jurisdiction override wins
            if (j == null || j.isBlank()) defaultCurve = m;
        }
        return defaultCurve;
    }

    // ------------------------------------------------------------------ behavioural life

    private record BehaviourResolution(double behaviouralMonths, String type) { }

    @SuppressWarnings("unchecked")
    private BehaviourResolution resolveBehaviour(Map<String, Object> payload, String facilityType, int tenorMonths) {
        Object behObj = payload.get("behavioural");
        if (behObj instanceof Map<?, ?> behMap && facilityType != null) {
            Object cfgObj = behMap.get(facilityType);
            if (cfgObj instanceof Map<?, ?> cfg) {
                String type = cfg.get("type") == null ? "TERM" : String.valueOf(cfg.get("type"));
                if (cfg.get("behaviouralMonths") != null) {
                    // Revolving / demand: price off a fixed short behavioural maturity.
                    return new BehaviourResolution(num(cfg.get("behaviouralMonths"), tenorMonths), type);
                }
                if (cfg.get("lifeFactor") != null) {
                    // Amortising: weighted-average life = contractual × factor.
                    double factor = num(cfg.get("lifeFactor"), 1.0);
                    return new BehaviourResolution(Math.max(1.0, tenorMonths * factor), type);
                }
            }
        }
        return new BehaviourResolution(Math.max(1, tenorMonths), "CONTRACTUAL");
    }

    // ------------------------------------------------------------------ curve maths

    private List<double[]> parsePoints(Object raw) {
        List<double[]> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return out;
        for (Object o : list) {
            if (o instanceof Map<?, ?> m && m.get("months") != null && m.get("rate") != null) {
                out.add(new double[]{ num(m.get("months"), 0), num(m.get("rate"), 0) });
            }
        }
        out.sort((a, b) -> Double.compare(a[0], b[0]));
        return out;
    }

    /** Linear interpolation, clamped flat beyond the first/last point. */
    private double interpolate(List<double[]> points, double months) {
        if (points.isEmpty()) return 0.0;
        if (months <= points.get(0)[0]) return points.get(0)[1];
        if (months >= points.get(points.size() - 1)[0]) return points.get(points.size() - 1)[1];
        for (int i = 1; i < points.size(); i++) {
            double[] lo = points.get(i - 1);
            double[] hi = points.get(i);
            if (months <= hi[0]) {
                double w = (months - lo[0]) / (hi[0] - lo[0]);
                return lo[1] + w * (hi[1] - lo[1]);
            }
        }
        return points.get(points.size() - 1)[1];
    }

    private static double num(Object o, double dflt) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s) try { return Double.parseDouble(s); } catch (Exception ignored) { }
        return dflt;
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private static double round6(double v) { return Math.round(v * 1_000_000.0) / 1_000_000.0; }
}
