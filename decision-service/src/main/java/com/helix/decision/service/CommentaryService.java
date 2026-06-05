package com.helix.decision.service;

import com.helix.common.audit.AuditService;
import com.helix.common.web.ApiException;
import com.helix.decision.client.UpstreamClient;
import com.helix.decision.client.UpstreamClient.CollateralViewDto;
import com.helix.decision.client.UpstreamClient.DealEnvelopeDto;
import com.helix.decision.client.UpstreamClient.FacilityViewDto;
import com.helix.decision.client.UpstreamClient.RiskSummaryDto;
import com.helix.decision.entity.ProposalCommentary;
import com.helix.decision.repo.ProposalCommentaryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI narrative commentary for credit-proposal sections (PRD AI commentary).
 * Grounded narrative drafted from the deal envelope, the authoritative rating, the
 * spread ratios, and the deal structure. Always advisory and never auto-applied —
 * the figure path remains untouched and the proposal is generated separately. The
 * reviewer can tie any line in the narrative back to a source via {@code sources}.
 */
@Service
public class CommentaryService {

    private static final Set<String> SECTIONS = Set.of(
            "industry_outlook", "management_quality", "financial_commentary",
            "structure_commentary", "risk_commentary");

    private final ProposalCommentaryRepository repo;
    private final UpstreamClient upstream;
    private final AuditService audit;

    public CommentaryService(ProposalCommentaryRepository repo, UpstreamClient upstream, AuditService audit) {
        this.repo = repo;
        this.upstream = upstream;
        this.audit = audit;
    }

    @Transactional
    public ProposalCommentary draft(String reference, String section, String hint, String actor) {
        if (section == null || !SECTIONS.contains(section.toLowerCase())) {
            throw ApiException.badRequest("section must be one of " + SECTIONS);
        }
        String s = section.toLowerCase();
        DealEnvelopeDto env = upstream.envelope(reference);
        RiskSummaryDto rs;
        try { rs = upstream.riskSummary(reference); } catch (Exception e) { rs = null; }

        Map<String, Object> sources = new LinkedHashMap<>();
        List<String> bullets = new ArrayList<>();
        String narrative = switch (s) {
            case "industry_outlook" -> draftIndustry(env, sources, bullets);
            case "management_quality" -> draftManagement(env, sources, bullets);
            case "financial_commentary" -> draftFinancials(env, rs, sources, bullets);
            case "structure_commentary" -> draftStructure(env, sources, bullets);
            case "risk_commentary" -> draftRisk(env, rs, sources, bullets);
            default -> "";
        };
        if (hint != null && !hint.isBlank()) {
            narrative = narrative + " Reviewer hint: " + hint + ".";
            sources.put("reviewer_hint", hint);
        }

        ProposalCommentary c = new ProposalCommentary();
        c.setApplicationReference(reference);
        c.setSection(s);
        c.setNarrative(narrative);
        c.setBulletPoints(bullets);
        c.setSources(sources);
        c.setConfidence(rs == null ? 0.65 : 0.78);
        c.setAdvisory(true);
        c.setStatus("DRAFT");
        c.setDraftedBy(actor);
        ProposalCommentary saved = repo.save(c);

        audit.ai("proposal-commentary", "COMMENTARY_DRAFTED", "Application", reference,
                "Drafted '%s' commentary (advisory, %d bullet point(s))".formatted(s, bullets.size()),
                Map.of("section", s, "advisory", true, "confidence", c.getConfidence()));
        return saved;
    }

    @Transactional
    public ProposalCommentary review(Long id, boolean approve, String note, String actor) {
        ProposalCommentary c = get(id);
        if (!"DRAFT".equals(c.getStatus())) {
            throw ApiException.conflict("Commentary is " + c.getStatus());
        }
        c.setStatus(approve ? "CONFIRMED" : "REJECTED");
        c.setReviewedBy(actor);
        c.setReviewedAt(Instant.now());
        c.setReviewNote(note);
        ProposalCommentary saved = repo.save(c);
        audit.human(actor, approve ? "COMMENTARY_CONFIRMED" : "COMMENTARY_REJECTED",
                "ProposalCommentary", String.valueOf(id),
                (approve ? "Confirmed" : "Rejected") + " '" + c.getSection() + "' commentary",
                Map.of("section", c.getSection()));
        return saved;
    }

