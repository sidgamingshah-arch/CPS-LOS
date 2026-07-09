package com.helix.decision.service;

import com.helix.common.audit.AuditService;
import com.helix.decision.client.UpstreamClient;
import com.helix.decision.client.UpstreamClient.CounterpartyGroupDto;
import com.helix.decision.client.UpstreamClient.DealEnvelopeDto;
import com.helix.decision.client.UpstreamClient.GroupExposureDto;
import com.helix.decision.client.UpstreamClient.GroupMemberDto;
import com.helix.decision.client.UpstreamClient.LoanApplicationRefDto;
import com.helix.decision.client.UpstreamClient.RiskSummaryDto;
import com.helix.decision.dto.GroupDtos.GroupInsights;
import com.helix.decision.dto.GroupDtos.GroupMemberInsight;
import com.helix.decision.entity.Covenant;
import com.helix.decision.repo.CovenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Group decisioning insights — aggregates the deterministic figures (rating,
 * exposure, RAROC) of every group member without mutating any of them, and
 * drafts an advisory narrative. Stamped {@code audit.ai("group-insights", ...)}
 * so the trail records this as an AI-assisted advisory output.
 */
@Service
public class GroupInsightsService {

    /** Grade ladder used for "lowest" / "highest" — anything outside maps to weakest. */
    private static final List<String> GRADE_LADDER = List.of(
            "AAA", "AA+", "AA", "AA-", "A+", "A", "A-",
            "BBB+", "BBB", "BBB-", "BB+", "BB", "BB-",
            "B+", "B", "B-", "CCC", "CC", "C", "D");

    private final UpstreamClient upstream;
    private final CovenantRepository covenants;
    private final AuditService audit;

    public GroupInsightsService(UpstreamClient upstream, CovenantRepository covenants, AuditService audit) {
        this.upstream = upstream;
        this.covenants = covenants;
        this.audit = audit;
    }

