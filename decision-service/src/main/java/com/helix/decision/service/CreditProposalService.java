package com.helix.decision.service;

import com.helix.common.audit.AuditService;
import com.helix.common.llm.LlmClient;
import com.helix.common.llm.LlmRequest;
import com.helix.common.llm.LlmResult;
import com.helix.common.web.ApiException;
import com.helix.decision.client.UpstreamClient;
import com.helix.decision.client.UpstreamClient.DealEnvelopeDto;
import com.helix.decision.client.UpstreamClient.MasterRecordDto;
import com.helix.decision.client.UpstreamClient.RiskSummaryDto;
import com.helix.decision.dto.GroupDtos.GroupInsights;
import com.helix.decision.dto.GroupDtos.GroupMemberInsight;
import com.helix.decision.entity.Covenant;
import com.helix.decision.entity.CreditDecision;
import com.helix.decision.entity.CreditProposal;
import com.helix.decision.repo.CovenantRepository;
import com.helix.decision.repo.CreditDecisionRepository;
import com.helix.decision.repo.CreditProposalRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Generates a formal credit proposal / committee memo (PRD §8 US-8.3). Strictly
 * grounded: every figure is quoted from the engines/services, with the source
 * endpoint stored in {@code citations}. No invention; AI never produces a
 * decision or a pricing figure — it merely composes a report that the named
 * approver edits and signs.
 */
@Service
public class CreditProposalService {

    private final CreditProposalRepository proposals;
    private final CovenantRepository covenants;
    private final CreditDecisionRepository decisions;
    private final UpstreamClient upstream;
    private final GroupInsightsService groupInsights;
    private final AuditService audit;
    private final LlmClient llm;

    public CreditProposalService(CreditProposalRepository proposals, CovenantRepository covenants,
                                 CreditDecisionRepository decisions, UpstreamClient upstream,
                                 GroupInsightsService groupInsights, AuditService audit,
                                 LlmClient llm) {
        this.proposals = proposals;
        this.covenants = covenants;
        this.decisions = decisions;
        this.upstream = upstream;
        this.groupInsights = groupInsights;
        this.audit = audit;
        this.llm = llm;
    }

    /** No-format entry point — resolves the deal-segment default (else STANDARD). Byte-identical to
     *  the pre-format behaviour for a standard corporate deal. */
    @Transactional
    public CreditProposal generate(String reference, String actor) {
        return generate(reference, null, actor);
    }

    /**
     * Generate the credit proposal under a chosen CAM {@code format}. The format's section SET,
     * ORDER and human titles come from a resolved {@code PROPOSAL_FORMAT} master; each section is
     * produced by a stable-key builder. Resolution: explicit {@code format} arg → else the deal
     * segment's default (ModelResolve-style most-specific pick) → else STANDARD. With no format the
     * resolved STANDARD layout is byte-identical to the pre-format universal proposal.
     *
     * <p>A format only reshapes the RENDERING — it is never a figure source. Every figure is quoted
     * from the same upstream services; the segment-specific builders render already-computed values
     * only (no new authoritative computation).</p>
     */
    @Transactional
    public CreditProposal generate(String reference, String format, String actor) {
        DealEnvelopeDto env = upstream.envelope(reference);
        RiskSummaryDto rs = upstream.riskSummary(reference);
        if (rs == null || rs.rating() == null) {
            throw ApiException.conflict("Cannot generate proposal — the deal must be rated first");
        }
        List<Covenant> covs = covenants.findByApplicationReference(reference);
        CreditDecision dec = decisions.findFirstByApplicationReferenceOrderByCreatedAtDesc(reference).orElse(null);

        ResolvedFormat fmt = resolveFormat(format, env.segment());

        // Optional advisory LLM narrative, grounded in the SAME figures rendered below (every figure
        // quoted verbatim in the prompt). When a provider is configured it adds ONE extra executive-
        // narrative prose section; the deterministic sections, structured citations, versioning and
        // the confirm gate are unchanged. Provider 'none' (default) → no section, markdown/html
        // byte-identical to today.
        LlmResult proposalDraft = llmNarrative(env, rs, covs, dec);
        boolean llmDrafted = proposalDraft.usable();

        Md md = new Md();
        md.h1("Credit proposal · " + env.applicationReference());
        md.muted("Prepared by Helix · grounded in platform data · awaiting named human review and sign-off");
        md.spacer();
        if (llmDrafted) {
            md.h2("Executive narrative (AI-drafted, advisory)");
            md.line(proposalDraft.text().strip());
        }

        // Assemble ONLY the resolved format's sections, in that order, via the keyed builders.
        // When the format does not list a standalone `sublimits` section, the sublimits block is
        // rendered inline right after the facilities table — exactly as the universal layout did.
        Ctx ctx = new Ctx(env, rs, covs, dec);
        Set<String> keys = new LinkedHashSet<>();
        for (Section s : fmt.sections()) keys.add(s.key());
        boolean inlineSublimits = !keys.contains("sublimits");
        for (Section s : fmt.sections()) {
            renderSection(s.key(), md, ctx, inlineSublimits);
        }

        Map<String, Object> citations = new LinkedHashMap<>();
        citations.put("envelope", "origination-service GET /api/applications/" + reference + "/envelope");
        citations.put("rating",   "risk-service GET /api/risk/" + reference + "/rating");
        citations.put("capital",  "risk-service GET /api/risk/" + reference + "/capital");
        citations.put("pricing",  "risk-service GET /api/risk/" + reference);
        citations.put("covenants","decision-service GET /api/decisions/" + reference + "/covenants");
        citations.put("decision", "decision-service GET /api/decisions/" + reference);

        int version = proposals.findFirstByApplicationReferenceOrderByVersionDesc(reference)
                .map(p -> p.getVersion() + 1).orElse(1);

        CreditProposal p = new CreditProposal();
        p.setApplicationReference(reference);
        p.setVersion(version);
        p.setMarkdown(md.markdown());
        p.setHtml(md.html());
        p.setCitations(citations);
        p.setSections(fmt.sections().stream().map(Section::title).toList());
        p.setFormat(fmt.code());
        p.setGeneratedBy(actor);
        CreditProposal saved = proposals.save(p);
        Map<String, Object> propDetail = new LinkedHashMap<>();
        propDetail.put("version", version);
        propDetail.put("format", fmt.code());
        propDetail.put("facilities", env.facilities() == null ? 0 : env.facilities().size());
        propDetail.put("collaterals", env.collaterals() == null ? 0 : env.collaterals().size());
        if (llmDrafted) {
            propDetail.put("llmDrafted", true);
            propDetail.put("llmModel", proposalDraft.model());
        }
        audit.ai("proposal-generator", "CREDIT_PROPOSAL_GENERATED", "Application", reference,
                "Generated credit proposal v%d (%s format, %d sections, grounded)"
                        .formatted(version, fmt.code(), p.getSections().size()),
                propDetail);
        return saved;
    }

