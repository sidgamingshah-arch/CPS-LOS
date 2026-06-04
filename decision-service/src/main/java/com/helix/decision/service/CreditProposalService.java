package com.helix.decision.service;

import com.helix.common.audit.AuditService;
import com.helix.common.web.ApiException;
import com.helix.decision.client.UpstreamClient;
import com.helix.decision.client.UpstreamClient.DealEnvelopeDto;
import com.helix.decision.client.UpstreamClient.RiskSummaryDto;
import com.helix.decision.entity.Covenant;
import com.helix.decision.entity.CreditDecision;
import com.helix.decision.entity.CreditProposal;
import com.helix.decision.repo.CovenantRepository;
import com.helix.decision.repo.CreditDecisionRepository;
import com.helix.decision.repo.CreditProposalRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
    private final AuditService audit;

    public CreditProposalService(CreditProposalRepository proposals, CovenantRepository covenants,
                                 CreditDecisionRepository decisions, UpstreamClient upstream, AuditService audit) {
        this.proposals = proposals;
        this.covenants = covenants;
        this.decisions = decisions;
        this.upstream = upstream;
        this.audit = audit;
    }

    @Transactional
    public CreditProposal generate(String reference, String actor) {
        DealEnvelopeDto env = upstream.envelope(reference);
        RiskSummaryDto rs = upstream.riskSummary(reference);
        if (rs == null || rs.rating() == null) {
            throw ApiException.conflict("Cannot generate proposal — the deal must be rated first");
        }
        List<Covenant> covs = covenants.findByApplicationReference(reference);
        CreditDecision dec = decisions.findFirstByApplicationReferenceOrderByCreatedAtDesc(reference).orElse(null);

        Md md = new Md();
        md.h1("Credit proposal · " + env.applicationReference());
        md.muted("Prepared by Helix · grounded in platform data · awaiting named human review and sign-off");
        md.spacer();

        // 1) Executive summary
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

        // 2) Facilities proposed
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

        // 3) Collateral
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

        // 4) Financial spread & ratios
        md.h2("4. Financial position (latest period)");
        md.kvBlock(format(env.latestFinancials(), Map.of(
                "REVENUE", "Revenue", "EBITDA", "EBITDA", "PAT", "PAT", "TOTAL_DEBT", "Total debt",
                "CASH", "Cash", "NET_WORTH", "Net worth", "CFO", "Cash flow from ops")));
        md.h2("Ratios");
        md.kvBlock(formatRatios(env.ratios(), Map.of(
                "NET_LEVERAGE", "Net leverage", "INTEREST_COVERAGE", "Interest coverage",
                "DSCR", "DSCR", "EBITDA_MARGIN", "EBITDA margin", "CURRENT_RATIO", "Current ratio",
                "GEARING", "Gearing", "RETURN_ON_EQUITY", "Return on equity")));

        // 5) Rating
        md.h2("5. Risk rating");
        md.line("Model proposed **" + rs.rating().modelGrade() + "** · final **" + rs.rating().finalGrade()
                + "** · PD " + pct(rs.rating().pd()));
        if (rs.rating().overridden()) {
            md.line("> **Override applied** — escalation: " + rs.rating().escalated());
        }

        // 6) Capital projection (for RAROC)
        md.h2("6. Capital projection (for RAROC)");
        md.line("_Note: the bank's capital engine remains the system of record. This figure is an internal projection used by the pricing engine only._");
        if (rs.capital() != null) {
            md.kvBlock(Map.of(
                    "Exposure class", rs.capital().exposureClass(),
                    "RWA (projected)", money(rs.capital().rwa(), env.currency()),
                    "Capital projection", money(rs.capital().capitalRequired(), env.currency())));
        }

        // 7) Pricing (advisory)
        md.h2("7. Risk-adjusted pricing (advisory)");
        if (rs.pricing() != null) {
            md.kvBlock(Map.of(
                    "Recommended rate", pct(rs.pricing().recommendedRate()),
                    "RAROC", pct(rs.pricing().raroc()),
                    "Hurdle", pct(rs.pricing().hurdleRaroc()),
                    "Status", rs.pricing().belowHurdle() ? "Below hurdle — escalate" : "Clears hurdle"));
        }

        // 8) Covenants
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

        // 9) Routing & deviations
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

        // 10) Provenance
        md.h2("10. Provenance and governance");
        md.bullets(
                bullet("Generated by", "Helix · proposal-generator (AI-assisted, human signs)"),
                bullet("Grounding", "Every figure above is quoted from a platform service; no figure is invented by the model"),
                bullet("Approval", "AI cannot approve. A named human at the required authority must sign."));

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
        p.setSections(List.of("Executive summary", "Facilities proposed", "Collateral and security",
                "Financials", "Ratios", "Rating", "Capital projection", "Pricing", "Covenants",
                "Routing & decision", "Provenance"));
        p.setGeneratedBy(actor);
        CreditProposal saved = proposals.save(p);
        audit.ai("proposal-generator", "CREDIT_PROPOSAL_GENERATED", "Application", reference,
                "Generated credit proposal v%d (%d sections, grounded)".formatted(version, p.getSections().size()),
                Map.of("version", version, "facilities", env.facilities() == null ? 0 : env.facilities().size(),
                        "collaterals", env.collaterals() == null ? 0 : env.collaterals().size()));
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

    /** Minimal Markdown + HTML accumulator (no external templating engine). */
    private static class Md {
        private final StringBuilder mdBuf = new StringBuilder();
        private final StringBuilder htmlBuf = new StringBuilder("<article class='credit-proposal'>");

        void h1(String t) { mdBuf.append("# ").append(t).append("\n\n"); htmlBuf.append("<h1>").append(esc(t)).append("</h1>"); }
        void h2(String t) { mdBuf.append("## ").append(t).append("\n\n"); htmlBuf.append("<h2>").append(esc(t)).append("</h2>"); }
        void line(String s) { mdBuf.append(s).append("\n\n"); htmlBuf.append("<p>").append(mdToHtml(s)).append("</p>"); }
        void spacer() { mdBuf.append("\n"); htmlBuf.append("<div class='spacer'></div>"); }
        void muted(String s) { mdBuf.append("_").append(s).append("_\n\n"); htmlBuf.append("<p class='muted'>").append(esc(s)).append("</p>"); }
        void bullets(String[]... pairs) {
            htmlBuf.append("<ul>");
            for (String[] p : pairs) {
                mdBuf.append("- **").append(p[0]).append(":** ").append(p[1]).append("\n");
                htmlBuf.append("<li><b>").append(esc(p[0])).append(":</b> ").append(esc(p[1])).append("</li>");
            }
            mdBuf.append("\n");
            htmlBuf.append("</ul>");
        }
        void kvBlock(Map<String, String> kv) {
            htmlBuf.append("<table class='kv'>");
            kv.forEach((k, v) -> {
                mdBuf.append("- **").append(k).append(":** ").append(v).append("\n");
                htmlBuf.append("<tr><td>").append(esc(k)).append("</td><td>").append(esc(v)).append("</td></tr>");
            });
            mdBuf.append("\n");
            htmlBuf.append("</table>");
        }
        void table(String[] cols) {
            mdBuf.append("| ").append(String.join(" | ", cols)).append(" |\n");
            mdBuf.append("|").append("---|".repeat(cols.length)).append("\n");
            htmlBuf.append("<table><thead><tr>");
            for (String c : cols) htmlBuf.append("<th>").append(esc(c)).append("</th>");
            htmlBuf.append("</tr></thead><tbody>");
        }
        void row(String... cells) {
            mdBuf.append("| ").append(String.join(" | ", cells)).append(" |\n");
            htmlBuf.append("<tr>");
            for (String c : cells) htmlBuf.append("<td>").append(esc(c)).append("</td>");
            htmlBuf.append("</tr>");
        }
        String markdown() { return mdBuf.toString(); }
        String html() { return htmlBuf.append("</tbody></table></article>").toString(); }

        private String esc(String s) {
            return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
        private String mdToHtml(String s) {
            return esc(s).replaceAll("\\*\\*(.+?)\\*\\*", "<b>$1</b>").replaceAll("_(.+?)_", "<i>$1</i>");
        }
    }
}
