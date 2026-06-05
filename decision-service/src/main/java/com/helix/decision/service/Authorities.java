package com.helix.decision.service;

import java.util.List;
import java.util.Map;

/** Grade ordering and delegated-authority hierarchy used by routing (PRD §8). */
public final class Authorities {

    private Authorities() {
    }

    public static final List<String> GRADES =
            List.of("AAA", "AA", "A", "BBB", "BB", "B", "CCC", "CC", "C", "D");

    /** Authority rank — higher number = greater authority. */
    private static final Map<String, Integer> RANK = Map.of(
            "ANALYST", 0,
            "RM", 0,
            "RM_HEAD", 1,
            "CREDIT_OFFICER", 2,
            "CREDIT_COMMITTEE", 3,
            "CRO", 3,
            "BOARD_COMMITTEE", 4);

    public static final List<String> LADDER =
            List.of("RM_HEAD", "CREDIT_OFFICER", "CREDIT_COMMITTEE", "BOARD_COMMITTEE");

    public static int gradeIndex(String grade) {
        int i = GRADES.indexOf(grade == null ? "" : grade.toUpperCase());
        return i < 0 ? GRADES.size() - 1 : i;   // unknown grade treated as worst
    }

    public static int rank(String authority) {
        return RANK.getOrDefault(authority == null ? "" : authority.toUpperCase(), -1);
    }

    /** Escalate an authority one rung up the ladder (capped at the top). */
    public static String escalateOneLevel(String authority) {
        int i = LADDER.indexOf(authority);
        if (i < 0) {
            return authority;
        }
        return LADDER.get(Math.min(i + 1, LADDER.size() - 1));
    }
}