    @Transactional(readOnly = true)
    public CreditProposal latest(String reference) {
        return proposals.findFirstByApplicationReferenceOrderByVersionDesc(reference)
                .orElseThrow(() -> ApiException.notFound("No credit proposal for " + reference));
    }

    @Transactional(readOnly = true)
    public List<CreditProposal> versions(String reference) {
        return proposals.findByApplicationReferenceOrderByVersionDesc(reference);
    }

    /**
     * Combined credit proposal across every member of a borrower group (PRD §8,
     * "combining credit proposal of group companies"). Stored as a normal
     * {@link CreditProposal} with {@code applicationReference = "GRP:" + groupRef}
     * so versioning/history reuse the existing repository, and the figures are
     * <em>quoted</em> from each member's upstream services — never invented and
     * never mutated. Stamped {@code audit.ai("proposal-generator", ...)}.
     */
    @Transactional
    public CreditProposal generateForGroup(String groupReference, String actor) {
        // The insights service already fetches group + exposure + per-member envelope/risk
        // and stamps audit.ai("GROUP_INSIGHTS_GENERATED"). Re-fetching them here doubles
        // the cross-service round-trips AND double-stamps the audit trail, so we read
        // everything we need off the insights record.
        GroupInsights insights = groupInsights.insights(groupReference, actor);

        Md md = new Md();
        md.h1("Combined credit proposal · " + nv(insights.groupName())
                + " (" + insights.groupReference() + ")");
        md.muted("Group-level rollup of every tagged member's authoritative figures. Advisory, grounded, "
                + "human-gated. No figure below is computed by the model — they are quoted verbatim from the "
                + "rating / capital / pricing / origination services.");
        md.spacer();

        // 1) Group summary
        md.h2("1. Group summary");
        md.bullets(
                bullet("Group reference", insights.groupReference()),
                bullet("Group RM", nv(insights.groupRm())),
                bullet("Country", nv(insights.country())),
                bullet("Multi-country", String.valueOf(insights.multiCountry())),
                bullet("Members tagged", String.valueOf(insights.memberCount())),
                bullet("Active obligors", String.valueOf(insights.obligorCount())),
                bullet("Members with live application", String.valueOf(insights.membersWithApplication())),
                bullet("Members below RAROC hurdle (advisory)", String.valueOf(insights.membersBelowHurdle())),
                bullet("Members with rating override", String.valueOf(insights.membersOverridden())));

        // 2) Group-level aggregates
        md.h2("2. Group-level aggregates (deterministic, unchanged)");
        Map<String, String> rollup = new LinkedHashMap<>();
        insights.totalExposureByCurrency().forEach((ccy, amt) ->
                rollup.put("Proposed exposure · " + ccy, String.format(Locale.UK, "%,.0f", amt)));
        if (insights.weightedAveragePd() != null) {
            rollup.put("Weighted-average PD", pct(insights.weightedAveragePd()));
        }
        if (insights.weightedAverageRaroc() != null) {
            rollup.put("Weighted-average RAROC", pct(insights.weightedAverageRaroc()));
        }
        if (insights.lowestGrade() != null) {
            rollup.put("Grade band (best → weakest)",
                    insights.highestGrade() + " → " + insights.lowestGrade());
        }
        if (insights.groupGrade() != null) {
            rollup.put("Group grade (advisory, derived)", insights.groupGrade());
            rollup.put("Group grade method", insights.groupGradeMethod());
        }
        md.kvBlock(rollup);

        // Section numbering is dynamic so we don't skip an integer when concentrations
        // or callouts are empty (a single-currency / single-segment book had been
        // rendering "2. → 4. → 5." with no "3.").
        int section = 3;
        if (!insights.concentrations().isEmpty()) {
            md.h2(section++ + ". Concentrations (advisory)");
            for (String c : insights.concentrations()) {
                md.line("- " + c);
            }
        }
        if (!insights.riskCallouts().isEmpty()) {
            md.h2(section++ + ". Risk callouts (advisory)");
            for (String c : insights.riskCallouts()) {
                md.line("- " + c);
            }
        }

        // Per-member sections — each pulls the member's deterministic figures verbatim.
        md.h2(section++ + ". Per-member breakdown");
        for (GroupMemberInsight m : insights.members()) {
            md.line("**" + m.counterpartyName() + "** · " + m.counterpartyRef()
                    + " · " + nv(m.segment()) + " · " + nv(m.recordType())
                    + " · RM " + nv(m.rm()));
            if (m.latestApplicationReference() == null) {
                md.line("_No live application on file._");
                continue;
            }
            Map<String, String> mv = new LinkedHashMap<>();
            mv.put("Latest application", m.latestApplicationReference()
                    + " · " + nv(m.applicationStatus()));
            if (m.finalGrade() != null) {
                mv.put("Rating (model → final)",
                        nv(m.modelGrade()) + " → " + m.finalGrade()
                                + (m.ratingConfirmed() ? " ✓" : " · unconfirmed")
                                + (m.ratingOverridden() ? " · OVERRIDDEN" : ""));
            }
            if (m.pd() != null) mv.put("PD", pct(m.pd()));
            if (m.exposure() != null) mv.put("Proposed exposure",
                    String.format(Locale.UK, "%,.0f", m.exposure()) + " " + nv(m.currency()));
            mv.put("Facilities", String.valueOf(m.facilityCount()));
            mv.put("Covenants", String.valueOf(m.covenantCount()));
            if (m.recommendedRate() != null) {
                mv.put("Pricing (advisory)",
                        "rate " + pct(m.recommendedRate())
                                + " · RAROC " + (m.raroc() == null ? "—" : pct(m.raroc()))
                                + " vs hurdle " + (m.hurdleRaroc() == null ? "—" : pct(m.hurdleRaroc()))
                                + (m.belowHurdle() ? " · BELOW HURDLE" : ""));
            }
            md.kvBlock(mv);
        }

        // Narrative
        md.h2(section++ + ". Advisory narrative");
        md.line(insights.narrative());

        // Provenance
        md.h2(section + ". Provenance and governance");
        md.bullets(
                bullet("Generated by", "Helix · proposal-generator (AI-assisted; human signs)"),
                bullet("Grounding",
                        "Every figure above is quoted verbatim from a platform service — none are invented or "
                                + "recomputed by this report"),
                bullet("Approval",
                        "AI cannot approve. A named human at the required group-level authority must sign each "
                                + "member proposal AND this combined rollup."),
                bullet("Member proposals",
                        "Linkages to per-member credit proposals remain authoritative; this rollup never "
                                + "supersedes them."));

        // Citations: one upstream lookup per member + the group endpoints.
        Map<String, Object> citations = new LinkedHashMap<>();
        citations.put("group",
                "counterparty-service GET /api/initiation/groups/by-reference/" + groupReference);
        citations.put("groupExposure",
                "counterparty-service GET /api/initiation/groups/by-reference/" + groupReference + "/exposure");
        Map<String, Object> memberCitations = new LinkedHashMap<>();
        for (GroupMemberInsight m : insights.members()) {
            memberCitations.put(m.counterpartyRef(),
                    "origination-service GET /api/applications/by-counterparty/" + m.counterpartyRef());
        }
        citations.put("members", memberCitations);

        String groupKey = "GRP:" + groupReference;
        int version = proposals.findFirstByApplicationReferenceOrderByVersionDesc(groupKey)
                .map(p -> p.getVersion() + 1).orElse(1);

        CreditProposal p = new CreditProposal();
        p.setApplicationReference(groupKey);
        p.setVersion(version);
        p.setMarkdown(md.markdown());
        p.setHtml(md.html());
        p.setCitations(citations);
        p.setSections(List.of(
                "Group summary", "Group-level aggregates", "Concentrations", "Risk callouts",
                "Per-member breakdown", "Advisory narrative", "Provenance"));
        // The combined group rollup has its own bespoke section set (not the per-deal CAM layout);
        // it is stamped STANDARD so the format column is populated without changing that layout.
        p.setFormat("STANDARD");
        p.setGeneratedBy(actor);
        CreditProposal saved = proposals.save(p);
        audit.ai("proposal-generator", "GROUP_CREDIT_PROPOSAL_GENERATED", "Group", insights.groupReference(),
                "Generated combined group proposal v%d for %s — %d member(s), %d with application"
                        .formatted(version, nv(insights.groupName()), insights.memberCount(), insights.membersWithApplication()),
                Map.of("version", version,
                        "memberCount", insights.memberCount(),
                        "membersWithApplication", insights.membersWithApplication(),
                        "membersBelowHurdle", insights.membersBelowHurdle(),
                        "advisory", true));
        return saved;
    }

