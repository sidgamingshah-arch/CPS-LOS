package com.helix.risk.client;

import com.helix.risk.dto.RulePackDto;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Conservative built-in rule packs used only when config-service is unreachable.
 * Marked version 0 so consumers can see a fallback was used in the audit trace.
 */
final class DefaultRulePacks {

    private DefaultRulePacks() {
    }

    static RulePackDto fallback(String jurisdiction, String type) {
        Map<String, Object> payload = switch (type) {
            case "CAPITAL_SA" -> capitalSa();
            case "ECRA_MAPPING" -> ecra();
            case "RATING_PD_MAP" -> pdMap();
            case "LGD_MAP" -> lgdMap();
            case "PRICING" -> pricing();
            default -> Map.of();
        };
        return new RulePackDto(0L, "fallback_" + type.toLowerCase(), type, jurisdiction, 0, payload);
    }

    private static Map<String, Object> capitalSa() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("unrated_risk_weights", Map.of(
                "SOVEREIGN", 0.0, "BANK", 0.5, "CORPORATE", 1.0, "SME_CORPORATE", 0.85,
                "SPECIALISED_LENDING", 1.0, "EQUITY", 2.5, "OTHER", 1.0));
        m.put("below_investment_grade_corporate_rw", 1.5);
        m.put("slotting_risk_weights", Map.of(
                "STRONG", 0.7, "GOOD", 0.9, "SATISFACTORY", 1.15, "WEAK", 2.5, "DEFAULT", 0.0));
        m.put("ccf_undrawn_commitment", 0.4);
        m.put("ccf_trade_contingent", 0.2);
        m.put("ccf_direct_credit_substitute", 1.0);
        m.put("crm_collateral_haircuts", Map.of(
                "CASH", 0.0, "GOVT_SECURITIES", 0.02, "EQUITY_LISTED", 0.25, "PROPERTY", 0.4, "RECEIVABLES", 0.5));
        m.put("due_diligence_uplift", 0.25);
        m.put("capital_ratio_min", 0.09);
        return m;
    }

    private static Map<String, Object> ecra() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("corporate", Map.of("AAA_AA", 0.2, "A", 0.5, "BBB", 1.0, "BB", 1.0, "B_AND_BELOW", 1.5, "UNRATED", 1.0));
        m.put("bank", Map.of("AAA_AA", 0.2, "A", 0.3, "BBB", 0.5, "BB", 1.0, "B_AND_BELOW", 1.5, "UNRATED", 0.5));
        m.put("internal_grade_to_bucket", Map.of(
                "AAA", "AAA_AA", "AA", "AAA_AA", "A", "A", "BBB", "BBB", "BB", "BB",
                "B", "B_AND_BELOW", "CCC", "B_AND_BELOW", "CC", "B_AND_BELOW", "C", "B_AND_BELOW", "D", "B_AND_BELOW"));
        return m;
    }

    private static Map<String, Object> pdMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("AAA", 0.0003);
        m.put("AA", 0.0005);
        m.put("A", 0.0010);
        m.put("BBB", 0.0030);
        m.put("BB", 0.0100);
        m.put("B", 0.0350);
        m.put("CCC", 0.1200);
        m.put("CC", 0.2500);
        m.put("C", 0.4000);
        m.put("D", 1.0000);
        return m;
    }

    private static Map<String, Object> lgdMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("SENIOR_UNSECURED", 0.45);
        m.put("SENIOR_SECURED", 0.25);
        m.put("SUBORDINATED", 0.75);
        m.put("FULLY_COLLATERALISED", 0.15);
        return m;
    }

    private static Map<String, Object> pricing() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hurdle_raroc", 0.15);
        m.put("cost_of_funds", 0.075);
        m.put("opex_rate", 0.010);
        m.put("target_capital_ratio", 0.12);
        m.put("min_spread", 0.005);
        return m;
    }
}
