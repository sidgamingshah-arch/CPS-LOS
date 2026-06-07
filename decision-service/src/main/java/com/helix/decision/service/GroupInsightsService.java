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

        for (GroupMemberDto m : exposure.members()) {
            List<LoanApplicationRefDto> apps = upstream.applicationsForCounterparty(m.reference());
            // pick the most-recent application by reference order (lexical fallback) or the first non-CLOSED.
            LoanApplicationRefDto latest = apps.stream()
                    .filter(a -> !"CLOSED".equalsIgnoreCase(a.status()))
                    .min(Comparator.comparing(LoanApplicationRefDto::reference).reversed())
                    .orElse(apps.isEmpty() ? null : apps.get(0));

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
                concentrations, callouts, members, narrative, true);

        audit.ai("group-insights", "GROUP_INSIGHTS_GENERATED", "Group", group.reference(),
                "Insights for group %s — %d member(s), %d with application, %d below hurdle, %d overridden"
                        .formatted(group.name(), members.size(), withApp, belowHurdle, overridden),
                Map.of("memberCount", members.size(), "membersWithApplication", withApp,
                        "membersBelowHurdle", belowHurdle, "membersOverridden", overridden,
                        "advisory", true));
        return gi;
    }

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
