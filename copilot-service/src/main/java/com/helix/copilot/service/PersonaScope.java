package com.helix.copilot.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RBAC/ABAC-style scoping (PRD §6.6): a copilot answers only within the asking
 * persona's data scope. Out-of-scope topics are refused, not answered — the
 * scope-leak guardrail.
 */
public final class PersonaScope {

    private PersonaScope() {
    }

    public enum Role { RM, ANALYST, APPROVER, COMPLIANCE, PORTFOLIO_MGR, CRO, OPS, GUEST }

    private static final Set<Intent> ALL = Set.of(
            Intent.SUMMARY, Intent.RATING, Intent.CAPITAL, Intent.PRICING, Intent.SPREAD,
            Intent.COVENANTS, Intent.DECISION, Intent.ECL, Intent.EWS, Intent.CONCENTRATION,
            Intent.PORTFOLIO, Intent.KYC, Intent.SCREENING, Intent.UBO);

    private static final Map<Role, Set<Intent>> SCOPE = Map.of(
            Role.RM, Set.of(Intent.SUMMARY, Intent.PRICING, Intent.DECISION),
            Role.ANALYST, Set.of(Intent.SUMMARY, Intent.SPREAD, Intent.RATING, Intent.CAPITAL, Intent.COVENANTS),
            Role.APPROVER, Set.of(Intent.SUMMARY, Intent.RATING, Intent.CAPITAL, Intent.PRICING, Intent.DECISION, Intent.COVENANTS),
            Role.COMPLIANCE, Set.of(Intent.SUMMARY, Intent.KYC, Intent.SCREENING, Intent.UBO),
            Role.PORTFOLIO_MGR, Set.of(Intent.SUMMARY, Intent.PORTFOLIO, Intent.CONCENTRATION, Intent.ECL, Intent.EWS),
            Role.CRO, ALL,
            Role.OPS, Set.of(Intent.SUMMARY, Intent.DECISION, Intent.PORTFOLIO),
            Role.GUEST, Set.of(Intent.SUMMARY));

    /** Maps a named actor (e.g. "credit.officer") to a role. */
    public static Role roleOf(String persona) {
        String p = persona == null ? "" : persona.toLowerCase();
        // The generic demo persona gets full read scope so the copilot can be
        // shown answering across every capability. This widens only the READ
        // scope; the action guardrail (approve/override/price/book) still refuses
        // regardless of role, so the non-binding invariant is preserved.
        if (p.contains("demo")) return Role.CRO;
        if (p.contains("cro")) return Role.CRO;
        if (p.contains("compliance")) return Role.COMPLIANCE;
        if (p.contains("portfolio")) return Role.PORTFOLIO_MGR;
        if (p.contains("committee") || p.contains("officer")) return Role.APPROVER;
        if (p.contains("analyst")) return Role.ANALYST;
        if (p.contains("ops")) return Role.OPS;
        if (p.contains("rm")) return Role.RM;
        return Role.GUEST;
    }

    public static boolean allows(Role role, Intent intent) {
        return SCOPE.getOrDefault(role, Set.of(Intent.SUMMARY)).contains(intent);
    }

    public static List<String> scopeOf(Role role) {
        return SCOPE.getOrDefault(role, Set.of(Intent.SUMMARY)).stream().map(Enum::name).sorted().toList();
    }
}
