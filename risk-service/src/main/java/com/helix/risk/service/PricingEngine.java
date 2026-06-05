package com.helix.risk.service;

import com.helix.risk.dto.RulePackDto;
import com.helix.risk.entity.PricingResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RAROC-based risk-adjusted pricing (PRD §7). Solves for the rate that meets the
 * hurdle given expected loss, capital charge, funding and opex, then reports the
 * resulting RAROC. Advisory only — flagged, never auto-applied.
 */
@Component
public class PricingEngine {

    public PricingResult price(String applicationReference, double pd, double lgd, double rwa, double ead,
                               RulePackDto pricingPack) {
        double hurdle = pricingPack.number("hurdle_raroc", 0.15);
        double costOfFunds = pricingPack.number("cost_of_funds", 0.075);
        double opexRate = pricingPack.number("opex_rate", 0.010);
        double targetCapitalRatio = pricingPack.number("target_capital_ratio", 0.12);
        double minSpread = pricingPack.number("min_spread", 0.005);

        double expectedLoss = pd * lgd * ead;
        double capital = rwa * targetCapitalRatio;
        double cofAmount = ead * costOfFunds;
        double opexAmount = ead * opexRate;

        // Required income so that (income - EL - opex - CoF) / capital >= hurdle.
        double requiredIncome = hurdle * capital + expectedLoss + opexAmount + cofAmount;
        double requiredRate = ead > 0 ? requiredIncome / ead : 0.0;
        double recommendedRate = Math.max(requiredRate, costOfFunds + minSpread);

        double income = recommendedRate * ead;
        double raroc = capital > 0 ? (income - expectedLoss - opexAmount - cofAmount) / capital : 0.0;
        boolean belowHurdle = raroc < hurdle - 1e-9;

        PricingResult r = new PricingResult();
        r.setApplicationReference(applicationReference);
        r.setEad(round(ead));
        r.setExpectedLoss(round(expectedLoss));
        r.setCapitalCharge(round(capital));
        r.setCostOfFundsAmount(round(cofAmount));
        r.setOpexAmount(round(opexAmount));
        r.setRecommendedRate(round4(recommendedRate));
        r.setRaroc(round4(raroc));
        r.setHurdleRaroc(hurdle);
        r.setBelowHurdle(belowHurdle);

        Map<String, Object> breakdown = new LinkedHashMap<>();
        breakdown.put("pd", pd);
        breakdown.put("lgd", lgd);
        breakdown.put("ead", round(ead));
        breakdown.put("expectedLoss", round(expectedLoss));
        breakdown.put("capitalCharge", round(capital));
        breakdown.put("costOfFunds", costOfFunds);
        breakdown.put("costOfFundsAmount", round(cofAmount));
        breakdown.put("opexRate", opexRate);
        breakdown.put("opexAmount", round(opexAmount));
        breakdown.put("requiredRate", round4(requiredRate));
        breakdown.put("recommendedRate", round4(recommendedRate));
        breakdown.put("raroc", round4(raroc));
        breakdown.put("hurdleRaroc", hurdle);
        breakdown.put("pricingPack", pricingPack.code() + " v" + pricingPack.version());
        r.setBreakdown(breakdown);
        return r;
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private double round4(double v) {
        return Math.round(v * 1_000_000.0) / 1_000_000.0;
    }
}