    // ----------------------------------------------------------- section builders (keyed)

    /** Immutable bundle of the deal figures every section builder renders from. */
    private record Ctx(DealEnvelopeDto env, RiskSummaryDto rs, List<Covenant> covs, CreditDecision dec) {
    }

    /** Dispatch a stable section key to its builder. Unknown keys are skipped (forward-compatible). */
    private void renderSection(String key, Md md, Ctx ctx, boolean inlineSublimits) {
        switch (key) {
            case "executive_summary" -> secExecutiveSummary(md, ctx);
            case "facilities" -> secFacilities(md, ctx, inlineSublimits);
            case "sublimits" -> secSublimits(md, ctx);
            case "collateral" -> secCollateral(md, ctx);
            case "financials" -> secFinancials(md, ctx);
            case "ratios" -> secRatios(md, ctx);
            case "rating" -> secRating(md, ctx);
            case "capital" -> secCapital(md, ctx);
            case "pricing" -> secPricing(md, ctx);
            case "covenants" -> secCovenants(md, ctx);
            case "routing" -> secRouting(md, ctx);
            case "provenance" -> secProvenance(md, ctx);
            // segment-specific — render already-computed figures only (no new authoritative math)
            case "dscr_waterfall" -> secDscrWaterfall(md, ctx);
            case "rent_roll" -> secRentRoll(md, ctx);
            case "scf_program" -> secScfProgram(md, ctx);
            default -> { /* unknown key — skip */ }
        }
    }