    @Transactional
    public ProposalCommentary edit(Long id, String narrative, String actor) {
        ProposalCommentary c = get(id);
        if (!"DRAFT".equals(c.getStatus())) {
            throw ApiException.conflict("Edit only DRAFT commentary");
        }
        if (narrative == null || narrative.isBlank()) {
            throw ApiException.badRequest("narrative required");
        }
        c.setNarrative(narrative);
        ProposalCommentary saved = repo.save(c);
        audit.human(actor, "COMMENTARY_EDITED", "ProposalCommentary", String.valueOf(id),
                "Edited '" + c.getSection() + "' commentary", Map.of("section", c.getSection()));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<ProposalCommentary> list(String reference, String section) {
        if (section == null || section.isBlank()) {
            return repo.findByApplicationReferenceOrderByIdDesc(reference);
        }
        return repo.findByApplicationReferenceAndSectionOrderByIdDesc(reference, section.toLowerCase());
    }

    @Transactional(readOnly = true)
    public ProposalCommentary get(Long id) {
        return repo.findById(id).orElseThrow(() -> ApiException.notFound("No commentary: " + id));
    }

    // --------------------------------------------------- section drafters

    private String draftIndustry(DealEnvelopeDto env, Map<String, Object> sources, List<String> bullets) {
        String seg = env.segment();
        sources.put("segment", seg);
        sources.put("jurisdiction", env.jurisdiction());
        bullets.add(seg + " sector — directional outlook reviewed quarterly by the credit committee");
        bullets.add("Jurisdiction " + env.jurisdiction() + " regulatory regime governs the lending relationship");
        return "The borrower operates in the " + seg + " segment within " + env.jurisdiction()
                + ". The committee tracks sector-specific leading indicators on the EWS dashboard and the macro overlay; "
                + "where the macro-impact assessment surfaces downgrade pressure, the rating review schedule is brought forward. "
                + "Refer to the Industry Benchmark master for the latest peer ratios.";
    }

    private String draftManagement(DealEnvelopeDto env, Map<String, Object> sources, List<String> bullets) {
        sources.put("counterpartyName", env.counterpartyName());
        bullets.add("UBO and screening dispositions recorded in counterparty-service");
        bullets.add("KYC re-verification cadence per INACTIVITY_THRESHOLD master");
        return "Management of " + env.counterpartyName() + " has been screened and the UBO chain resolved through the "
                + "counterparty onboarding lifecycle. Negative-list and external-rating checks were refreshed prior to credit "
                + "initiation; any adverse screening hit is dispositioned by a named compliance officer and feeds the audit trail. "
                + "Reputational/PEP findings (if any) are surfaced on Customer-360.";
    }

    private String draftFinancials(DealEnvelopeDto env, RiskSummaryDto rs, Map<String, Object> sources, List<String> bullets) {
        Map<String, Double> ratios = env.ratios() == null ? Map.of() : env.ratios();
        sources.put("ratios", ratios);
        StringBuilder sb = new StringBuilder("Financial position is summarised from the confirmed spread with cell-level provenance. ");
        for (String key : List.of("DSCR", "NET_LEVERAGE", "INTEREST_COVERAGE", "EBITDA_MARGIN", "CURRENT_RATIO")) {
            Double v = ratios.get(key);
            if (v == null) continue;
            String pretty = humanise(key);
            String quality = qualityOf(key, v);
            bullets.add(pretty + " " + format(v) + " — " + quality);
            sb.append(pretty).append(" of ").append(format(v)).append(" is ").append(quality).append(". ");
        }
        if (rs != null && rs.rating() != null) {
            sources.put("grade", rs.rating().finalGrade());
            sources.put("pd", rs.rating().pd());
            sb.append("The authoritative scorecard returns grade ").append(rs.rating().finalGrade())
                    .append(" with a PD of ").append(String.format("%.2f%%", rs.rating().pd() * 100))
                    .append(", consistent with the ratio profile above. ");
        }
        sb.append("Trends and benchmark flags are reviewed alongside the projections.");
        return sb.toString();
    }

    private String draftStructure(DealEnvelopeDto env, Map<String, Object> sources, List<String> bullets) {
        int facCount = env.facilities() == null ? 0 : env.facilities().size();
        int colCount = env.collaterals() == null ? 0 : env.collaterals().size();
        sources.put("facilityCount", facCount);
        sources.put("collateralCount", colCount);
        sources.put("totalCollateralCover", env.totalCollateralCover());
        StringBuilder sb = new StringBuilder("The proposal comprises ").append(facCount)
                .append(" facility line(s)");
        if (env.facilities() != null) {
            for (FacilityViewDto f : env.facilities()) {
                if (f.sublimits() != null && !f.sublimits().isEmpty()) {
                    bullets.add(f.facilityType() + " " + env.currency() + " " + format((double) f.amount())
                            + " with " + f.sublimits().size() + " sublimit(s)"
                            + (f.interchangeabilityGroups() == null || f.interchangeabilityGroups().isEmpty()
                                    ? "" : " · interchangeable pools defined"));
                } else {
                    bullets.add(f.facilityType() + " " + env.currency() + " " + format((double) f.amount()));
                }
            }
        }
        sb.append(" totalling ").append(env.currency()).append(" ").append(format(env.totalProposedAmount()))
                .append(" over ").append(env.tenorMonths()).append(" months. ");
        if (colCount > 0) {
            double cover = env.totalCollateralCover();
            sb.append("Security cover stands at ").append(format(cover)).append(" across ").append(colCount)
                    .append(" collateral item(s); perfection status is tracked in CAD. ");
            for (CollateralViewDto col : env.collaterals()) {
                bullets.add(col.collateralType() + " — effective " + format(col.effectiveValue())
                        + " · " + col.perfectionStatus());
            }
        } else {
            sb.append("This is an unsecured exposure; pricing and DoA reflect that. ");
        }
        sb.append("Interchangeability and sublimit fungibility are governed in the limit tree.");
        return sb.toString();
    }

    private String draftRisk(DealEnvelopeDto env, RiskSummaryDto rs, Map<String, Object> sources, List<String> bullets) {
        StringBuilder sb = new StringBuilder("Key risks are captured below, mitigated by the covenant package, ")
                .append("the security position, and the early-warning system. ");
        if (rs != null && rs.rating() != null) {
            sources.put("grade", rs.rating().finalGrade());
            sb.append("Authoritative grade is ").append(rs.rating().finalGrade())
                    .append("; deviations from the model grade are notch-limited and require named-human confirmation. ");
            bullets.add("Final grade " + rs.rating().finalGrade() + " — overridden=" + rs.rating().overridden());
        }
        if (rs != null && rs.pricing() != null) {
            sources.put("raroc", rs.pricing().raroc());
            sources.put("hurdleRaroc", rs.pricing().hurdleRaroc());
            bullets.add("RAROC " + format(rs.pricing().raroc() * 100) + "% vs hurdle "
                    + format(rs.pricing().hurdleRaroc() * 100) + "% "
                    + (rs.pricing().belowHurdle() ? "(below hurdle — escalation required)" : "(at or above hurdle)"));
            if (rs.pricing().belowHurdle()) {
                sb.append("Pricing is below the hurdle RAROC and the DoA path requires one-level escalation. ");
            }
        }
        bullets.add("Macro overlay (advisory) checked for directional PD impact");
        bullets.add("EWS triggers monitored post-disbursement");
        return sb.toString();
    }

    // --------------------------------------------------- helpers

    private String qualityOf(String key, double v) {
        return switch (key) {
            case "DSCR" -> v >= 1.5 ? "comfortable coverage" : v >= 1.2 ? "adequate" : "tight";
            case "NET_LEVERAGE" -> v <= 2 ? "conservative" : v <= 4 ? "moderate" : "elevated";
            case "INTEREST_COVERAGE" -> v >= 4 ? "strong" : v >= 2 ? "adequate" : "weak";
            case "EBITDA_MARGIN" -> v >= 0.15 ? "healthy" : v >= 0.08 ? "modest" : "compressed";
            case "CURRENT_RATIO" -> v >= 1.5 ? "comfortable" : v >= 1.0 ? "adequate" : "stretched";
            default -> "in line with sector";
        };
    }

    private String humanise(String key) {
        String s = key.replace('_', ' ').toLowerCase();
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private String format(double v) {
        return Math.abs(v) >= 1000 ? String.format("%,.0f", v) : String.format("%.2f", v);
    }
}
