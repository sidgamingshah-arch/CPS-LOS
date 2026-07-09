package com.helix.portfolio.service;

import java.util.List;

/**
 * Master rating ladder (best→worst), mirrored locally so portfolio-service can measure
 * notch distance without importing risk-service {@code MasterScale}. Keep in lock-step
 * with risk-service {@code MasterScale.GRADES}.
 */
public final class GradeLadder {

    private GradeLadder() {
    }

    /** The 10-notch AAA..D master scale, best to worst. */
    public static final List<String> GRADES =
            List.of("AAA", "AA", "A", "BBB", "BB", "B", "CCC", "CC", "C", "D");

    /** Ladder index; -1 for null/unknown grades (callers treat -1 as "skip"). */
    public static int index(String grade) {
        return grade == null ? -1 : GRADES.indexOf(grade.toUpperCase());
    }

    /**
     * Downgrade distance in notches: positive when {@code current} is WORSE than
     * {@code origination}, 0 when equal/better, -1 when either grade is null/unknown
     * (caller skips the notch rule).
     */
    public static int downgradeNotches(String origination, String current) {
        int io = index(origination);
        int ic = index(current);
        return (io < 0 || ic < 0) ? -1 : ic - io;
    }
}