    private void secExecutiveSummary(Md md, Ctx ctx) {
        DealEnvelopeDto env = ctx.env();
        RiskSummaryDto rs = ctx.rs();
        md.h2("1. Executive summary");
        md.bullets(
                bullet("Borrower", env.counterpartyName()),
                bullet("Jurisdiction · segment", env.jurisdiction() + " · " + env.segment()),
                bullet("Proposed total exposure", money(env.totalProposedAmount(), env.currency())),
                bullet("Number of proposed facilities", String.valueOf(env.facilities() == null ? 0 : env.facilities().size())),
                bullet("Indicative tenor", env.tenorMonths() + " months"),
                bullet("Final grade (analyst proposed / approver confirmed)", rs.rating().finalGrade()
                        + (Boolean.TRUE.equals(rs.rating().confirmed()) ? " ✓" : " · unconfirmed")),
                bullet("RAROC (advisory)", rs.pricing() == null ? "—" :
                        pct(rs.pricing().raroc()) + " vs hurdle " + pct(rs.pricing().hurdleRaroc())
                                + (rs.pricing().belowHurdle() ? " · BELOW HURDLE" : "")));
    }

    private void secFacilities(Md md, Ctx ctx, boolean inlineSublimits) {
        DealEnvelopeDto env = ctx.env();
        md.h2("2. Facilities proposed");
        if (env.facilities() == null || env.facilities().isEmpty()) {
            md.line("_No facilities recorded._");
        } else {
            md.table(new String[]{"Ord", "Type", "Amount", "Tenor", "Purpose", "Indicative rate"});
            for (var f : env.facilities()) {
                md.row(String.valueOf(f.ordinal() + (f.primary() ? "★" : "")),
                        f.facilityType(), money(f.amount(), f.currency()),
                        f.tenorMonths() + "m", nv(f.purpose()),
                        f.indicativeRate() == null ? "—" : pct(f.indicativeRate()));
            }
        }
        if (inlineSublimits) secSublimits(md, ctx);
    }

    private void secSublimits(Md md, Ctx ctx) {
        DealEnvelopeDto env = ctx.env();
        // 2b) Sublimits and interchangeability per facility
        boolean anySublimits = env.facilities() != null && env.facilities().stream()
                .anyMatch(f -> f.sublimits() != null && !f.sublimits().isEmpty());
        if (anySublimits) {
            md.h2("2b. Sublimits and interchangeability");
            for (var f : env.facilities()) {
                if (f.sublimits() == null || f.sublimits().isEmpty()) continue;
                md.line("**" + f.facilityType() + " · " + money(f.amount(), f.currency()) + "** — sublimit total "
                        + money(f.sublimitTotal(), f.currency()) + " · headroom " + money(f.sublimitHeadroom(), f.currency()));
                md.table(new String[]{"Code", "Product", "Amount", "Tenor", "Interchangeable group", "Purpose"});
                for (var s : f.sublimits()) {
                    md.row(s.code(), s.productType(), money(s.amount(), s.currency()),
                            s.tenorMonths() == null ? "—" : s.tenorMonths() + "m",
                            s.interchangeableGroup() == null ? "fixed (hard cap)" : s.interchangeableGroup(),
                            nv(s.purpose()));
                }
                if (f.interchangeabilityGroups() != null && !f.interchangeabilityGroups().isEmpty()) {
                    md.line("Fungibility pools (utilisation may move freely within each):");
                    for (var g : f.interchangeabilityGroups()) {
                        md.line("- **" + g.groupKey() + "** · combined cap "
                                + money(g.combinedCap(), g.currency()) + " · members: "
                                + String.join(", ", g.memberCodes()));
                    }
                }
            }
        }
    }

    private void secCollateral(Md md, Ctx ctx) {
        DealEnvelopeDto env = ctx.env();
        md.h2("3. Collateral and security");
        if (env.collaterals() == null || env.collaterals().isEmpty()) {
            md.line("_Unsecured / no collateral recorded._");
        } else {
            md.table(new String[]{"Type", "Description", "Market value", "Haircut", "Effective", "Perfection"});
            for (var c : env.collaterals()) {
                md.row(c.collateralType(), nv(c.description()), money(c.marketValue(), env.currency()),
                        pct(c.haircut()), money(c.effectiveValue(), env.currency()), c.perfectionStatus());
            }
            md.line("**Total effective coverage:** " + money(env.totalCollateralCover(), env.currency()));
        }
    }

