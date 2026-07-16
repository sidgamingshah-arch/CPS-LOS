package com.helix.counterparty.service;

import com.helix.common.audit.AuditService;
import com.helix.common.llm.LlmClient;
import com.helix.common.llm.LlmRequest;
import com.helix.common.llm.LlmResult;
import com.helix.common.web.ApiException;
import com.helix.counterparty.dto.InitiationDtos.GroupCandidate;
import com.helix.counterparty.dto.InitiationDtos.GroupSuggestionResult;
import com.helix.counterparty.dto.InitiationDtos.SiblingCandidate;
import com.helix.counterparty.entity.Counterparty;
import com.helix.counterparty.entity.CounterpartyGroup;
import com.helix.counterparty.repo.CounterpartyGroupRepository;
import com.helix.counterparty.repo.CounterpartyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Advisory auto-identification of the parent borrower group (PRD §1, "automated
 * ownership resolution / group fetching"). Suggestions are advisory — the human
 * still calls {@code POST /counterparties/{id}/group/{groupId}} to actually tag,
 * which stamps {@code audit.human(...)}. This service only stamps
 * {@code audit.ai("group-identification", ...)} when it produces a suggestion.
 *
 * <p>Scoring is a transparent fuzzy heuristic (no trained model) blending:
 * shared registration-number prefix, name-token jaccard, shared country/sector,
 * shared group RM. The reasoning ("signals") is returned so the analyst can see
 * <em>why</em> the suggestion was made.
 */
@Service
public class GroupIdentificationService {

    private static final Set<String> NAME_STOPWORDS = Set.of(
            "ltd", "limited", "pvt", "private", "llc", "llp", "inc", "co", "company", "corp",
            "plc", "the", "and", "spv", "holdings", "holding", "group", "international",
            "global", "trading", "industries", "industry");

    private static final double NAME_JACCARD_FLOOR = 0.30;
    private static final double GROUP_SUGGEST_FLOOR = 0.50;
    private static final double GROUP_RECOMMEND_FLOOR = 0.70;
    private static final double SIBLING_SUGGEST_FLOOR = 0.45;

    private final CounterpartyRepository counterparties;
    private final CounterpartyGroupRepository groups;
    private final AuditService audit;
    private final com.helix.common.governance.AiGovernanceClient governance;
    private final LlmClient llm;

    public GroupIdentificationService(CounterpartyRepository counterparties,
                                      CounterpartyGroupRepository groups, AuditService audit,
                                      com.helix.common.governance.AiGovernanceClient governance,
                                      LlmClient llm) {
        this.counterparties = counterparties;
        this.groups = groups;
        this.audit = audit;
        this.governance = governance;
        this.llm = llm;
    }

    @Transactional
    public GroupSuggestionResult suggest(Long counterpartyId, String actor) {
        Counterparty subject = counterparties.findById(counterpartyId)
                .orElseThrow(() -> ApiException.notFound("No counterparty: " + counterpartyId));
        governance.enforce(com.helix.common.governance.AiCapability.GROUP_SUGGEST, subject.getJurisdiction());

        Set<String> subjectTokens = nameTokens(subject.getLegalName());
        String subjectRegPrefix = registrationPrefix(subject.getRegistrationNo());

        List<Counterparty> all = counterparties.findAll();
        Map<Long, CounterpartyGroup> groupById = groups.findAll().stream()
                .collect(Collectors.toMap(CounterpartyGroup::getId, g -> g));

        // 1) Score every existing group: best matching member determines group score.
        List<GroupCandidate> groupMatches = new ArrayList<>();
        for (CounterpartyGroup g : groupById.values()) {
            if (subject.getGroupId() != null && subject.getGroupId().equals(g.getId())) continue;
            double best = 0.0;
            List<String> bestSignals = List.of();
            List<Counterparty> members = all.stream()
                    .filter(c -> g.getId().equals(c.getGroupId()))
                    .toList();
            for (Counterparty m : members) {
                Scored s = score(subject, subjectTokens, subjectRegPrefix, m);
                if (s.score > best) {
                    best = s.score;
                    bestSignals = s.signals;
                }
            }
            // Same group-RM nudges the score up a touch (separate signal).
            if (g.getGroupRmId() != null && g.getGroupRmId().equalsIgnoreCase(subject.getRmId())) {
                best = Math.min(1.0, best + 0.05);
                bestSignals = appendSignal(bestSignals, "shared group RM=" + g.getGroupRmId());
            }
            if (best >= GROUP_SUGGEST_FLOOR) {
                groupMatches.add(new GroupCandidate(g.getId(), g.getReference(), g.getName(),
                        g.getGroupRmId(), members.size(),
                        Math.round(best * 1000.0) / 1000.0,
                        bestSignals));
            }
        }
        groupMatches.sort(Comparator.comparingDouble(GroupCandidate::score).reversed());

        // 2) Ungrouped siblings — counterparties with no groupId that match the subject.
        List<SiblingCandidate> siblings = new ArrayList<>();
        for (Counterparty c : all) {
            if (c.getId().equals(subject.getId())) continue;
            if (c.getGroupId() != null) continue;
            if ("DISCARDED".equals(c.getLifecycleStatus())) continue;
            Scored s = score(subject, subjectTokens, subjectRegPrefix, c);
            if (s.score < SIBLING_SUGGEST_FLOOR) continue;
            siblings.add(new SiblingCandidate(c.getId(), c.getReference(), c.getLegalName(),
                    c.getCountry(), c.getSector(),
                    Math.round(s.score * 1000.0) / 1000.0, s.signals));
        }
        siblings.sort(Comparator.comparingDouble(SiblingCandidate::score).reversed());

        // 3) Recommendation. If there's any existing-group match above the suggest floor we
        // never jump straight to CREATE_NEW_GROUP — that would silently ignore a viable
        // parent. Only escalate to TAG when the top group is strong enough on its own.
        double topGroup = groupMatches.isEmpty() ? 0.0 : groupMatches.get(0).score();
        String recommendation;
        if (topGroup >= GROUP_RECOMMEND_FLOOR) {
            recommendation = "TAG_TO_EXISTING_GROUP";
        } else if (!groupMatches.isEmpty()) {
            recommendation = "REVIEW_CANDIDATES";
        } else if (siblings.size() >= 2) {
            recommendation = "CREATE_NEW_GROUP";
        } else if (!siblings.isEmpty()) {
            recommendation = "REVIEW_CANDIDATES";
        } else {
            recommendation = "NO_STRONG_MATCH";
        }
        double topScore = Math.max(topGroup, siblings.isEmpty() ? 0.0 : siblings.get(0).score());

        // Optional advisory LLM rationale per suggestion: when a bank has configured an external
        // model it appends one human-readable sentence to each candidate's signals, grounded ONLY
        // in the computed signals + similarity score. It never adds or removes a candidate and
        // never changes a similarity score, the recommendation or topScore — the human still tags
        // the group. Provider 'none' (default) → signals byte-identical to today.
        List<String> llmModels = new ArrayList<>();
        List<GroupCandidate> groupOut = draftGroupRationales(groupMatches, llmModels);
        List<SiblingCandidate> siblingOut = draftSiblingRationales(siblings, llmModels);

        CounterpartyGroup current = subject.getGroupId() == null ? null
                : groupById.get(subject.getGroupId());

        GroupSuggestionResult result = new GroupSuggestionResult(
                subject.getId(), subject.getReference(), subject.getLegalName(),
                subject.getGroupId(),
                current == null ? null : current.getReference(),
                groupOut, siblingOut, recommendation,
                topScore,
                true);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("recommendation", recommendation);
        detail.put("groupMatches", groupOut.size());
        detail.put("siblings", siblingOut.size());
        detail.put("topScore", result.topScore());
        detail.put("advisory", true);
        if (!llmModels.isEmpty()) {
            detail.put("llmDrafted", true);
            detail.put("llmModel", llmModels.get(llmModels.size() - 1));
        }
        audit.ai("group-identification", "GROUP_SUGGESTED", "Counterparty", subject.getReference(),
                "Suggested %d group(s) + %d ungrouped sibling(s); recommendation=%s; top=%.3f"
                        .formatted(groupOut.size(), siblingOut.size(), recommendation, result.topScore()),
                detail);
        return result;
    }

    // ------------------------------------------------------- advisory LLM rationale

    private List<GroupCandidate> draftGroupRationales(List<GroupCandidate> in, List<String> llmModels) {
        List<GroupCandidate> out = new ArrayList<>(in.size());
        for (GroupCandidate g : in) {
            List<String> sig = llmRationale("parent group", g.name() + " (" + g.reference() + ")",
                    g.score(), g.signals(), llmModels);
            out.add(sig == g.signals() ? g
                    : new GroupCandidate(g.groupId(), g.reference(), g.name(), g.groupRm(),
                            g.memberCount(), g.score(), sig));
        }
        return out;
    }

    private List<SiblingCandidate> draftSiblingRationales(List<SiblingCandidate> in, List<String> llmModels) {
        List<SiblingCandidate> out = new ArrayList<>(in.size());
        for (SiblingCandidate s : in) {
            List<String> sig = llmRationale("sibling counterparty", s.legalName() + " (" + s.reference() + ")",
                    s.score(), s.signals(), llmModels);
            out.add(sig == s.signals() ? s
                    : new SiblingCandidate(s.counterpartyId(), s.reference(), s.legalName(),
                            s.country(), s.sector(), s.score(), sig));
        }
        return out;
    }

    /**
     * Advisory LLM rationale for ONE candidate suggestion, grounded ONLY in the already-computed
     * deterministic signals + similarity score. On a usable result it returns the signals with one
     * plain-prose rationale sentence appended; it NEVER changes the similarity score and (being a
     * per-candidate augmentation) NEVER adds or removes a candidate. Returns the ORIGINAL signals
     * list unchanged when not configured / failed / empty — fail-soft, byte-identical default.
     */
    private List<String> llmRationale(String kind, String label, double score,
                                      List<String> signals, List<String> llmModels) {
        if (signals == null || signals.isEmpty()) {
            return signals;
        }
        String system = "You are drafting an ADVISORY one-sentence rationale for a suggested borrower-group "
                + "link in a wholesale-credit onboarding workflow. capability=group-identification. In ONE plain-"
                + "prose sentence, summarise WHY this " + kind + " is a candidate, using ONLY the similarity "
                + "signals and score supplied. Reuse the similarity score and every signal value verbatim — never "
                + "invent or change a value, and never add or drop a candidate. This is a suggestion only: a named "
                + "human still tags the group.";
        String user = "Candidate " + kind + ": " + label
                + "\nSimilarity score (0..1 — do not change): " + String.format(Locale.UK, "%.3f", score)
                + "\nComputed signals (source of truth): " + String.join("; ", signals);
        LlmResult r = safeComplete(LlmRequest.of("group-identification", system, user));
        if (!r.usable()) {
            return signals;
        }
        llmModels.add(r.model());
        List<String> augmented = new ArrayList<>(signals);
        augmented.add("AI rationale: " + r.text().strip());
        return augmented;
    }

    private LlmResult safeComplete(LlmRequest req) {
        try {
            LlmResult r = llm.complete(req);
            return r == null ? LlmResult.notConfigured() : r;
        } catch (Exception e) {
            return LlmResult.failed(e.getMessage());
        }
    }

    // ------------------------------------------------------- scoring internals

    private record Scored(double score, List<String> signals) {
    }

    private Scored score(Counterparty subject, Set<String> subjectTokens, String subjectRegPrefix,
                         Counterparty other) {
        List<String> signals = new ArrayList<>();
        double nameScore = jaccard(subjectTokens, nameTokens(other.getLegalName()));
        if (nameScore >= NAME_JACCARD_FLOOR) {
            signals.add("name-token overlap=" + round2(nameScore));
        }

        double regScore = 0.0;
        String otherPrefix = registrationPrefix(other.getRegistrationNo());
        if (subjectRegPrefix != null && otherPrefix != null
                && subjectRegPrefix.equals(otherPrefix)) {
            regScore = 0.6;
            signals.add("registration-prefix=" + subjectRegPrefix);
        }

        double countryBonus = 0.0;
        if (subject.getCountry() != null
                && subject.getCountry().equalsIgnoreCase(other.getCountry())) {
            countryBonus = 0.05;
            signals.add("shared country=" + subject.getCountry());
        }

        double sectorBonus = 0.0;
        if (Objects.equals(safeLower(subject.getSector()), safeLower(other.getSector()))
                && subject.getSector() != null) {
            sectorBonus = 0.05;
            signals.add("shared sector=" + subject.getSector());
        }

        double rmBonus = 0.0;
        if (subject.getRmId() != null && subject.getRmId().equalsIgnoreCase(other.getRmId())) {
            rmBonus = 0.05;
            signals.add("shared RM=" + subject.getRmId());
        }

        // Compose: max(name, regPrefix) as the core signal; add country/sector/rm bonuses
        // capped at 1.0. We ignore the bonuses entirely if the core signal is already 0,
        // since a sector match alone shouldn't imply a group link.
        double core = Math.max(nameScore >= NAME_JACCARD_FLOOR ? nameScore : 0.0, regScore);
        if (core == 0.0) {
            return new Scored(0.0, List.of());
        }
        double total = Math.min(1.0, core + countryBonus + sectorBonus + rmBonus);
        return new Scored(total, signals);
    }

    private static String registrationPrefix(String regNo) {
        if (regNo == null) return null;
        String cleaned = regNo.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        if (cleaned.length() < 5) return null;
        return cleaned.substring(0, 5);
    }

    private static Set<String> nameTokens(String name) {
        if (name == null) return Set.of();
        return Arrays.stream(name.toLowerCase().replaceAll("[^a-z0-9 ]", " ").split("\\s+"))
                .filter(t -> t.length() > 1 && !NAME_STOPWORDS.contains(t))
                .collect(Collectors.toCollection(HashSet::new));
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) inter.size() / union.size();
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static String safeLower(String s) {
        return s == null ? null : s.toLowerCase();
    }

    private static List<String> appendSignal(List<String> base, String sig) {
        List<String> out = new ArrayList<>(base);
        out.add(sig);
        return out;
    }
}
