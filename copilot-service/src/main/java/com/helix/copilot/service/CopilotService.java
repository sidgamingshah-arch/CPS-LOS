package com.helix.copilot.service;

import com.helix.common.audit.AuditService;
import com.helix.copilot.client.CopilotUpstreamClient;
import com.helix.copilot.dto.Dtos.AskRequest;
import com.helix.copilot.dto.Dtos.Citation;
import com.helix.copilot.dto.Dtos.CopilotAnswer;
import com.helix.copilot.service.PersonaScope.Role;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Persona-scoped, grounded, non-binding conversational copilot (PRD §6.6).
 * It classifies intent, enforces the persona's data scope, retrieves facts from
 * upstream services and answers citing those sources. It refuses to fabricate and
 * refuses credit-consequential actions, routing them to the gated workflow instead.
 *
 * The reasoning here is deterministic (intent + retrieval + grounding envelope);
 * a generative model would drop in behind this same contract without changing the
 * scope enforcement, grounding, citation or refusal behaviour.
 */
@Service
public class CopilotService {

    private static final Pattern DEAL_REF = Pattern.compile("HLX-\\d{4}-[A-Z0-9]{6}", Pattern.CASE_INSENSITIVE);

    private static final List<String> ACTION_PHRASES = List.of(
            "approve this", "approve the", "please approve", "go ahead and approve", "reject this", "decline this",
            "override the rating", "override rating", "override to", "change the grade", "change the rating",
            "set the rating", "set rating", "book the exposure", "book this", "disburse", "reclassify",
            "re-stage", "restage", "waive the", "waive this", "sanction this deal", "sign off the", "increase the limit");

    private record Composed(String text, boolean grounded, List<Citation> citations, String suggestedAction) {
        static Composed of(String text, boolean grounded, List<Citation> citations) {
            return new Composed(text, grounded, citations, null);
        }
    }

    private final CopilotUpstreamClient up;
    private final AuditService audit;

    public CopilotService(CopilotUpstreamClient up, AuditService audit) {
        this.up = up;
        this.audit = audit;
    }

    public CopilotAnswer ask(String persona, AskRequest req) {
        Role role = PersonaScope.roleOf(persona);
        List<String> scope = PersonaScope.scopeOf(role);
        String q = req.question() == null ? "" : req.question().toLowerCase(Locale.ROOT);
        String ref = resolveRef(req);

        // 1) Guardrail: never execute credit-consequential actions.
        if (isAction(q)) {
            audit.ai("copilot:" + persona, "COPILOT_ASK", "Application", ref,
                    "Refused credit-consequential action request", Map.of("intent", "ACTION_BLOCKED", "role", role.name()));
            return new CopilotAnswer(persona, role.name(), Intent.ACTION_BLOCKED.name(),
                    "I can't perform credit-consequential actions — those require a named human at the right authority.",
                    false, true,
                    "Approvals, overrides, staging and booking are gated to accountable humans; the copilot is advisory only.",
                    "Use the gated workflow: rate/override under /risk, record the decision under /decision (named approver), "
                            + "book/stage under /portfolio.",
                    List.of(), scope);
        }

        // 2) Intent + scope (RBAC/ABAC) enforcement.
        Intent intent = detectIntent(q);
        if (intent == Intent.UNKNOWN) {
            return answer(persona, role, intent, scope,
                    "I can help with: " + String.join(", ", scope) + ". Try naming a deal reference (HLX-…) and a topic.",
                    false, null, List.of());
        }
        if (!PersonaScope.allows(role, intent)) {
            audit.ai("copilot:" + persona, "COPILOT_SCOPE_REFUSED", "Application", ref,
                    "Out-of-scope topic %s for role %s".formatted(intent, role), Map.of("intent", intent.name()));
            return new CopilotAnswer(persona, role.name(), intent.name(),
                    "That topic is outside your role's data scope.", false, true,
                    "%s is not in scope for %s. In scope: %s.".formatted(intent, role, String.join(", ", scope)),
                    null, List.of(), scope);
        }

        // 3) Retrieve + ground.
        Composed c = switch (intent) {
            case SUMMARY -> summary(ref);
            case RATING -> rating(ref);
            case CAPITAL -> capital(ref);
            case PRICING -> pricing(ref);
            case SPREAD -> spread(ref);
            case COVENANTS -> covenants(ref);
            case DECISION -> decision(ref);
            case ECL -> ecl(ref);
            case EWS -> ews(ref);
            case CONCENTRATION, PORTFOLIO -> portfolio();
            case KYC -> kyc(ref);
            case SCREENING -> screening(ref);
            case UBO -> ubo(ref);
            default -> Composed.of("I don't have an answer for that.", false, List.of());
        };

        audit.ai("copilot:" + persona, "COPILOT_ASK", "Application", ref,
                "Answered %s (grounded=%s)".formatted(intent, c.grounded()),
                Map.of("intent", intent.name(), "role", role.name(), "grounded", c.grounded()));
        return new CopilotAnswer(persona, role.name(), intent.name(), c.text(), c.grounded(), false, null,
                c.suggestedAction(), c.citations(), scope);
    }

