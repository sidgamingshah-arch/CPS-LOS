package com.helix.origination.service;

import java.util.LinkedHashMap;
import java.util.Map;

/** Standard credit ratio set computed from canonical financials (PRD §4, US-4.3). */
public final class Ratios {

    private Ratios() {
    }

    public static Map<String, Double> compute(Map<String, Double> f) {
        Map<String, Double> r = new LinkedHashMap<>();
        double revenue = f.getOrDefault("REVENUE", 0.0);
        double ebitda = f.getOrDefault("EBITDA", 0.0);
        double ebit = f.getOrDefault("EBIT", 0.0);
        double pat = f.getOrDefault("PAT", 0.0);
        double totalDebt = f.getOrDefault("TOTAL_DEBT", 0.0);
        double cash = f.getOrDefault("CASH", 0.0);
        double interest = f.getOrDefault("INTEREST_EXPENSE", 0.0);
        double debtService = f.getOrDefault("DEBT_SERVICE", 0.0);
        double cfo = f.getOrDefault("CFO", 0.0);
        double netWorth = f.getOrDefault("NET_WORTH", 0.0);
        double currentAssets = f.getOrDefault("CURRENT_ASSETS", 0.0);
        double currentLiab = f.getOrDefault("CURRENT_LIABILITIES", 0.0);

        r.put("EBITDA_MARGIN", round(safeDiv(ebitda, revenue, 0.0)));
        r.put("NET_MARGIN", round(safeDiv(pat, revenue, 0.0)));
        r.put("GROSS_LEVERAGE", round(safeDiv(totalDebt, ebitda, 0.0)));
        r.put("NET_LEVERAGE", round(safeDiv(totalDebt - cash, ebitda, 0.0)));
        r.put("INTEREST_COVERAGE", round(coverage(ebit, interest)));
        r.put("DSCR", round(coverage(cfo > 0 ? cfo : ebitda, debtService)));
        r.put("CURRENT_RATIO", round(safeDiv(currentAssets, currentLiab, 0.0)));
        r.put("GEARING", round(safeDiv(totalDebt, netWorth, 0.0)));
        r.put("RETURN_ON_EQUITY", round(safeDiv(pat, netWorth, 0.0)));
        return r;
    }

    private static double coverage(double num, double den) {
        if (den <= 0.0001) {
            return num > 0 ? 99.0 : 0.0;   // no debt service / interest => effectively uncapped
        }
        return num / den;
    }

    private static double safeDiv(double num, double den, double fallback) {
        return Math.abs(den) < 0.0001 ? fallback : num / den;
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