    private void secFinancials(Md md, Ctx ctx) {
        DealEnvelopeDto env = ctx.env();
        md.h2("4. Financial position (latest period)");
        md.kvBlock(format(env.latestFinancials(), Map.of(
                "REVENUE", "Revenue", "EBITDA", "EBITDA", "PAT", "PAT", "TOTAL_DEBT", "Total debt",
                "CASH", "Cash", "NET_WORTH", "Net worth", "CFO", "Cash flow from ops")));
    }

    private void secRatios(Md md, Ctx ctx) {
        DealEnvelopeDto env = ctx.env();
        md.h2("Ratios");
        md.kvBlock(formatRatios(env.ratios(), Map.of(
                "NET_LEVERAGE", "Net leverage", "INTEREST_COVERAGE", "Interest coverage",
                "DSCR", "DSCR", "EBITDA_MARGIN", "EBITDA margin", "CURRENT_RATIO", "Current ratio",
                "GEARING", "Gearing", "RETURN_ON_EQUITY", "Return on equity")));
    }

    private void secRating(Md md, Ctx ctx) {
        RiskSummaryDto rs = ctx.rs();
        md.h2("5. Risk rating");
        md.line("Model proposed **" + rs.rating().modelGrade() + "** · final **" + rs.rating().finalGrade()
                + "** · PD " + pct(rs.rating().pd()));
        if (rs.rating().overridden()) {
            md.line("> **Override applied** — escalation: " + rs.rating().escalated());
        }
    }

    private void secCapital(Md md, Ctx ctx) {
        DealEnvelopeDto env = ctx.env();
        RiskSummaryDto rs = ctx.rs();
        md.h2("6. Capital projection (for RAROC)");
        md.line("_Note: the bank's capital engine remains the system of record. This figure is an internal projection used by the pricing engine only._");
        if (rs.capital() != null) {
            md.kvBlock(Map.of(
                    "Exposure class", rs.capital().exposureClass(),
                    "RWA (projected)", money(rs.capital().rwa(), env.currency()),
                    "Capital projection", money(rs.capital().capitalRequired(), env.currency())));
        }
    }

    private void secPricing(Md md, Ctx ctx) {
        RiskSummaryDto rs = ctx.rs();
        md.h2("7. Risk-adjusted pricing (advisory)");
        if (rs.pricing() != null) {
            md.kvBlock(Map.of(
                    "Recommended rate", pct(rs.pricing().recommendedRate()),
                    "RAROC", pct(rs.pricing().raroc()),
                    "Hurdle", pct(rs.pricing().hurdleRaroc()),
                    "Status", rs.pricing().belowHurdle() ? "Below hurdle — escalate" : "Clears hurdle"));
        }
    }

    private void secCovenants(Md md, Ctx ctx) {
        List<Covenant> covs = ctx.covs();
        md.h2("8. Covenants");
        if (covs.isEmpty()) {
            md.line("_No covenants set._");
        } else {
            md.table(new String[]{"Type", "Metric", "Test", "Frequency", "Severity"});
            for (Covenant c : covs) {
                md.row(c.getCovenantType(), c.getMetric(), c.getOperator() + " " + c.getThreshold(),
                        c.getTestFrequency(), c.getBreachSeverity());
            }
        }
    }

    private void secRouting(Md md, Ctx ctx) {
        CreditDecision dec = ctx.dec();
        md.h2("9. Routing & decision");
        if (dec != null) {
            md.line("Routed to **" + dec.getRequiredAuthority() + "** authority. Status: " + dec.getStatus()
                    + (dec.getOutcome() == null ? "" : " · " + dec.getOutcome() + " by " + dec.getDecidedBy()));
            if (dec.getDeviations() != null && !dec.getDeviations().isEmpty()) {
                md.line("Deviations:");
                dec.getDeviations().forEach(d -> md.line("- " + d));
            }
            if (dec.getConditions() != null && !dec.getConditions().isEmpty()) {
                md.line("Conditions:");
                dec.getConditions().forEach(d -> md.line("- " + d));
            }
        } else {
            md.line("_Not yet routed for approval._");
        }
    }

    private void secProvenance(Md md, Ctx ctx) {
        md.h2("10. Provenance and governance");
        md.bullets(
                bullet("Generated by", "Helix · proposal-generator (AI-assisted, human signs)"),
                bullet("Grounding", "Every figure above is quoted from a platform service; no figure is invented by the model"),
                bullet("Approval", "AI cannot approve. A named human at the required authority must sign."));
    }

    // ---- segment-specific builders — render EXISTING figures only, graceful when absent ----

    private void secDscrWaterfall(Md md, Ctx ctx) {
        DealEnvelopeDto env = ctx.env();
        md.h2("DSCR & cashflow waterfall (advisory)");
        md.line("_Coverage figures quoted from the deal's spread — no figure is recomputed here._");
        Map<String, Double> ratios = env.ratios();
        Map<String, Double> fin = env.latestFinancials();
        Map<String, String> kv = new LinkedHashMap<>();
        putRatio(kv, ratios, "DSCR", "DSCR (x)");
        putRatio(kv, ratios, "INTEREST_COVERAGE", "Interest coverage (x)");
        putMoney(kv, fin, "CFO", "Cash flow from ops", env.currency());
        putMoney(kv, fin, "TOTAL_DEBT", "Total debt", env.currency());
        if (kv.isEmpty()) md.line("_No DSCR / cashflow data captured for this deal — not applicable._");
        else md.kvBlock(kv);
    }