    // ----------------------------------------------------------- intent handlers

    private Composed summary(String ref) {
        if (ref == null) return needRef();
        Map<String, Object> ci = up.creditInputs(ref);
        if (ci == null) return notFound(ref);
        Map<String, Object> rs = up.riskSummary(ref);
        Map<String, Object> rating = sub(rs, "rating");
        Map<String, Object> pricing = sub(rs, "pricing");
        Map<String, Object> dec = up.decisionLatest(ref);
        StringBuilder sb = new StringBuilder();
        sb.append("%s — %s %s for %s (%s, %s). ".formatted(ref, str(ci, "facilityType"),
                money(dbl(ci, "requestedAmount")), str(ci, "counterpartyName"), str(ci, "segment"), str(ci, "jurisdiction")));
        if (rating != null) sb.append("Grade %s (PD %s). ".formatted(str(rating, "finalGrade"), pct(dbl(rating, "pd"))));
        if (pricing != null) sb.append("RAROC %s%s. ".formatted(pct(dbl(pricing, "raroc")),
                Boolean.TRUE.equals(pricing.get("belowHurdle")) ? " (below hurdle)" : ""));
        if (dec != null) sb.append("Approval: %s, %s.".formatted(str(dec, "status"),
                dec.get("outcome") == null ? "routed to " + str(dec, "requiredAuthority") : str(dec, "outcome")));
        return Composed.of(sb.toString().trim(), true, List.of(
                new Citation("origination-service", "/api/applications/" + ref + "/credit-inputs", "facilityType,requestedAmount"),
                new Citation("risk-service", "/api/risk/" + ref, "rating.finalGrade,pricing.raroc"),
                new Citation("decision-service", "/api/decisions/" + ref, "status,outcome")));
    }

    private Composed rating(String ref) {
        if (ref == null) return needRef();
        Map<String, Object> r = sub(up.riskSummary(ref), "rating");
        if (r == null) return Composed.of("No rating exists for " + ref + " yet — the deal must be rated first.", true, List.of());
        String over = Boolean.TRUE.equals(r.get("overridden"))
                ? " Overridden from %s (reason %s); ".formatted(str(r, "modelGrade"), str(r, "reasonCode")) : " ";
        return Composed.of(("Final grade %s (model %s, score %s).%sPD %s, LGD %s, EAD %s. Confirmed: %s.")
                .formatted(str(r, "finalGrade"), str(r, "modelGrade"), num(dbl(r, "modelScore")), over,
                        pct(dbl(r, "pd")), pct(dbl(r, "lgd")), money(dbl(r, "ead")), r.get("confirmed")),
                true, List.of(new Citation("risk-service", "/api/risk/" + ref + "/rating", "finalGrade,pd,lgd,ead")));
    }

    private Composed capital(String ref) {
        if (ref == null) return needRef();
        Map<String, Object> cap = sub(up.riskSummary(ref), "capital");
        if (cap == null) return Composed.of("No capital result for " + ref + " — compute capital after rating.", true, List.of());
        Map<String, Object> expl = up.capitalExplain(ref);
        String text = "Exposure class %s, applied risk weight %s, RWA %s, capital %s (pack %s v%s).".formatted(
                str(cap, "exposureClass"), pct(dbl(cap, "appliedRiskWeight")), money(dbl(cap, "rwa")),
                money(dbl(cap, "capitalRequired")), str(cap, "capitalPackCode"), num(dbl(cap, "capitalPackVersion")));
        if (expl != null && expl.get("explanation") != null) text += " " + expl.get("explanation");
        return Composed.of(text, true, List.of(
                new Citation("risk-service", "/api/risk/" + ref, "capital.rwa,capital.appliedRiskWeight"),
                new Citation("risk-service", "/api/risk/" + ref + "/capital/explain", "explanation")));
    }