    @Transactional
    public GroupInsights insights(String groupReference, String actor) {
        CounterpartyGroupDto group = upstream.groupByReference(groupReference);
        GroupExposureDto exposure = upstream.groupExposure(groupReference);

        List<GroupMemberInsight> members = new ArrayList<>();
        Map<String, Double> exposureByCcy = new LinkedHashMap<>();
        double weightedPdNum = 0.0;
        double weightedRarocNum = 0.0;
        double exposureSum = 0.0;
        int withApp = 0;
        int belowHurdle = 0;
        int overridden = 0;
        Map<String, Double> currencyConcentration = new LinkedHashMap<>();
        Map<String, Double> segmentConcentration = new LinkedHashMap<>();
        String anchorJurisdiction = group.country();   // group-grade policy regime (largest-exposure member wins)
        double anchorExposure = -1;

        for (GroupMemberDto m : exposure.members()) {
            List<LoanApplicationRefDto> apps = upstream.applicationsForCounterparty(m.reference());
            // Pick the most-recently-created non-CLOSED application. createdAt is an ISO
            // instant string from origination; lexical ordering matches chronological order
            // for that format. If every app is CLOSED we deliberately treat the member as
            // having no live application — counting a closed loan in the rollup would
            // inflate exposure / PD weights.
            LoanApplicationRefDto latest = apps.stream()
                    .filter(a -> !"CLOSED".equalsIgnoreCase(a.status()))
                    .max(Comparator.comparing(
                            (LoanApplicationRefDto a) -> a.createdAt() == null ? "" : a.createdAt())
                            .thenComparing(LoanApplicationRefDto::reference))
                    .orElse(null);

            String appRef = latest == null ? null : latest.reference();
            DealEnvelopeDto env = appRef == null ? null : upstream.envelopeOrNull(appRef);
            RiskSummaryDto risk = appRef == null ? null : upstream.riskSummaryOrNull(appRef);
            List<Covenant> covs = appRef == null ? List.of() : covenants.findByApplicationReference(appRef);

            Double exp = env == null ? null : env.totalProposedAmount();
            String ccy = env == null ? null : env.currency();
            Double pd = risk == null || risk.rating() == null ? null : risk.rating().pd();
            String finalGrade = risk == null || risk.rating() == null ? null : risk.rating().finalGrade();
            String modelGrade = risk == null || risk.rating() == null ? null : risk.rating().modelGrade();
            boolean ratingOverridden = risk != null && risk.rating() != null && risk.rating().overridden();
            boolean ratingConfirmed = risk != null && risk.rating() != null
                    && Boolean.TRUE.equals(risk.rating().confirmed());
            Double recRate = risk == null || risk.pricing() == null ? null : risk.pricing().recommendedRate();
            Double raroc = risk == null || risk.pricing() == null ? null : risk.pricing().raroc();
            Double hurdle = risk == null || risk.pricing() == null ? null : risk.pricing().hurdleRaroc();
            boolean below = risk != null && risk.pricing() != null && risk.pricing().belowHurdle();

            if (env != null) {
                withApp++;
                exposureByCcy.merge(ccy, exp, Double::sum);
                currencyConcentration.merge(ccy, exp, Double::sum);
                exposureSum += exp;
                if (pd != null) weightedPdNum += pd * exp;
                if (raroc != null) weightedRarocNum += raroc * exp;
                segmentConcentration.merge(safe(m.segment()), exp, Double::sum);
                if (exp != null && exp > anchorExposure && env.jurisdiction() != null) {
                    anchorExposure = exp;
                    anchorJurisdiction = env.jurisdiction();
                }
            }
            if (below) belowHurdle++;
            if (ratingOverridden) overridden++;

            members.add(new GroupMemberInsight(
                    m.reference(), m.name(), m.segment(), m.recordType(), m.rm(),
                    appRef,
                    latest == null ? null : latest.status(),
                    finalGrade, modelGrade, pd, ratingOverridden, ratingConfirmed,
                    exp, ccy,
                    env == null || env.facilities() == null ? 0 : env.facilities().size(),
                    covs.size(),
                    recRate, raroc, hurdle, below));
        }

        Double weightedPd = exposureSum == 0 ? null : weightedPdNum / exposureSum;
        Double weightedRaroc = exposureSum == 0 ? null : weightedRarocNum / exposureSum;

        String lowestGrade = pickGrade(members, true);
        String highestGrade = pickGrade(members, false);

        // D10 — derive a defensible GROUP grade on the AAA..D master ladder from member grades +
        // exposures, per the jurisdiction's GROUP_GRADE policy. Deterministic + advisory: it reads
        // authoritative figures already fetched and never mutates a member's rating.
        GroupGrade gg = deriveGroupGrade(members, upstream.groupGradePolicy(anchorJurisdiction).payload());

        List<String> concentrations = composeConcentrations(currencyConcentration, segmentConcentration, exposureSum);
        List<String> callouts = composeCallouts(belowHurdle, overridden, members.size(), withApp, lowestGrade);

        String narrative = composeNarrative(group, exposure, members.size(), withApp,
                exposureByCcy, weightedPd, weightedRaroc, lowestGrade, highestGrade, belowHurdle, overridden);

        GroupInsights gi = new GroupInsights(
                group.reference(), group.name(), group.groupRmId(), group.multiCountry(),
                group.country(),
                members.size(), exposure.obligorCount(), withApp,
                belowHurdle, overridden,
                exposureByCcy, weightedPd, weightedRaroc, lowestGrade, highestGrade,
                concentrations, callouts,
                gg.grade(), gg.method(), gg.basis(), gg.contributions(),
                members, narrative, true);

        audit.ai("group-insights", "GROUP_INSIGHTS_GENERATED", "Group", group.reference(),
                "Insights for group %s — %d member(s), %d with application, %d below hurdle, %d overridden"
                        .formatted(group.name(), members.size(), withApp, belowHurdle, overridden),
                Map.of("memberCount", members.size(), "membersWithApplication", withApp,
                        "membersBelowHurdle", belowHurdle, "membersOverridden", overridden,
                        "advisory", true));
        // The group grade is a deterministic rollup, so it is stamped as an engine (SYSTEM) event
        // separate from the advisory narrative above.
        audit.engine("GROUP_GRADE_DERIVED", "Group", group.reference(),
                "Group grade %s via %s across %d rated member(s) — advisory, member ratings unchanged"
                        .formatted(gg.grade() == null ? "n/a" : gg.grade(), gg.method(), gg.contributions().size()),
                Map.of("groupGrade", gg.grade() == null ? "" : gg.grade(), "method", gg.method(),
                        "ratedMembers", gg.contributions().size(), "advisory", true));
        return gi;
    }

    /** Result holder for the derived group grade. */
    private record GroupGrade(String grade, String method, String basis,
                              List<com.helix.decision.dto.GroupDtos.GroupGradeContribution> contributions) {
    }