    private void secRentRoll(Md md, Ctx ctx) {
        DealEnvelopeDto env = ctx.env();
        md.h2("Rent roll & lease profile (advisory)");
        md.line("_Lease-rental figures quoted from the deal's spread — no figure is recomputed here._");
        Map<String, Double> ratios = env.ratios();
        Map<String, String> kv = new LinkedHashMap<>();
        putRatio(kv, ratios, "CAM_RENTAL_DSCR", "Rental cover (x of debt service)");
        putRatio(kv, ratios, "CAM_LTV", "Loan-to-value (x)");
        putRatio(kv, ratios, "DSCR", "DSCR (x)");
        if (kv.isEmpty()) md.line("_No lease-rental data captured for this deal — not applicable._");
        else md.kvBlock(kv);
    }

    private void secScfProgram(Md md, Ctx ctx) {
        DealEnvelopeDto env = ctx.env();
        md.h2("Supply-chain-finance programme (advisory)");
        md.line("_Programme figures quoted from the deal's spread — no figure is recomputed here._");
        Map<String, Double> ratios = env.ratios();
        Map<String, String> kv = new LinkedHashMap<>();
        putRatio(kv, ratios, "ANCHOR_DEPENDENCE", "Anchor dependence (x)");
        putRatio(kv, ratios, "DILUTION_RATE", "Invoice dilution rate (x)");
        putRatio(kv, ratios, "RECEIVABLE_DAYS", "Anchor receivable days");
        if (kv.isEmpty()) md.line("_No supply-chain-finance programme data captured for this deal — not applicable._");
        else md.kvBlock(kv);
    }

    private static void putRatio(Map<String, String> kv, Map<String, Double> ratios, String key, String label) {
        Double v = ratios == null ? null : ratios.get(key);
        if (v != null) kv.put(label, String.format(Locale.UK, "%.2f", v));
    }

    private static void putMoney(Map<String, String> kv, Map<String, Double> vals, String key, String label, String ccy) {
        Double v = vals == null ? null : vals.get(key);
        if (v != null) kv.put(label, money(v, ccy));
    }

    // ----------------------------------------------------------- format resolution

    /** A section in a resolved CAM format: a stable builder {@code key} + its human {@code title}. */
    public record Section(String key, String title) {
    }

    /** A resolved CAM proposal format: code, label, defaulting segment, and its ordered sections. */
    public record ResolvedFormat(String code, String label, String segment, List<Section> sections) {
    }

    /** A listing row for the format picker (adds {@code recommended} for a given segment). */
    public record ProposalFormatView(String code, String label, String segment,
                                     List<Section> sections, boolean recommended) {
    }

    /**
     * Resolve the format to assemble under. Precedence: explicit {@code code} (matched against a
     * PROPOSAL_FORMAT master, else STANDARD) → the deal {@code segment}'s default (most-specific
     * PROPOSAL_FORMAT whose {@code segment} matches) → STANDARD. Never throws — a config-service
     * outage falls back to the built-in STANDARD layout (byte-identical to the universal proposal).
     */
    private ResolvedFormat resolveFormat(String code, String segment) {
        List<MasterRecordDto> recs = upstream.masters("PROPOSAL_FORMAT");
        if (code != null && !code.isBlank()) {
            for (MasterRecordDto r : recs) {
                if (code.equalsIgnoreCase(r.recordKey())) return toResolved(r);
            }
            // Explicit-but-unknown code (or explicit STANDARD without a seed): fall back to STANDARD.
            return standardOrBuiltin(recs);
        }
        if (segment != null && !segment.isBlank()) {
            MasterRecordDto best = null;
            for (MasterRecordDto r : recs) {
                Object seg = payload(r).get("segment");
                if (seg != null && segment.equalsIgnoreCase(String.valueOf(seg))) {
                    best = r;   // unique per segment in the seed; last-wins is deterministic on a stable list
                }
            }
            if (best != null) return toResolved(best);
        }
        return standardOrBuiltin(recs);
    }

    private ResolvedFormat standardOrBuiltin(List<MasterRecordDto> recs) {
        for (MasterRecordDto r : recs) {
            if ("STANDARD".equalsIgnoreCase(r.recordKey())) {
                ResolvedFormat f = toResolved(r);
                if (!f.sections().isEmpty()) return f;
            }
        }
        return builtinStandard();
    }

    /**
     * The built-in STANDARD layout — the universal proposal's sections, in the universal order,
     * with the exact human titles previously persisted. Used only when config-service is unreachable
     * or has no STANDARD record; the seeded STANDARD master mirrors it, so the output is identical
     * either way.
     */
    private static ResolvedFormat builtinStandard() {
        return new ResolvedFormat("STANDARD", "Standard universal CAM", null, List.of(
                new Section("executive_summary", "Executive summary"),
                new Section("facilities", "Facilities proposed"),
                new Section("collateral", "Collateral and security"),
                new Section("financials", "Financials"),
                new Section("ratios", "Ratios"),
                new Section("rating", "Rating"),
                new Section("capital", "Capital projection"),
                new Section("pricing", "Pricing"),
                new Section("covenants", "Covenants"),
                new Section("routing", "Routing & decision"),
                new Section("provenance", "Provenance")));
    }