    private Composed pricing(String ref) {
        if (ref == null) return needRef();
        Map<String, Object> p = sub(up.riskSummary(ref), "pricing");
        if (p == null) return Composed.of("No pricing for " + ref + " — compute capital then pricing.", true, List.of());
        boolean below = Boolean.TRUE.equals(p.get("belowHurdle"));
        return Composed.of(("Recommended rate %s, RAROC %s vs hurdle %s%s. Expected loss %s, capital charge %s. "
                + "Advisory only — pricing is the RM/approver's decision.").formatted(
                pct(dbl(p, "recommendedRate")), pct(dbl(p, "raroc")), pct(dbl(p, "hurdleRaroc")),
                below ? " — BELOW HURDLE, requires escalation" : "", money(dbl(p, "expectedLoss")), money(dbl(p, "capitalCharge"))),
                true, List.of(new Citation("risk-service", "/api/risk/" + ref, "pricing.recommendedRate,pricing.raroc")));
    }

    private Composed spread(String ref) {
        if (ref == null) return needRef();
        Map<String, Object> an = up.analysis(ref);
        if (an == null) return notFound(ref);
        List<Map<String, Object>> periods = list(an, "periods");
        if (periods.isEmpty()) return Composed.of("No spread exists for " + ref + " yet.", true, List.of());
        Map<String, Object> latest = periods.get(0);
        Map<String, Object> ratios = sub(latest, "ratios");
        List<?> flags = (List<?>) an.get("benchmarkFlags");
        String text = "%s spread (confirmed: %s). DSCR %s, net leverage %s, interest coverage %s, EBITDA margin %s.".formatted(
                str(latest, "label"), an.get("spreadConfirmed"), num(dbl(ratios, "DSCR")), num(dbl(ratios, "NET_LEVERAGE")),
                num(dbl(ratios, "INTEREST_COVERAGE")), pct(dbl(ratios, "EBITDA_MARGIN")));
        if (flags != null && !flags.isEmpty()) text += " Flags: " + String.join("; ", flags.stream().map(Object::toString).toList()) + ".";
        return Composed.of(text, true, List.of(new Citation("origination-service", "/api/applications/" + ref + "/analysis", "ratios,benchmarkFlags")));
    }

    private Composed covenants(String ref) {
        if (ref == null) return needRef();
        List<Map<String, Object>> covs = up.covenants(ref);
        if (covs.isEmpty()) return Composed.of("No covenants defined for " + ref + ".", true, List.of());
        String joined = String.join("; ", covs.stream().map(c -> "%s %s %s (%s)".formatted(
                str(c, "metric"), str(c, "operator"), num(dbl(c, "threshold")), str(c, "testFrequency"))).toList());
        return Composed.of("%d covenant(s): %s.".formatted(covs.size(), joined), true,
                List.of(new Citation("decision-service", "/api/decisions/" + ref + "/covenants", "metric,operator,threshold")));
    }

    private Composed decision(String ref) {
        if (ref == null) return needRef();
        Map<String, Object> d = up.decisionLatest(ref);
        if (d == null) return Composed.of("No approval has been routed for " + ref + " yet.", true, List.of());
        List<?> dev = (List<?>) d.get("deviations");
        String text = "Requires %s authority. Status %s%s.".formatted(str(d, "requiredAuthority"), str(d, "status"),
                d.get("outcome") == null ? "" : " — %s by %s".formatted(str(d, "outcome"), str(d, "decidedBy")));
        if (dev != null && !dev.isEmpty()) text += " Deviations: " + String.join("; ", dev.stream().map(Object::toString).toList()) + ".";
        return Composed.of(text, true, List.of(new Citation("decision-service", "/api/decisions/" + ref, "requiredAuthority,status,outcome")));
    }

