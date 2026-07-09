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

    public record Routing(String requiredAuthority, String ruleApplied, boolean committee, int quorum) {
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

        String finalAuthority = base;
        String rule = matchedRule;
        boolean escalates = Boolean.TRUE.equals(payload.get("deviation_escalates_one_level"));
        if (hasDeviation && escalates) {
            finalAuthority = Authorities.escalateOneLevel(base);
            rule = matchedRule + "; deviation present -> escalated to " + finalAuthority;
        }

        // Committee / quorum are optional properties of the resolved (post-escalation) tier, read
        // from that tier's level in the pack. Absent keys -> single-approver (current behaviour).
        boolean committee = false;
        int quorum = 1;
        int defaultQuorum = intVal(payload.get("committee_default_quorum"), 2);
        for (Map<String, Object> level : levels) {
            if (finalAuthority.equalsIgnoreCase(String.valueOf(level.get("authority")))) {
                committee = boolVal(level.get("committee"));
                quorum = committee ? Math.max(1, intVal(level.get("quorum"), defaultQuorum)) : 1;
                break;
            }
        }
        return new Routing(finalAuthority, rule, committee, quorum);
    }

    private static boolean boolVal(Object o) {
        return Boolean.TRUE.equals(o) || "true".equalsIgnoreCase(String.valueOf(o));
    }

    private static int intVal(Object o, int dflt) {
        if (o instanceof Number n) return n.intValue();
        try {
            return o == null ? dflt : Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }
}
