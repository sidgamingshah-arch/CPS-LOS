package com.helix.decision.service;

import com.helix.common.audit.AuditService;
import com.helix.common.model.Enums.BreachSeverity;
import com.helix.common.web.ApiException;
import com.helix.decision.dto.Dtos.AddCovenantRequest;
import com.helix.decision.entity.Covenant;
import com.helix.decision.repo.CovenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** Structured covenant management (PRD §7). Covenants are tested in monitoring (§11). */
@Service
public class CovenantService {

    private final CovenantRepository covenants;
    private final AuditService audit;

    public CovenantService(CovenantRepository covenants, AuditService audit) {
        this.covenants = covenants;
        this.audit = audit;
    }

    @Transactional
    public Covenant add(String reference, AddCovenantRequest req, String actor) {
        Covenant c = new Covenant();
        c.setApplicationReference(reference);
        c.setCovenantType(req.covenantType());
        c.setMetric(req.metric());
        c.setOperator(req.operator());
        c.setThreshold(req.threshold());
        c.setTestFrequency(req.testFrequency());
        c.setSource(req.source());
        c.setCurePeriodDays(req.curePeriodDays());
        c.setBreachSeverity(BreachSeverity.valueOf(req.breachSeverity().toUpperCase()).name());
        c.setOnBreach(req.onBreach());
        c.setCreatedBy(actor);
        Covenant saved = covenants.save(c);
        audit.human(actor, "COVENANT_ADDED", "Application", reference,
                "%s %s %.2f tested %s".formatted(req.metric(), req.operator(), req.threshold(), req.testFrequency()),
                Map.of("metric", req.metric(), "operator", req.operator(), "threshold", req.threshold()));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Covenant> list(String reference) {
        return covenants.findByApplicationReference(reference);
    }

    @Transactional
    public void deactivate(Long id, String actor) {
        Covenant c = covenants.findById(id).orElseThrow(() -> ApiException.notFound("No covenant: " + id));
        c.setActive(false);
        covenants.save(c);
    }

    /** AI [C] covenant suggestions tied to the risk profile (PRD §7, US-7.2). Analyst edits/accepts. */
    public List<AddCovenantRequest> suggest(String finalGrade) {
        boolean weak = List.of("BB", "B", "CCC", "CC", "C", "D").contains(finalGrade);
        double dscr = weak ? 1.35 : 1.25;
        double leverage = weak ? 3.0 : 3.5;
        return List.of(
                new AddCovenantRequest("FINANCIAL_MAINTENANCE", "DSCR", ">=", dscr, "QUARTERLY",
                        "borrower_management_accounts", 30, "MAJOR",
                        List.of("notify_RM", "raise_EWS", "trigger_review")),
                new AddCovenantRequest("FINANCIAL_MAINTENANCE", "NET_LEVERAGE", "<=", leverage, "QUARTERLY",
                        "borrower_management_accounts", 30, "MAJOR",
                        List.of("notify_RM", "raise_EWS")),
                new AddCovenantRequest("INFORMATION", "INTEREST_COVERAGE", ">=", 2.0, "ANNUAL",
                        "audited_financials", 0, "MINOR", List.of("notify_RM")));
    }
}