    /**
     * Deterministic group-grade derivation on the 10-notch master ladder (best→worst), driven by
     * the GROUP_GRADE policy: EXPOSURE_WEIGHTED_NOTCH (default), WORST_OF, or PARENT_ANCHORED.
     * Members with no authoritative grade are excluded; exposure weights fall back to equal weight
     * when no graded member carries exposure. Purely advisory — mutates nothing.
     */
    private GroupGrade deriveGroupGrade(List<GroupMemberInsight> members, Map<String, Object> policy) {
        String method = str(policy.get("method"), "EXPOSURE_WEIGHTED_NOTCH").toUpperCase(Locale.ROOT);
        String rounding = str(policy.get("rounding"), "HALF_UP_WORSE").toUpperCase(Locale.ROOT);
        int supportNotches = (int) num(policy.get("parent_support_notches"), 0);
        int minRated = Math.max(1, (int) num(policy.get("min_rated_members"), 1));

        List<GroupMemberInsight> graded = members.stream()
                .filter(m -> m.finalGrade() != null && !m.finalGrade().isBlank()).toList();
        if (graded.size() < minRated) {
            return new GroupGrade(null, method,
                    "No rated member (need %d) — group grade not derivable".formatted(minRated), List.of());
        }
        double totalExp = graded.stream()
                .filter(m -> m.exposure() != null && m.exposure() > 0)
                .mapToDouble(GroupMemberInsight::exposure).sum();
        boolean useExp = totalExp > 0;

        double wsum = 0, widx = 0;
        int worstIdx = -1, anchorIdx = -1;
        double anchorW = -1;
        List<Object[]> raw = new ArrayList<>();   // {ref, grade, idx, weight}
        for (GroupMemberInsight m : graded) {
            int idx = Authorities.gradeIndex(m.finalGrade());
            double w = useExp ? (m.exposure() != null && m.exposure() > 0 ? m.exposure() : 0.0) : 1.0;
            wsum += w;
            widx += w * idx;
            if (idx > worstIdx) worstIdx = idx;
            if (w > anchorW) { anchorW = w; anchorIdx = idx; }
            raw.add(new Object[]{m.counterpartyRef(), m.finalGrade(), idx, w});
        }
        double wtot = wsum == 0 ? graded.size() : wsum;
        List<com.helix.decision.dto.GroupDtos.GroupGradeContribution> contribs = new ArrayList<>();
        for (Object[] r : raw) {
            double w = (double) r[3];
            contribs.add(new com.helix.decision.dto.GroupDtos.GroupGradeContribution(
                    (String) r[0], (String) r[1], (int) r[2], round2(w), round4(w / wtot)));
        }

        int groupIdx;
        String basis;
        switch (method) {
            case "WORST_OF" -> {
                groupIdx = clampIdx(worstIdx);
                basis = "Worst-of member grades = %s across %d rated member(s) on the AAA..D master scale — advisory"
                        .formatted(Authorities.GRADES.get(groupIdx), graded.size());
            }
            case "PARENT_ANCHORED" -> {
                groupIdx = clampIdx(anchorIdx + supportNotches);
                basis = "Parent-anchored (largest exposure) %+d support notch(es) = %s across %d rated member(s) — advisory"
                        .formatted(supportNotches, Authorities.GRADES.get(groupIdx), graded.size());
            }
            default -> {
                double avg = wsum > 0 ? widx / wsum : worstIdx;
                groupIdx = clampIdx(roundIdx(avg, rounding));
                basis = "%s average notch = %s across %d rated member(s) on the AAA..D master scale — advisory"
                        .formatted(useExp ? "Exposure-weighted" : "Equal-weight",
                                Authorities.GRADES.get(groupIdx), graded.size());
            }
        }
        return new GroupGrade(Authorities.GRADES.get(clampIdx(groupIdx)), method, basis, contribs);
    }

    private static int clampIdx(int idx) {
        return Math.max(0, Math.min(Authorities.GRADES.size() - 1, idx));
    }

    private static int roundIdx(double avg, String rounding) {
        // HALF_UP_WORSE rounds .5 toward the worse (higher) notch; HALF_UP_BETTER toward the better.
        return "HALF_UP_BETTER".equals(rounding)
                ? (int) -Math.round(-avg)
                : (int) Math.round(avg);
    }

    private static String str(Object o, String dflt) {
        return o == null || String.valueOf(o).isBlank() ? dflt : String.valueOf(o);
    }

