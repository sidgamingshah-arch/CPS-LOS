package com.helix.risk.service;

import com.helix.common.audit.AuditService;
import com.helix.risk.client.ConfigClient;
import com.helix.risk.client.OriginationClient;
import com.helix.risk.dto.CreditInputsDto;
import com.helix.risk.dto.OptimiserDtos.OptimisationResult;
import com.helix.risk.dto.OptimiserDtos.OptimiseRequest;
import com.helix.risk.dto.OptimiserDtos.Scenario;
import com.helix.risk.dto.RulePackDto;
import com.helix.risk.entity.CapitalResult;
import com.helix.risk.entity.Rating;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pricing scenario optimiser (PRD: scenario optimiser / goal-seek). Given a target
 * RAROC, solves for the rate, fee or collateral mix needed to clear the target —
 * subject to caps/floors the analyst provides. Advisory: returns scenarios, the
 * recommended path and any constraints that bind; never auto-applies pricing.
 *
 * <p>The math mirrors {@link PricingEngine}: RAROC = (income − EL − opex − CoF) / capital,
 * where income = rate × EAD + feeBps × EAD, EL = PD × LGD × EAD. Reducing LGD via
 * additional collateral cover lowers EL; increasing fees lowers the required rate.
 */
@Service
public class PricingOptimiser {

    private final RiskService risk;
    private final ConfigClient config;
    private final OriginationClient origination;
    private final FtpService ftpService;
    private final AuditService audit;
    private final com.helix.common.governance.AiGovernanceClient governance;

    public PricingOptimiser(RiskService risk, ConfigClient config, OriginationClient origination,
                            FtpService ftpService, AuditService audit,
                            com.helix.common.governance.AiGovernanceClient governance) {
        this.risk = risk;
        this.config = config;
        this.origination = origination;
        this.ftpService = ftpService;
        this.audit = audit;
        this.governance = governance;
    }

    @Transactional
    public OptimisationResult optimise(String reference, OptimiseRequest req, String actor) {
        Rating rating = risk.latestRating(reference);
        CapitalResult capital = risk.latestCapital(reference);
        CreditInputsDto in = origination.creditInputs(reference);
        governance.enforce(com.helix.common.governance.AiCapability.PRICING_OPTIMISER, in.jurisdiction());
        RulePackDto pack = config.activePack(in.jurisdiction(), "PRICING");

        double hurdle = pack.number("hurdle_raroc", 0.15);
        // Same FTP as the deterministic recommended price, so the optimiser baseline matches.
        double costOfFunds = ftpService.computeFtp(in.currency(), in.jurisdiction(),
                in.facilityType(), in.tenorMonths(), pack.number("cost_of_funds", 0.075)).ftp();
        double opexRate = pack.number("opex_rate", 0.010);
        double targetCapitalRatio = pack.number("target_capital_ratio", 0.12);
        double minSpread = pack.number("min_spread", 0.005);

        double target = req.targetRaroc() == null ? hurdle : Math.max(0.0, req.targetRaroc());
        double rateCap = req.rateCap() == null ? 0.18 : req.rateCap();
        double feeCapBps = req.feeBpsCap() == null ? 150.0 : req.feeBpsCap();
        double maxCollat = req.maxCollateralCover() == null ? 0.75 : Math.max(0.0, Math.min(0.95, req.maxCollateralCover()));

        double pd = rating.getPd();
        double baseLgd = rating.getLgd();
        double ead = rating.getEad();
        double rwa = capital.getRwa();
        double capitalCharge = rwa * targetCapitalRatio;
        double cof = ead * costOfFunds;
        double opex = ead * opexRate;

        // Baseline: current LGD, no fee, recommended rate from PricingEngine math.
        Scenario baseline = compute("baseline", pd, baseLgd, ead, capitalCharge, cof, opex,
                requiredRateAt(target, pd, baseLgd, ead, capitalCharge, cof, opex,
                        Math.max(costOfFunds + minSpread, 0.0), 0.0),
                0.0, rateCap, feeCapBps, target);

        // 1. Find the minimum rate that clears the target at current LGD, no fee.
        double rateForTarget = requiredRateAt(target, pd, baseLgd, ead, capitalCharge, cof, opex,
                costOfFunds + minSpread, 0.0);
        Scenario rateOnly = compute("rate-only", pd, baseLgd, ead, capitalCharge, cof, opex,
                rateForTarget, 0.0, rateCap, feeCapBps, target);

        // 2. Cap the rate at rateCap and seek the fee that closes the gap.
        double feeBps = solveFeeBps(target, pd, baseLgd, ead, capitalCharge, cof, opex, rateCap, feeCapBps);
        Scenario feeAssisted = compute("rate-capped + fee", pd, baseLgd, ead, capitalCharge, cof, opex,
                rateCap, feeBps, rateCap, feeCapBps, target);

        // 3. Add collateral cover to drop LGD — re-solve the rate at the lower LGD.
        double coveredLgd = baseLgd * (1.0 - maxCollat);
        double rateAtLowerLgd = requiredRateAt(target, pd, coveredLgd, ead, capitalCharge, cof, opex,
                costOfFunds + minSpread, 0.0);
        Scenario collatAssisted = compute("collateral-assisted", pd, coveredLgd, ead, capitalCharge, cof, opex,
                rateAtLowerLgd, 0.0, rateCap, feeCapBps, target);

        List<Scenario> scenarios = List.of(baseline, rateOnly, feeAssisted, collatAssisted);

        // Pick the simplest scenario that meets the target.
        Scenario recommended = scenarios.stream().filter(Scenario::meetsTarget).findFirst()
                .orElse(scenarios.stream().max((a, b) -> Double.compare(a.raroc(), b.raroc())).orElse(baseline));

        OptimisationResult result = new OptimisationResult(reference, baseline.rate(), baseline.raroc(),
                target, hurdle, recommended.meetsTarget(), recommended, scenarios, true);

        audit.ai("pricing-optimiser", "PRICING_OPTIMISED", "Application", reference,
                "Goal-seek target RAROC %.1f%% → %s (%s) (advisory)".formatted(
                        target * 100,
                        recommended.meetsTarget() ? "achievable" : "shortfall",
                        recommended.name()),
                Map.of("target", target, "achievable", recommended.meetsTarget(),
                        "recommendedScenario", recommended.name(), "advisory", true));
        return result;
    }

