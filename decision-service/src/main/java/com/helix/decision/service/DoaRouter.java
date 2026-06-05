package com.helix.decision.service;

import com.helix.decision.client.UpstreamClient.RulePackDto;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Resolves the required approval authority from the delegated-authority matrix
 * (PRD §8, US-8.1). Routing is amount × rating × deviations, driven entirely by
 * the config rule pack — no hard-coded thresholds.
 */
@Component
public class DoaRouter {

    public record Routing(String requiredAuthority, String ruleApplied) {
    }

    @SuppressWarnings("unchecked")
    public Routing route(double amount, String finalGrade, boolean hasDeviation, RulePackDto doaPack) {
        Map<String, Object> payload = doaPack.payload();
        List<Map<String, Object>> levels = (List<Map<String, Object>>) payload.getOrDefault("levels", List.of());

        String base = "BOARD_COMMITTEE";
        String matchedRule = "amount/grade exceeded all delegated tiers";
        for (Map<String, Object> level : levels) {
            double maxAmount = ((Number) level.get("max_amount")).doubleValue();
            String minGrade = (String) level.get("min_grade");
            String authority = (String) level.get("authority");
            boolean amountOk = amount <= maxAmount;
            boolean gradeOk = Authorities.gradeIndex(finalGrade) <= Authorities.gradeIndex(minGrade);
            if (amountOk && gradeOk) {
                base = authority;
                matchedRule = "amount <= %.0f and grade >= %s -> %s".formatted(maxAmount, minGrade, authority);
                break;
            }
        }

        boolean escalates = Boolean.TRUE.equals(payload.get("deviation_escalates_one_level"));
        if (hasDeviation && escalates) {
            String escalated = Authorities.escalateOneLevel(base);
            return new Routing(escalated, matchedRule + "; deviation present -> escalated to " + escalated);
        }
        return new Routing(base, matchedRule);
    }
}
