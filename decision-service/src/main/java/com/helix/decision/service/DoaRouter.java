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

    /**
     * The resolved routing. {@code committeeLabel} + {@code composition} are the NAMED committee tier
     * (Stage-4 ladder) surfaced on the decision + sanction letter — additive, they never change which
     * authority/quorum is required.
     */
    public record Routing(String requiredAuthority, String ruleApplied, boolean committee, int quorum,
                          String committeeLabel, String composition) {
    }

    @SuppressWarnings("unchecked")
    public Routing route(double amount, String finalGrade, boolean hasDeviation, RulePackDto doaPack) {
        Map<String, Object> payload = doaPack.payload();
        List<Map<String, Object>> levels = (List<Map<String, Object>>) payload.getOrDefault("levels", List.of());

        String base = "BOARD_COMMITTEE";
        String matchedRule = "amount/grade exceeded all delegated tiers";
        Map<String, Object> matchedLevel = null;
        for (Map<String, Object> level : levels) {
            double maxAmount = ((Number) level.get("max_amount")).doubleValue();
            String minGrade = (String) level.get("min_grade");
            String authority = (String) level.get("authority");
            boolean amountOk = amount <= maxAmount;
            boolean gradeOk = Authorities.gradeIndex(finalGrade) <= Authorities.gradeIndex(minGrade);
            if (amountOk && gradeOk) {
                base = authority;
                matchedLevel = level;
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

        // Committee / quorum + the NAMED committee tier are properties of the resolved (post-escalation)
        // tier's level. Absent keys -> single-approver (current behaviour), null label.
        boolean committee = false;
        int quorum = 1;
        int defaultQuorum = intVal(payload.get("committee_default_quorum"), 2);
        Map<String, Object> resolved = matchedLevel;
        for (Map<String, Object> level : levels) {
            if (finalAuthority.equalsIgnoreCase(String.valueOf(level.get("authority")))) {
                committee = boolVal(level.get("committee"));
                quorum = committee ? Math.max(1, intVal(level.get("quorum"), defaultQuorum)) : 1;
                resolved = level;   // the escalated tier's level, when escalation changed the authority
                break;
            }
        }
        String committeeLabel = resolved == null ? null : str(resolved.get("committee_label"));
        String composition = resolved == null ? null : str(resolved.get("composition"));
        return new Routing(finalAuthority, rule, committee, quorum, committeeLabel, composition);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
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