    // --------------------------------------------------- math

    /** Required rate so RAROC = target, given fee and LGD. Floored at the rate floor. */
    private double requiredRateAt(double target, double pd, double lgd, double ead, double capital,
                                  double cof, double opex, double rateFloor, double feeBps) {
        double el = pd * lgd * ead;
        double feeIncome = ead * (feeBps / 10_000.0);
        double requiredIncome = target * capital + el + opex + cof - feeIncome;
        double rate = ead > 0 ? requiredIncome / ead : rateFloor;
        return Math.max(rate, rateFloor);
    }

    /** Solve for fee (bps) needed to hit the target at a capped rate. Capped at feeCapBps. */
    private double solveFeeBps(double target, double pd, double lgd, double ead, double capital,
                               double cof, double opex, double rate, double feeCapBps) {
        double el = pd * lgd * ead;
        double income = rate * ead;
        double shortfall = target * capital + el + opex + cof - income;
        if (shortfall <= 0) return 0.0;
        double feeBps = ead > 0 ? (shortfall / ead) * 10_000.0 : 0.0;
        return Math.min(feeBps, feeCapBps);
    }

    private Scenario compute(String name, double pd, double lgd, double ead, double capital,
                             double cof, double opex, double rateRaw, double feeBps,
                             double rateCap, double feeCapBps, double target) {
        double rate = Math.min(rateRaw, rateCap);
        double feeIncome = ead * (feeBps / 10_000.0);
        double el = pd * lgd * ead;
        double income = rate * ead + feeIncome;
        double raroc = capital > 0 ? (income - el - opex - cof) / capital : 0.0;
        boolean meets = raroc >= target - 1e-9;
        String constraint = null;
        if (rateRaw > rateCap + 1e-9) constraint = "rate hit cap " + pct(rateCap);
        else if (feeBps >= feeCapBps - 1e-9 && feeCapBps > 0 && !meets) constraint = "fee hit cap " + bps(feeCapBps);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("pd", round6(pd));
        b.put("lgdAfterCollateral", round4(lgd));
        b.put("ead", round2(ead));
        b.put("expectedLoss", round2(el));
        b.put("capitalCharge", round2(capital));
        b.put("costOfFunds", round2(cof));
        b.put("opex", round2(opex));
        b.put("rate", round6(rate));
        b.put("feeBps", round2(feeBps));
        b.put("feeIncome", round2(feeIncome));
        b.put("interestIncome", round2(rate * ead));
        b.put("totalIncome", round2(income));
        b.put("raroc", round6(raroc));
        return new Scenario(name, round6(rate), round2(feeBps), round4(lgd), round6(raroc),
                meets, constraint, b);
    }

    // --------------------------------------------------- helpers

    private double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private double round4(double v) { return Math.round(v * 10_000.0) / 10_000.0; }
    private double round6(double v) { return Math.round(v * 1_000_000.0) / 1_000_000.0; }

    private String pct(double v) { return String.format("%.2f%%", v * 100); }
    private String bps(double v) { return String.format("%.0fbps", v); }

    @SuppressWarnings("unused")
    private List<Scenario> toList(Scenario... s) { return new ArrayList<>(List.of(s)); }
}