    private Composed ecl(String ref) {
        Map<String, Object> book = up.portfolioSummary();
        if (ref != null) {
            Map<String, Object> e = up.ecl(ref);
            if (e != null) {
                return Composed.of(("%s: stage %s, ECL %s, IRAC %s, reported %s (%s).").formatted(ref, str(e, "stage"),
                        money(dbl(e, "ecl")), money(dbl(e, "iracProvision")), money(dbl(e, "reportedProvision")),
                        str(e, "reportedProvisionPolicy")), true,
                        List.of(new Citation("portfolio-service", "/api/portfolio/exposures/" + ref + "/ecl/latest", "stage,reportedProvision")));
            }
        }
        if (book == null) return Composed.of("Couldn't retrieve portfolio provisioning.", false, List.of());
        return Composed.of("Book provision %s across %s exposures; staging: %s.".formatted(
                money(dbl(book, "totalReportedProvision")), num(dbl(book, "exposureCount")), sub(book, "byStage")),
                true, List.of(new Citation("portfolio-service", "/api/portfolio/summary", "totalReportedProvision,byStage")));
    }

    private Composed ews(String ref) {
        if (ref == null) return needRef();
        List<Map<String, Object>> sigs = up.signals(ref);
        if (sigs.isEmpty()) return Composed.of("No early-warning signals for " + ref + " (clean) — or run a scan first.", true, List.of());
        String joined = String.join("; ", sigs.stream().map(s -> "%s [%s, score %s]".formatted(
                str(s, "signalType"), str(s, "severity"), num(dbl(s, "score")))).toList());
        return Composed.of("%d signal(s): %s. These are flags only — staging/remediation is human-decided.".formatted(sigs.size(), joined),
                true, List.of(new Citation("portfolio-service", "/api/portfolio/exposures/" + ref + "/ews", "signalType,severity,score")));
    }

    private Composed portfolio() {
        Map<String, Object> s = up.portfolioSummary();
        Map<String, Object> conc = up.concentration("IN-RBI");
        if (s == null) return Composed.of("Couldn't retrieve the portfolio summary.", false, List.of());
        String text = "Book: %s exposures, total EAD %s, RWA %s, provision %s.".formatted(
                num(dbl(s, "exposureCount")), money(dbl(s, "totalEad")), money(dbl(s, "totalRwa")),
                money(dbl(s, "totalReportedProvision")));
        if (conc != null) {
            List<?> breaches = (List<?>) conc.get("breaches");
            text += breaches != null && !breaches.isEmpty()
                    ? " Concentration breaches: " + String.join("; ", breaches.stream().map(Object::toString).toList()) + "."
                    : " No concentration limit breaches.";
        }
        return Composed.of(text, true, List.of(
                new Citation("portfolio-service", "/api/portfolio/summary", "totalEad,totalRwa,totalReportedProvision"),
                new Citation("portfolio-service", "/api/portfolio/concentration", "breaches")));
    }

    private Composed kyc(String ref) {
        Map<String, Object> cp = counterpartyFor(ref);
        if (cp == null) return needRef();
        return Composed.of("%s: CDD tier %s, KYC %s, re-KYC due %s.".formatted(str(cp, "legalName"),
                str(cp, "cddTier"), str(cp, "kycStatus"), str(cp, "reKycDueDate")), true,
                List.of(new Citation("counterparty-service", "/api/counterparties/" + str(cp, "id"), "cddTier,kycStatus")));
    }

    private Composed screening(String ref) {
        Map<String, Object> cp = counterpartyFor(ref);
        if (cp == null) return needRef();
        List<Map<String, Object>> hits = up.screening(dbl(cp, "id").longValue());
        if (hits.isEmpty()) return Composed.of("No screening hits recorded for %s.".formatted(str(cp, "legalName")), true, List.of());
        String joined = String.join("; ", hits.stream().map(h -> "%s [%s, %s]".formatted(
                str(h, "listSource"), str(h, "severity"), str(h, "disposition"))).toList());
        return Composed.of("%d hit(s) for %s: %s. Disposition is a named human action.".formatted(
                hits.size(), str(cp, "legalName"), joined), true,
                List.of(new Citation("counterparty-service", "/api/counterparties/" + str(cp, "id") + "/screening", "listSource,severity,disposition")));
    }

    private Composed ubo(String ref) {
        Map<String, Object> cp = counterpartyFor(ref);
        if (cp == null) return needRef();
        List<Map<String, Object>> nodes = up.ubo(dbl(cp, "id").longValue());
        List<Map<String, Object>> ubos = nodes.stream().filter(n -> Boolean.TRUE.equals(n.get("ubo"))).toList();
        if (ubos.isEmpty()) return Composed.of("No beneficial owners ≥ threshold resolved for %s.".formatted(str(cp, "legalName")), true, List.of());
        String joined = String.join("; ", ubos.stream().map(n -> "%s (%s)".formatted(
                str(n, "name"), pct(dbl(n, "effectiveOwnership")))).toList());
        return Composed.of("UBO(s) for %s: %s.".formatted(str(cp, "legalName"), joined), true,
                List.of(new Citation("counterparty-service", "/api/counterparties/" + str(cp, "id") + "/ubo", "name,effectiveOwnership,ubo")));
    }