    private static double num(Object o, double dflt) {
        if (o instanceof Number n) return n.doubleValue();
        try { return o == null ? dflt : Double.parseDouble(String.valueOf(o)); }
        catch (NumberFormatException e) { return dflt; }
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    private static double round4(double v) { return Math.round(v * 10_000.0) / 10_000.0; }

    // ----------------------------------------------------- helpers

    private static String pickGrade(List<GroupMemberInsight> members, boolean weakest) {
        Integer pickIdx = null;
        for (GroupMemberInsight m : members) {
            int idx = gradeIndex(m.finalGrade());
            if (idx < 0) continue;
            if (pickIdx == null || (weakest ? idx > pickIdx : idx < pickIdx)) {
                pickIdx = idx;
            }
        }
        return pickIdx == null ? null : GRADE_LADDER.get(pickIdx);
    }

    private static int gradeIndex(String grade) {
        if (grade == null) return -1;
        int i = GRADE_LADDER.indexOf(grade);
        return i < 0 ? GRADE_LADDER.size() - 1 : i;
    }

    private static List<String> composeConcentrations(Map<String, Double> ccy,
                                                      Map<String, Double> segment, double total) {
        List<String> out = new ArrayList<>();
        if (total > 0) {
            ccy.entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .limit(2)
                    .forEach(e -> out.add(pct(e.getValue() / total) + " in " + e.getKey()));
            segment.entrySet().stream()
                    .filter(e -> !"unknown".equalsIgnoreCase(e.getKey()))
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .limit(2)
                    .forEach(e -> out.add(pct(e.getValue() / total) + " " + e.getKey()));
        }
        return out;
    }

    private static List<String> composeCallouts(int belowHurdle, int overridden, int total,
                                                int withApp, String lowestGrade) {
        List<String> out = new ArrayList<>();
        if (total == 0) {
            out.add("Group has no members yet — no aggregate signal available.");
            return out;
        }
        if (withApp == 0) {
            out.add("No live applications across the group — nothing to roll up yet.");
        }
        if (belowHurdle > 0) {
            out.add("%d of %d member(s) currently price below the RAROC hurdle.".formatted(belowHurdle, withApp));
        }
        if (overridden > 0) {
            out.add("%d member(s) have an analyst-applied rating override (audited)."
                    .formatted(overridden));
        }
        if (lowestGrade != null) {
            out.add("Weakest grade in the group: " + lowestGrade + " — concentration cap may apply.");
        }
        return out;
    }

    private String composeNarrative(CounterpartyGroupDto group, GroupExposureDto exposure, int memberCount,
                                    int withApp, Map<String, Double> exposureByCcy, Double weightedPd,
                                    Double weightedRaroc, String lowestGrade, String highestGrade,
                                    int belowHurdle, int overridden) {
        StringBuilder sb = new StringBuilder();
        sb.append("**").append(nv(group.name())).append("** is a ");
        sb.append(group.multiCountry() ? "multi-country" : "single-country").append(" borrower group ");
        sb.append("under group RM `").append(nv(group.groupRmId())).append("`. ");
        sb.append("It carries ").append(memberCount).append(" tagged member(s)");
        sb.append(", ").append(exposure.obligorCount()).append(" of which are active obligors");
        sb.append("; ").append(withApp).append(" have a live application in the platform.");
        if (!exposureByCcy.isEmpty()) {
            sb.append(" Proposed exposure across the group: ");
            sb.append(exposureByCcy.entrySet().stream()
                    .map(e -> moneyShort(e.getValue()) + " " + e.getKey())
                    .reduce((a, b) -> a + " + " + b).orElse("—")).append(".");
        }
        if (weightedPd != null) {
            sb.append(" Exposure-weighted PD is ").append(pct(weightedPd)).append(".");
        }
        if (weightedRaroc != null) {
            sb.append(" Exposure-weighted RAROC is ").append(pct(weightedRaroc)).append(".");
        }
        if (lowestGrade != null) {
            sb.append(" Grade band spans **").append(highestGrade).append(" → ").append(lowestGrade).append("**.");
        }
        if (belowHurdle > 0 || overridden > 0) {
            sb.append(" Risk callouts: ");
            List<String> bits = new ArrayList<>();
            if (belowHurdle > 0) bits.add(belowHurdle + " member(s) below hurdle");
            if (overridden > 0) bits.add(overridden + " rating override(s)");
            sb.append(String.join(", ", bits)).append(".");
        }
        sb.append(" _Advisory only. Authoritative grades, capital and pricing are unchanged._");
        return sb.toString();
    }

    private static String pct(double v) {
        return String.format(Locale.UK, "%.1f%%", v * 100);
    }

    private static String moneyShort(double v) {
        if (v >= 1_000_000_000) return String.format(Locale.UK, "%.2fbn", v / 1_000_000_000);
        if (v >= 1_000_000) return String.format(Locale.UK, "%.1fm", v / 1_000_000);
        if (v >= 1_000) return String.format(Locale.UK, "%.0fk", v / 1_000);
        return String.format(Locale.UK, "%.0f", v);
    }

    private static String safe(String s) {
        return s == null || s.isBlank() ? "unknown" : s;
    }

    private static String nv(String s) {
        return s == null || s.isBlank() || "null".equalsIgnoreCase(s) ? "—" : s;
    }
}
