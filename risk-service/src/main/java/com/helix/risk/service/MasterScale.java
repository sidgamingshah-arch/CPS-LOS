package com.helix.risk.service;

import java.util.List;

/** Internal master rating scale and score→grade mapping (PRD §5). */
public final class MasterScale {

    private MasterScale() {
    }

    /** Best to worst. */
    public static final List<String> GRADES = List.of("AAA", "AA", "A", "BBB", "BB", "B", "CCC", "CC", "C", "D");

    public static String fromScore(double score) {
        if (score >= 90) return "AAA";
        if (score >= 82) return "AA";
        if (score >= 74) return "A";
        if (score >= 66) return "BBB";
        if (score >= 56) return "BB";
        if (score >= 46) return "B";
        if (score >= 36) return "CCC";
        if (score >= 26) return "CC";
        if (score >= 16) return "C";
        return "D";
    }

    public static int index(String grade) {
        int i = GRADES.indexOf(grade);
        if (i < 0) {
            throw new IllegalArgumentException("Unknown grade: " + grade);
        }
        return i;
    }

    /** Notch distance between two grades (signed: positive = upgrade from a to b). */
    public static int notches(String from, String to) {
        return index(from) - index(to);
    }

    public static boolean isInvestmentGrade(String grade) {
        return index(grade) <= index("BBB");
    }
}
