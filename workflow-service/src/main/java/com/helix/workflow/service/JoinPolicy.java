package com.helix.workflow.service;

import java.util.Locale;

/**
 * Fan-out / parallel-group join semantics, shared by the case-management fan-out
 * ({@code WorkItemService}) and the workflow engine's optional parallel stage
 * groups ({@code WorkflowEngine}). A blank/unknown policy is treated as ALL so the
 * default is the most conservative (every sibling must complete).
 *
 * <ul>
 *   <li>{@code ANY} — satisfied when at least one sibling has completed.</li>
 *   <li>{@code ALL} — satisfied when every sibling has completed.</li>
 *   <li>{@code QUORUM:n} — satisfied when at least {@code n} siblings have completed.</li>
 * </ul>
 */
public final class JoinPolicy {

    private JoinPolicy() {
    }

    public static boolean satisfied(String policy, int completed, int total) {
        String p = policy == null ? "ALL" : policy.trim().toUpperCase(Locale.ROOT);
        if (p.isEmpty()) p = "ALL";
        if (p.equals("ANY")) {
            return completed >= 1;
        }
        if (p.startsWith("QUORUM")) {
            int n = parseQuorum(p, total);
            return completed >= n;
        }
        // ALL (default)
        return total > 0 && completed >= total;
    }

    /** Parses {@code QUORUM:n}; defaults to {@code total} (i.e. ALL) when n is absent/invalid. */
    public static int parseQuorum(String policy, int total) {
        String p = policy == null ? "" : policy.trim().toUpperCase(Locale.ROOT);
        int colon = p.indexOf(':');
        if (colon >= 0 && colon < p.length() - 1) {
            try {
                int n = Integer.parseInt(p.substring(colon + 1).trim());
                if (n > 0) return n;
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return total;
    }
}
