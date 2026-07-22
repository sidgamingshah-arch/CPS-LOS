package com.helix.portfolio.service;

import com.helix.common.audit.AuditService;
import com.helix.common.web.ApiException;
import com.helix.portfolio.client.PortfolioUpstreamClient;
import com.helix.portfolio.entity.GlobalCashflowAssessment;
import com.helix.portfolio.repo.GlobalCashflowAssessmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Global / combined cash-flow (relationship consolidated debt-service). Pulls each group
 * member's latest CONFIRMED spread figures via best-effort {@link PortfolioUpstreamClient}
 * reads to counterparty-service (group membership) and origination-service (spread reads),
 * then consolidates them into a combined coverage view with a per-member contribution list:
 *
 * <ul>
 *   <li>combinedRevenue = Σ member REVENUE</li>
 *   <li>combinedEbitda  = Σ member EBITDA proxy (REVENUE − COGS − OPERATING_EXPENSES)</li>
 *   <li>combinedCfo      = Σ member CFO</li>
 *   <li>combinedDebtService = Σ member (INTEREST_EXPENSE + CURRENT_PORTION_LTD)</li>
 *   <li>combinedDscr     = combinedCfo ÷ combinedDebtService</li>
 * </ul>
 *
 * <p>Every figure is a DETERMINISTIC sum of the members' confirmed spread cells. This is a
 * read-side consolidation: it fails soft per member (warn + skip a member that can't be read)
 * and NEVER writes to any member's spread / rating / exposure. It persists only its own
 * advisory assessment row and stamps a SYSTEM (engine) audit event.</p>
 */
@Service
public class GlobalCashflowService {

    private static final String SUBJECT = "GlobalCashflow";

    private final GlobalCashflowAssessmentRepository repo;
    private final PortfolioUpstreamClient upstream;
    private final AuditService audit;

    public GlobalCashflowService(GlobalCashflowAssessmentRepository repo,
                                 PortfolioUpstreamClient upstream, AuditService audit) {
        this.repo = repo;
        this.upstream = upstream;
        this.audit = audit;
    }

    @Transactional
    public GlobalCashflowAssessment assemble(String groupReference, String actor) {
        if (groupReference == null || groupReference.isBlank()) {
            throw ApiException.badRequest("groupReference is required");
        }
        if (actor == null || actor.isBlank()) {
            throw ApiException.badRequest("actor (X-Actor) is required");
        }
        String gref = groupReference.trim();

        Map<String, Object> exposure = upstream.groupExposure(gref);   // 404 / 502 surfaced here
        String groupName = null;
        if (exposure.get("group") instanceof Map<?, ?> g) {
            Object n = g.get("name");
            groupName = n == null ? null : String.valueOf(n);
        }
        List<Map<String, Object>> memberRows = asMapList(exposure.get("members"));

        List<Map<String, Object>> contributions = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<String> currencies = new LinkedHashSet<>();
        double sumRevenue = 0, sumEbitda = 0, sumCfo = 0, sumDebtService = 0;
        int considered = memberRows.size();

        for (Map<String, Object> m : memberRows) {
            String ref = str(m.get("reference"));
            String name = str(m.get("name"));
            if (ref == null) {
                warnings.add("A member with no reference was skipped");
                continue;
            }

            String appRef = latestApplicationRef(ref);
            if (appRef == null) {
                warnings.add("%s (%s): no live application — skipped".formatted(nv(name), ref));
                continue;
            }

            Map<String, Object> ci = upstream.getMap("origination", "/api/applications/{r}/credit-inputs", appRef);
            if (ci == null || ci.isEmpty()) {
                warnings.add("%s (%s): application %s unreadable — skipped".formatted(nv(name), ref, appRef));
                continue;
            }
            if (!Boolean.TRUE.equals(ci.get("spreadConfirmed"))) {
                warnings.add("%s (%s): spread not confirmed on %s — skipped".formatted(nv(name), ref, appRef));
                continue;
            }
            Map<String, Object> fin = ci.get("latestFinancials") instanceof Map<?, ?> f
                    ? castMap(f) : Map.of();
            if (fin.isEmpty()) {
                warnings.add("%s (%s): no spread figures on %s — skipped".formatted(nv(name), ref, appRef));
                continue;
            }

            double revenue = num(fin, "REVENUE");
            double ebitda = revenue - num(fin, "COGS") - num(fin, "OPERATING_EXPENSES");   // EBITDA proxy
            double cfo = num(fin, "CFO");
            double debtService = num(fin, "INTEREST_EXPENSE") + num(fin, "CURRENT_PORTION_LTD");
            Double memberDscr = debtService > 0 ? round6(cfo / debtService) : null;
            String ccy = str(ci.get("currency"));
            if (ccy != null) currencies.add(ccy);

            sumRevenue += revenue;
            sumEbitda += ebitda;
            sumCfo += cfo;
            sumDebtService += debtService;

            Map<String, Object> c = new LinkedHashMap<>();
            c.put("ref", ref);
            c.put("name", name);
            c.put("applicationRef", appRef);
            c.put("currency", ccy);
            c.put("revenue", round2(revenue));
            c.put("ebitda", round2(ebitda));
            c.put("cfo", round2(cfo));
            c.put("debtService", round2(debtService));
            c.put("dscr", memberDscr);
            contributions.add(c);
        }

        // Round the combined figures once, then derive combined DSCR from those SAME persisted
        // figures so the ratio is fully self-consistent with what is stored and displayed.
        double combinedRevenue = round2(sumRevenue);
        double combinedEbitda = round2(sumEbitda);
        double combinedCfo = round2(sumCfo);
        double combinedDebtService = round2(sumDebtService);
        double combinedDscr = combinedDebtService > 0 ? round6(combinedCfo / combinedDebtService) : 0.0;

        GlobalCashflowAssessment a = new GlobalCashflowAssessment();
        a.setGcfRef(newRef());
        a.setGroupReference(gref);
        a.setGroupName(groupName);
        a.setCurrency(currencies.isEmpty() ? null : currencies.size() == 1
                ? currencies.iterator().next() : "MIXED");
        a.setMembers(contributions);
        a.setMembersConsidered(considered);
        a.setMembersIncluded(contributions.size());
        a.setWarnings(warnings);
        a.setCombinedRevenue(combinedRevenue);
        a.setCombinedEbitda(combinedEbitda);
        a.setCombinedCfo(combinedCfo);
        a.setCombinedDebtService(combinedDebtService);
        a.setCombinedDscr(combinedDscr);
        a.setAdvisory(true);
        a.setCreatedBy(actor);
        GlobalCashflowAssessment saved = repo.save(a);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("groupReference", gref);
        detail.put("membersConsidered", considered);
        detail.put("membersIncluded", contributions.size());
        detail.put("combinedCfo", saved.getCombinedCfo());
        detail.put("combinedDebtService", saved.getCombinedDebtService());
        detail.put("combinedDscr", saved.getCombinedDscr());
        detail.put("advisory", true);
        audit.engine("GLOBAL_CASHFLOW_CONSOLIDATED", SUBJECT, saved.getGcfRef(),
                "Consolidated cash-flow for group %s — %d of %d member(s), combined DSCR %.2f (advisory, member spreads unchanged)"
                        .formatted(gref, contributions.size(), considered, saved.getCombinedDscr()),
                detail);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<GlobalCashflowAssessment> list(String groupReference) {
        return groupReference == null || groupReference.isBlank()
                ? repo.findAllByOrderByIdDesc()
                : repo.findByGroupReferenceOrderByIdDesc(groupReference.trim());
    }

    @Transactional(readOnly = true)
    public GlobalCashflowAssessment get(String gcfRef) {
        return repo.findByGcfRef(gcfRef)
                .orElseThrow(() -> ApiException.notFound("Global cash-flow assessment " + gcfRef + " not found"));
    }

    // --------------------------------------------------------------- internals

    /** Latest non-CLOSED application reference for a counterparty, or null when there is none. */
    private String latestApplicationRef(String counterpartyRef) {
        List<Map<String, Object>> apps = upstream.applicationsForCounterparty(counterpartyRef);
        return apps.stream()
                .filter(a -> !"CLOSED".equalsIgnoreCase(str(a.get("status"))))
                .max(Comparator.comparing((Map<String, Object> a) -> nz(str(a.get("createdAt"))))
                        .thenComparing(a -> nz(str(a.get("reference")))))
                .map(a -> str(a.get("reference")))
                .orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asMapList(Object o) {
        if (!(o instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object e : list) {
            if (e instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private static double num(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String nv(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double round6(double v) {
        return Math.round(v * 1_000_000.0) / 1_000_000.0;
    }

    private static String newRef() {
        final String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder("GCF-");
        for (int i = 0; i < 6; i++) {
            sb.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        }
        return sb.toString();
    }
}
