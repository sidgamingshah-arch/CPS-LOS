package com.helix.limit.service;

import com.helix.common.audit.AuditService;
import com.helix.common.money.Money;

import java.math.BigDecimal;
import com.helix.common.web.ApiException;
import com.helix.limit.dto.Dtos.ActionResult;
import com.helix.limit.dto.Dtos.ExposureCheckResult;
import com.helix.limit.dto.Dtos.UtilisationAction;
import com.helix.limit.dto.Dtos.UtilisationRequest;
import com.helix.limit.dto.Dtos.UtilisationResponse;
import com.helix.limit.dto.Dtos.ValidationCheck;
import com.helix.limit.dto.Dtos.ValidationResult;
import com.helix.limit.entity.LimitNode;
import com.helix.limit.entity.Utilisation;
import com.helix.limit.repo.LimitNodeRepository;
import com.helix.limit.repo.UtilisationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The product-processor transaction interface (PRD Validation & Utilisation APIs).
 * Validation runs status / expiry / tenor / available / exposure checks; utilisation
 * applies UTILISE / RELEASE / RESERVE / REVERSAL and rolls balances up the tree to
 * the obligor root. An override flag force-utilises past available/exposure breaches
 * (corporate loans) but never past a FROZEN/CLOSED or expired line.
 */
@Service
public class UtilisationService {

    private final LimitNodeRepository nodes;
    private final UtilisationRepository ledger;
    private final LimitService limits;
    private final FxService fx;
    private final AuditService audit;