    // --------------------------------------------------------------- helpers

    private Map<String, Object> counterpartyFor(String ref) {
        if (ref == null) return null;
        Map<String, Object> ci = up.creditInputs(ref);
        if (ci == null || ci.get("counterpartyId") == null) return null;
        return up.counterparty(dbl(ci, "counterpartyId").longValue());
    }

    private boolean isAction(String q) {
        return ACTION_PHRASES.stream().anyMatch(q::contains);
    }

    private Intent detectIntent(String q) {
        if (has(q, "rwa", "risk weight", "capital requirement", "regulatory capital")) return Intent.CAPITAL;
        if (has(q, "raroc", "pricing", "interest rate", "price", "hurdle", "spread over")) return Intent.PRICING;
        if (has(q, "rating", "grade", "pd", "score", "notch")) return Intent.RATING;
        if (has(q, "covenant")) return Intent.COVENANTS;
        if (has(q, "ecl", "provision", "staging", "stage ", "ifrs", "irac", "impair")) return Intent.ECL;
        if (has(q, "ews", "early warning", "watchlist", "signal")) return Intent.EWS;
        if (has(q, "concentration", "limit", "headroom")) return Intent.CONCENTRATION;
        if (has(q, "portfolio", "book ", "total exposure", "stress")) return Intent.PORTFOLIO;
        if (has(q, "screening", "sanction", "pep", "adverse media", "hit")) return Intent.SCREENING;
        if (has(q, "ubo", "beneficial owner", "ownership", "control")) return Intent.UBO;
        if (has(q, "kyc", "cdd", "onboard", "re-kyc")) return Intent.KYC;
        if (has(q, "ebitda", "leverage", "dscr", "ratio", "financ", "coverage")) return Intent.SPREAD;
        if (has(q, "decision", "authority", "doa", "approved", "who approves", "committee")) return Intent.DECISION;
        if (has(q, "summari", "overview", "status", "tell me about", "describe", "what is the deal", "snapshot")) return Intent.SUMMARY;
        return Intent.UNKNOWN;
    }

    private boolean has(String q, String... needles) {
        for (String n : needles) if (q.contains(n)) return true;
        return false;
    }

    private String resolveRef(AskRequest req) {
        if (req.reference() != null && !req.reference().isBlank()) return req.reference().toUpperCase();
        Matcher m = DEAL_REF.matcher(req.question() == null ? "" : req.question());
        return m.find() ? m.group().toUpperCase() : null;
    }

    private Composed needRef() {
        return Composed.of("Please name a deal reference (e.g. HLX-2026-XXXXXX) so I can ground the answer.", false, List.of());
    }

    private Composed notFound(String ref) {
        return Composed.of("I couldn't find deal " + ref + ".", false, List.of());
    }

    private CopilotAnswer answer(String persona, Role role, Intent intent, List<String> scope,
                                 String text, boolean grounded, String suggested, List<Citation> citations) {
        return new CopilotAnswer(persona, role.name(), intent.name(), text, grounded, false, null, suggested, citations, scope);
    }

    // ---- typed map readers (the upstream JSON is read defensively) ----

    @SuppressWarnings("unchecked")
    private Map<String, Object> sub(Map<String, Object> m, String k) {
        return m == null ? null : (m.get(k) instanceof Map<?, ?> v ? (Map<String, Object>) v : null);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(Map<String, Object> m, String k) {
        return m != null && m.get(k) instanceof List<?> v ? (List<Map<String, Object>>) v : List.of();
    }

    private String str(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v == null ? "—" : v.toString();
    }

    private Double dbl(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }

    private String money(Double v) {
        return v == null ? "—" : String.format(Locale.UK, "%,.0f", v);
    }

    private String pct(Double v) {
        return v == null ? "—" : String.format(Locale.UK, "%.2f%%", v * 100);
    }

    private String num(Double v) {
        if (v == null) return "—";
        return v == Math.rint(v) ? String.valueOf(v.longValue()) : String.format(Locale.UK, "%.2f", v);
    }
}