    @SuppressWarnings("unchecked")
    private ResolvedFormat toResolved(MasterRecordDto r) {
        Map<String, Object> p = payload(r);
        String label = str(p.get("label"), r.recordKey());
        String segment = str(p.get("segment"), null);
        List<Section> secs = new ArrayList<>();
        Object raw = p.get("sections");
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Object k = m.get("key");
                    if (k == null || String.valueOf(k).isBlank()) continue;
                    String key = String.valueOf(k);
                    String title = m.get("title") == null ? key : String.valueOf(m.get("title"));
                    secs.add(new Section(key, title));
                }
            }
        }
        if (secs.isEmpty()) secs = builtinStandard().sections();
        return new ResolvedFormat(r.recordKey(), label, segment, secs);
    }

    /**
     * The available proposal formats for the picker (read-only). Always includes STANDARD; when a
     * {@code segment} is supplied the segment default is flagged {@code recommended} and sorted first.
     */
    @Transactional(readOnly = true)
    public List<ProposalFormatView> proposalFormats(String segment) {
        List<MasterRecordDto> recs = upstream.masters("PROPOSAL_FORMAT");
        List<ResolvedFormat> formats = new ArrayList<>();
        boolean hasStandard = false;
        for (MasterRecordDto r : recs) {
            formats.add(toResolved(r));
            if ("STANDARD".equalsIgnoreCase(r.recordKey())) hasStandard = true;
        }
        if (!hasStandard) formats.add(0, builtinStandard());
        String recommended = resolveFormat(null, segment).code();
        List<ProposalFormatView> views = new ArrayList<>();
        for (ResolvedFormat f : formats) {
            views.add(new ProposalFormatView(f.code(), f.label(), f.segment(), f.sections(),
                    f.code().equalsIgnoreCase(recommended)));
        }
        // Recommended first (when a segment was given), then STANDARD, then alphabetical by code.
        views.sort((a, b) -> {
            if (a.recommended() != b.recommended()) return a.recommended() ? -1 : 1;
            boolean as = "STANDARD".equalsIgnoreCase(a.code());
            boolean bs = "STANDARD".equalsIgnoreCase(b.code());
            if (as != bs) return as ? -1 : 1;
            return a.code().compareToIgnoreCase(b.code());
        });
        return views;
    }

    private static Map<String, Object> payload(MasterRecordDto r) {
        return r.payload() == null ? Map.of() : r.payload();
    }

    private static String str(Object o, String dflt) {
        if (o == null) return dflt;
        String s = String.valueOf(o);
        return s.isBlank() ? dflt : s;
    }

    // ----------------------------------------------------------- advisory LLM narrative

    /**
     * Advisory LLM narrative for the per-deal proposal, grounded strictly in the deterministic
     * figures the report renders (amount, grade, PD, pricing, ratios, routing). The model is told
     * to quote every figure verbatim and invent nothing, and never to recommend a decision. Returns
     * the {@link LlmResult} so the caller keeps the deterministic render on {@code none}/failure/empty.
     */
    private LlmResult llmNarrative(DealEnvelopeDto env, RiskSummaryDto rs, List<Covenant> covs, CreditDecision dec) {
        String system = "You are drafting an ADVISORY executive narrative for a wholesale-credit proposal "
                + "(capability=proposal-draft). Write grounded, professional prose using ONLY the facts supplied. "
                + "Quote every figure (amount, grade, PD, rate, RAROC, hurdle, ratios) exactly as given and never "
                + "invent, estimate or change a value. Do not approve, reject or recommend a decision; a named human "
                + "at the required authority signs. Reply with 3-5 sentences of plain prose.";
        StringBuilder user = new StringBuilder();
        user.append("Borrower: ").append(nv(env.counterpartyName())).append("; jurisdiction/segment: ")
                .append(nv(env.jurisdiction())).append(" / ").append(nv(env.segment())).append(".\n");
        user.append("Proposed total exposure: ").append(money(env.totalProposedAmount(), env.currency()))
                .append(" over ").append(env.tenorMonths()).append(" months across ")
                .append(env.facilities() == null ? 0 : env.facilities().size()).append(" facility line(s).\n");
        if (rs.rating() != null) {
            user.append("Rating: model ").append(nv(rs.rating().modelGrade())).append(", final ")
                    .append(nv(rs.rating().finalGrade())).append(", PD ").append(pct(rs.rating().pd()))
                    .append(rs.rating().overridden() ? " (override applied)" : "").append(".\n");
        }
        if (rs.capital() != null) {
            user.append("Capital projection: exposure class ").append(nv(rs.capital().exposureClass()))
                    .append(", RWA ").append(money(rs.capital().rwa(), env.currency())).append(".\n");
        }
        if (rs.pricing() != null) {
            user.append("Pricing (advisory): rate ").append(pct(rs.pricing().recommendedRate()))
                    .append(", RAROC ").append(pct(rs.pricing().raroc())).append(" vs hurdle ")
                    .append(pct(rs.pricing().hurdleRaroc()))
                    .append(rs.pricing().belowHurdle() ? " (below hurdle)" : "").append(".\n");
        }
        if (env.ratios() != null && !env.ratios().isEmpty()) {
            user.append("Key ratios: ").append(env.ratios()).append(".\n");
        }
        user.append("Covenants set: ").append(covs.size()).append(".\n");
        if (dec != null) {
            user.append("Routing: required authority ").append(nv(dec.getRequiredAuthority()))
                    .append(", status ").append(nv(dec.getStatus()))
                    .append(dec.getOutcome() == null ? "" : (", outcome " + dec.getOutcome())).append(".\n");
        }
        return safeComplete(LlmRequest.of("proposal-draft", system, user.toString()));
    }

    private LlmResult safeComplete(LlmRequest req) {
        try {
            LlmResult r = llm.complete(req);
            return r == null ? LlmResult.notConfigured() : r;
        } catch (Exception e) {
            return LlmResult.failed(e.getMessage());
        }
    }

    // ----------------------------------------------------------- helpers

    private static String[] bullet(String k, String v) {
        return new String[]{k, v};
    }

    private static String money(double v, String ccy) {
        return String.format(Locale.UK, "%,.0f", v) + (ccy == null ? "" : " " + ccy);
    }

    private static String pct(double v) {
        return String.format(Locale.UK, "%.2f%%", v * 100);
    }

    private static String nv(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    private static Map<String, String> format(Map<String, Double> values, Map<String, String> labels) {
        if (values == null) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        labels.forEach((k, label) -> {
            Double v = values.get(k);
            if (v != null) out.put(label, String.format(Locale.UK, "%,.0f", v));
        });
        return out;
    }

    private static Map<String, String> formatRatios(Map<String, Double> ratios, Map<String, String> labels) {
        if (ratios == null) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        labels.forEach((k, label) -> {
            Double v = ratios.get(k);
            if (v == null) return;
            out.put(label, k.endsWith("MARGIN") || k.endsWith("EQUITY") || k.endsWith("ROE")
                    ? pct(v) : String.format(Locale.UK, "%.2f", v));
        });
        return out;
    }

    /**
     * Minimal Markdown + HTML accumulator (no external templating engine). Tracks an
     * {@code inTable} flag so non-table blocks emitted between tables close the previous
     * tbody/table cleanly, and {@code html()} doesn't emit a dangling
     * {@code </tbody></table>} when no table was ever opened.
     */
    private static class Md {
        private final StringBuilder mdBuf = new StringBuilder();
        private final StringBuilder htmlBuf = new StringBuilder("<article class='credit-proposal'>");
        private boolean inTable = false;

        private void closeTableIfOpen() {
            if (inTable) {
                htmlBuf.append("</tbody></table>");
                inTable = false;
            }
        }

        void h1(String t) { closeTableIfOpen(); mdBuf.append("# ").append(t).append("\n\n"); htmlBuf.append("<h1>").append(esc(t)).append("</h1>"); }
        void h2(String t) { closeTableIfOpen(); mdBuf.append("## ").append(t).append("\n\n"); htmlBuf.append("<h2>").append(esc(t)).append("</h2>"); }
        void line(String s) { closeTableIfOpen(); mdBuf.append(s).append("\n\n"); htmlBuf.append("<p>").append(mdToHtml(s)).append("</p>"); }
        void spacer() { closeTableIfOpen(); mdBuf.append("\n"); htmlBuf.append("<div class='spacer'></div>"); }
        void muted(String s) { closeTableIfOpen(); mdBuf.append("_").append(s).append("_\n\n"); htmlBuf.append("<p class='muted'>").append(esc(s)).append("</p>"); }
        void bullets(String[]... pairs) {
            closeTableIfOpen();
            htmlBuf.append("<ul>");
            for (String[] p : pairs) {
                mdBuf.append("- **").append(p[0]).append(":** ").append(p[1]).append("\n");
                htmlBuf.append("<li><b>").append(esc(p[0])).append(":</b> ").append(esc(p[1])).append("</li>");
            }
            mdBuf.append("\n");
            htmlBuf.append("</ul>");
        }
        void kvBlock(Map<String, String> kv) {
            closeTableIfOpen();
            htmlBuf.append("<table class='kv'>");
            kv.forEach((k, v) -> {
                mdBuf.append("- **").append(k).append(":** ").append(v).append("\n");
                htmlBuf.append("<tr><td>").append(esc(k)).append("</td><td>").append(esc(v)).append("</td></tr>");
            });
            mdBuf.append("\n");
            htmlBuf.append("</table>");
        }
        void table(String[] cols) {
            closeTableIfOpen();
            mdBuf.append("| ").append(String.join(" | ", cols)).append(" |\n");
            mdBuf.append("|").append("---|".repeat(cols.length)).append("\n");
            htmlBuf.append("<table><thead><tr>");
            for (String c : cols) htmlBuf.append("<th>").append(esc(c)).append("</th>");
            htmlBuf.append("</tr></thead><tbody>");
            inTable = true;
        }
        void row(String... cells) {
            mdBuf.append("| ").append(String.join(" | ", cells)).append(" |\n");
            htmlBuf.append("<tr>");
            for (String c : cells) htmlBuf.append("<td>").append(esc(c)).append("</td>");
            htmlBuf.append("</tr>");
        }
        String markdown() { return mdBuf.toString(); }
        String html() { closeTableIfOpen(); return htmlBuf.append("</article>").toString(); }

        private String esc(String s) {
            return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
        private String mdToHtml(String s) {
            return esc(s).replaceAll("\\*\\*(.+?)\\*\\*", "<b>$1</b>").replaceAll("_(.+?)_", "<i>$1</i>");
        }
    }
}
