package com.helix.origination.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The single canonical chart of accounts onto which all GAAPs are mapped (PRD §4/§7).
 * Input lines are extracted/entered; derived lines are computed deterministically.
 */
public final class CanonicalTaxonomy {

    private CanonicalTaxonomy() {
    }

    public static final Map<String, String> LABELS = labels();

    /** Lines that come from source documents (extracted or entered). */
    public static final List<String> INPUT_LINES = List.of(
            "REVENUE", "COGS", "OPERATING_EXPENSES", "DEPRECIATION", "INTEREST_EXPENSE", "TAX",
            "TOTAL_ASSETS", "CURRENT_ASSETS", "CASH", "CURRENT_LIABILITIES",
            "SHORT_TERM_DEBT", "LONG_TERM_DEBT", "CURRENT_PORTION_LTD", "NET_WORTH", "CFO");

    /** Lines computed from input lines. */
    public static final List<String> DERIVED_LINES = List.of(
            "GROSS_PROFIT", "EBITDA", "EBIT", "PBT", "PAT", "TOTAL_DEBT", "WORKING_CAPITAL", "DEBT_SERVICE");

    private static Map<String, String> labels() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("REVENUE", "Revenue / Turnover");
        m.put("COGS", "Cost of goods sold");
        m.put("OPERATING_EXPENSES", "Operating expenses (ex-D&A)");
        m.put("DEPRECIATION", "Depreciation & amortisation");
        m.put("INTEREST_EXPENSE", "Interest / finance cost");
        m.put("TAX", "Tax expense");
        m.put("TOTAL_ASSETS", "Total assets");
        m.put("CURRENT_ASSETS", "Current assets");
        m.put("CASH", "Cash & equivalents");
        m.put("CURRENT_LIABILITIES", "Current liabilities");
        m.put("SHORT_TERM_DEBT", "Short-term debt");
        m.put("LONG_TERM_DEBT", "Long-term debt");
        m.put("CURRENT_PORTION_LTD", "Current portion of long-term debt");
        m.put("NET_WORTH", "Net worth / equity");
        m.put("CFO", "Cash flow from operations");
        m.put("GROSS_PROFIT", "Gross profit");
        m.put("EBITDA", "EBITDA");
        m.put("EBIT", "EBIT");
        m.put("PBT", "Profit before tax");
        m.put("PAT", "Profit after tax");
        m.put("TOTAL_DEBT", "Total debt");
        m.put("WORKING_CAPITAL", "Net working capital");
        m.put("DEBT_SERVICE", "Annual debt service");
        return m;
    }

    public static String label(String key) {
        return LABELS.getOrDefault(key, key);
    }

    public static boolean isDerived(String key) {
        return DERIVED_LINES.contains(key);
    }

    /** Computes the full set of derived lines from a map of canonical values. */
    public static Map<String, Double> deriveAll(Map<String, Double> v) {
        double grossProfit = g(v, "REVENUE") - g(v, "COGS");
        double ebitda = grossProfit - g(v, "OPERATING_EXPENSES");
        double ebit = ebitda - g(v, "DEPRECIATION");
        double pbt = ebit - g(v, "INTEREST_EXPENSE");
        double pat = pbt - g(v, "TAX");
        double totalDebt = g(v, "SHORT_TERM_DEBT") + g(v, "LONG_TERM_DEBT");
        double workingCapital = g(v, "CURRENT_ASSETS") - g(v, "CURRENT_LIABILITIES");
        double debtService = g(v, "INTEREST_EXPENSE") + g(v, "CURRENT_PORTION_LTD");

        Map<String, Double> d = new LinkedHashMap<>();
        d.put("GROSS_PROFIT", grossProfit);
        d.put("EBITDA", ebitda);
        d.put("EBIT", ebit);
        d.put("PBT", pbt);
        d.put("PAT", pat);
        d.put("TOTAL_DEBT", totalDebt);
        d.put("WORKING_CAPITAL", workingCapital);
        d.put("DEBT_SERVICE", debtService);
        return d;
    }

    private static double g(Map<String, Double> v, String k) {
        return v.getOrDefault(k, 0.0);
    }
}
