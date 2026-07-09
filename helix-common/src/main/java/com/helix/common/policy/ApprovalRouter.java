package com.helix.common.policy;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Generic data-driven approval router — the routing semantic that already
 * proved out for DoA (amount × grade × deviation in decision-service's
 * {@code DoaRouter}), lifted to a shared, reusable primitive.
 *
 * <p>The router is intentionally <b>policy-agnostic</b>: callers pass a
 * "metric" (concession bps, write-off amount, drawdown size, …) and a
 * payload whose {@code levels} list defines {@code (max_metric, min_grade,
 * authority, level)} tiers. The first matching tier wins. Deviations escalate
 * one tier upward when {@code deviation_escalates_one_level=true}.</p>
 *
 * <p>This is the building block referenced in {@code docs/DESIGN-workflow-engine.md}
 * §8. Consumers (PricingException, CAD deviation, write-off) can adopt it at
 * their own pace by reading an {@code APPROVAL_POLICY} master/pack instead of
 * inlining bps bands in Java. Today it powers the workflow-service's
 * approval-gate metadata; existing services keep their current routing until
 * they migrate.</p>
 */
public class ApprovalRouter {

    public record Routing(String requiredAuthority, int requiredLevels, String ruleApplied) {
    }

    /**
     * Route a request by amount + grade + deviation, reading from a payload of
     * the shape DoA already seeds in config-service. Caller supplies the
     * payload (rule-pack OR master) — the router doesn't know about either.
     */
    @SuppressWarnings("unchecked")
    public static Routing route(double metricAmount, String finalGrade, boolean hasDeviation,
                                 Map<String, Object> policyPayload) {
        if (policyPayload == null) {
            return new Routing("BOARD_COMMITTEE", 2, "no policy payload supplied — escalated");
        }
        List<Map<String, Object>> levels = (List<Map<String, Object>>)
                policyPayload.getOrDefault("levels", List.of());
        String base = "BOARD_COMMITTEE";
        int requiredLevels = 2;
        String matchedRule = "amount/grade exceeded all delegated tiers";
        for (Map<String, Object> level : levels) {
            Number maxN = (Number) level.get("max_amount");
            if (maxN == null) maxN = (Number) level.get("max_metric");
            if (maxN == null) continue;
            String minGrade = (String) level.get("min_grade");
            String authority = (String) level.get("authority");
            Number levelN = (Number) level.get("level");
            boolean amountOk = metricAmount <= maxN.doubleValue();
            boolean gradeOk = gradeIndex(finalGrade) <= gradeIndex(minGrade);
            if (amountOk && gradeOk) {
                base = authority;
                requiredLevels = levelN == null ? 1 : Math.max(1, levelN.intValue());
                matchedRule = "metric <= %s and grade >= %s -> %s (L%d)".formatted(
                        maxN, minGrade, authority, requiredLevels);
                break;
            }
        }
        boolean escalates = Boolean.TRUE.equals(policyPayload.get("deviation_escalates_one_level"));
        if (hasDeviation && escalates) {
            String escalated = escalateOneLevel(base);
            return new Routing(escalated, Math.max(requiredLevels, 2),
                    matchedRule + "; deviation -> escalated to " + escalated);
        }
        return new Routing(base, requiredLevels, matchedRule);
    }

    /** Same rating ladder used by decision-service's Authorities helper. */
    public static int gradeIndex(String grade) {
        if (grade == null) return 99;
        return switch (grade.toUpperCase(Locale.ROOT)) {
            case "AAA" -> 1;
            case "AA" -> 2;
            case "A" -> 3;
            case "BBB" -> 4;
            case "BB" -> 5;
            case "B" -> 6;
            case "CCC" -> 7;
            case "CC" -> 8;
            case "C" -> 9;
            case "D" -> 10;
            default -> 99;
        };
    }

    public static String escalateOneLevel(String authority) {
        if (authority == null) return "BOARD_COMMITTEE";
        return switch (authority.toUpperCase(Locale.ROOT)) {
            case "RM_HEAD" -> "CREDIT_OFFICER";
            case "CREDIT_OFFICER" -> "CREDIT_HEAD";
            case "CREDIT_HEAD" -> "CREDIT_COMMITTEE";
            case "CREDIT_COMMITTEE" -> "BOARD_COMMITTEE";
            default -> authority;
        };
    }
}