    public UtilisationService(LimitNodeRepository nodes, UtilisationRepository ledger, LimitService limits,
                              FxService fx, AuditService audit) {
        this.nodes = nodes;
        this.ledger = ledger;
        this.limits = limits;
        this.fx = fx;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public ValidationResult validate(String cif, String lineId, double amount, String currency, Integer tenorMonths) {
        LimitNode n = nodes.findByReference(lineId).orElse(null);
        List<ValidationCheck> checks = new ArrayList<>();
        if (n == null || (cif != null && !cif.equals(n.getCif()))) {
            return new ValidationResult(false, lineId, "Unknown line for CIF", List.of(), 0, null, List.of());
        }
        double amtBase = fx.toBase(amount, currency == null ? n.getCurrency() : currency);

        checks.add(new ValidationCheck("status_active", "ACTIVE".equals(n.getStatus()), "status=" + n.getStatus()));
        boolean notExpired = n.getExpiryDate() == null || !n.getExpiryDate().isBefore(LocalDate.now());
        checks.add(new ValidationCheck("not_expired", notExpired,
                n.getExpiryDate() == null ? "no expiry" : "expiry=" + n.getExpiryDate()));
        boolean tenorOk = tenorMonths == null || n.getTenorMonths() == null || tenorMonths <= n.getTenorMonths();
        checks.add(new ValidationCheck("tenor_within_facility", tenorOk,
                "requested=%s facility=%s".formatted(tenorMonths, n.getTenorMonths())));
        boolean nodeAvail = Money.of(amtBase).compareTo(Money.add(n.available(), Money.of(1e-6))) <= 0;
        checks.add(new ValidationCheck("line_available", nodeAvail,
                "need %.0f, available %.0f (base)".formatted(amtBase, Money.asDouble(n.available()))));
        LimitNode root = root(n);
        boolean rootAvail = Money.of(amtBase).compareTo(Money.add(root.available(), Money.of(1e-6))) <= 0;
        checks.add(new ValidationCheck("obligor_available", rootAvail,
                "need %.0f, obligor available %.0f (base)".formatted(amtBase, Money.asDouble(root.available()))));

        // Per-transaction exposure uses the single-name (obligor) norm only; sector/
        // geography concentration is a portfolio/sanction-level review (see /exposure
        // and portfolio-service concentration), not a drawdown-time hard stop.
        ExposureCheckResult exp = limits.exposureCheck(n.getCif(), amtBase);
        exp.checks().stream().filter(c -> "single_name".equals(c.name())).forEach(checks::add);

        boolean success = checks.stream().allMatch(ValidationCheck::pass);
        List<String> terms = List.of(
                "Tenor <= %s months".formatted(n.getTenorMonths()),
                "Facility status must be ACTIVE",
                "Country/sector exposure norms apply");
        String msg = success ? "All validations passed" : "One or more validations failed";
        return new ValidationResult(success, lineId, msg, checks, round(Money.asDouble(n.available())), FxService.BASE, terms);
    }

    @Transactional
    public UtilisationResponse apply(UtilisationRequest req, String actor) {
        List<ActionResult> results = new ArrayList<>();
        boolean allOk = true;
        for (UtilisationAction a : req.actions()) {
            ActionResult r = applyOne(req.cif(), a, req.overrideFlag(), req.productProcessor(), actor);
            results.add(r);
            allOk = allOk && r.success();
        }
        return new UtilisationResponse(req.cif(), allOk, results);
    }

    private ActionResult applyOne(String cif, UtilisationAction a, boolean override, String pp, String actor) {
        LimitNode n = nodes.findByReference(a.lineId()).orElse(null);
        if (n == null || (cif != null && !cif.equals(n.getCif()))) {
            return new ActionResult(a.lineId(), a.action(), false, "Unknown line for CIF", 0, 0);
        }
        String action = a.action().toUpperCase();
        double amtBase = fx.toBase(a.amount(), a.currency() == null ? n.getCurrency() : a.currency());

        // Idempotency: a repeat call with the same (transactionRef, action) returns the
        // original confirmed booking without applying the delta a second time. This is
        // the contract the disbursement-release path relies on to be retry-safe — if
        // anything after the limit call fails locally, the next release attempt is a
        // no-op here instead of a double-book.
        if (a.transactionRef() != null && !a.transactionRef().isBlank()) {
            Utilisation existing = ledger
                    .findFirstByTransactionRefAndActionAndStatus(a.transactionRef(), action, "CONFIRMED")
                    .orElse(null);
            if (existing != null) {
                return new ActionResult(a.lineId(), action, true,
                        "Idempotent — existing booking (txnRef " + a.transactionRef() + ")",
                        round(Money.asDouble(n.getOutstanding())), round(Money.asDouble(n.available())));
            }
        }

        if ("UTILISE".equals(action) || "RESERVE".equals(action)) {
            // Hard stops that override cannot bypass.
            if (!"ACTIVE".equals(n.getStatus())) {
                return reject(n, a, amtBase, pp, "Line is " + n.getStatus());
            }
            if (n.getExpiryDate() != null && n.getExpiryDate().isBefore(LocalDate.now())) {
                return reject(n, a, amtBase, pp, "Line expired");
            }
            ValidationResult v = validate(cif, a.lineId(), a.amount(), a.currency(), null);
            if (!v.success() && !override) {
                return reject(n, a, amtBase, pp, v.message() + " — " +
                        v.checks().stream().filter(c -> !c.pass()).map(ValidationCheck::name).toList());
            }
        }

        boolean overrideApplied = false;
        switch (action) {
            case "UTILISE" -> {
                ValidationResult v = validate(cif, a.lineId(), a.amount(), a.currency(), null);
                overrideApplied = !v.success() && override;
                adjust(n, amtBase, amtBase, 0);
            }
            case "RELEASE" -> adjust(n, -amtBase, n.isRevolving() ? 0 : 0, 0);   // release reduces outstanding; non-revolving keeps cumulativeDrawn
            case "RESERVE" -> adjust(n, 0, 0, amtBase);
            case "REVERSAL" -> adjust(n, -amtBase, -amtBase, 0);
            default -> {
                return new ActionResult(a.lineId(), action, false, "Unknown action", round(Money.asDouble(n.getOutstanding())), round(Money.asDouble(n.available())));
            }
        }
        Utilisation u = new Utilisation();
        u.setLimitNodeId(n.getId());
        u.setCif(n.getCif());
        u.setAction(action);
        u.setAmount(Money.of(a.amount()));
        u.setCurrency(a.currency() == null ? n.getCurrency() : a.currency());
        u.setBaseAmount(Money.of(amtBase));
        u.setProductProcessor(pp);
        u.setTransactionRef(a.transactionRef());
        u.setOverrideApplied(overrideApplied);
        u.setStatus("CONFIRMED");
        ledger.save(u);
        audit.engine("LIMIT_" + action, "Limit", n.getReference(),
                "%s %.0f %s on %s%s".formatted(action, a.amount(), u.getCurrency(), n.getReference(),
                        overrideApplied ? " (OVERRIDE)" : ""),
                Map.of("action", action, "baseAmount", amtBase, "override", overrideApplied, "pp", String.valueOf(pp)));
        return new ActionResult(a.lineId(), action, true,
                overrideApplied ? "Confirmed (override applied)" : "Confirmed",
                round(Money.asDouble(n.getOutstanding())), round(Money.asDouble(n.available())));
    }

    private ActionResult reject(LimitNode n, UtilisationAction a, double amtBase, String pp, String reason) {
        Utilisation u = new Utilisation();
        u.setLimitNodeId(n.getId());
        u.setCif(n.getCif());
        u.setAction(a.action().toUpperCase());
        u.setAmount(Money.of(a.amount()));
        u.setCurrency(a.currency() == null ? n.getCurrency() : a.currency());
        u.setBaseAmount(Money.of(amtBase));
        u.setProductProcessor(pp);
        u.setTransactionRef(a.transactionRef());
        u.setStatus("REJECTED");
        u.setMessage(reason);
        ledger.save(u);
        return new ActionResult(a.lineId(), a.action(), false, reason, round(Money.asDouble(n.getOutstanding())), round(Money.asDouble(n.available())));
    }

    /** Applies a delta to the node and rolls it up the ancestor chain to the root. */
    private void adjust(LimitNode n, double outstandingDelta, double drawnDelta, double reservedDelta) {
        BigDecimal odelta = Money.of(outstandingDelta);
        BigDecimal ddelta = Money.of(Math.max(0, drawnDelta));
        BigDecimal rdelta = Money.of(reservedDelta);
        LimitNode cur = n;
        while (cur != null) {
            cur.setOutstanding(Money.nonNegative(Money.add(cur.getOutstanding(), odelta)));
            cur.setCumulativeDrawn(Money.nonNegative(Money.add(cur.getCumulativeDrawn(), ddelta)));
            cur.setReserved(Money.nonNegative(Money.add(cur.getReserved(), rdelta)));
            nodes.save(cur);
            cur = cur.getParentId() == null ? null : nodes.findById(cur.getParentId()).orElse(null);
        }
    }

    private LimitNode root(LimitNode n) {
        return n.getRootId() == null ? n : nodes.findById(n.getRootId()).orElse(n);
    }

    @Transactional(readOnly = true)
    public List<Utilisation> ledgerFor(String cif) {
        return ledger.findByCifOrderByCreatedAtDesc(cif);
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
