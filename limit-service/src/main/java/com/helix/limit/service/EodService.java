package com.helix.limit.service;

import com.helix.common.audit.AuditService;
import com.helix.common.web.ApiException;
import com.helix.limit.entity.EodBatchRun;
import com.helix.limit.entity.LimitNode;
import com.helix.limit.entity.ReconciliationVariance;
import com.helix.limit.entity.RevaluationEntry;
import com.helix.limit.entity.Utilisation;
import com.helix.limit.repo.EodBatchRunRepository;
import com.helix.limit.repo.LimitNodeRepository;
import com.helix.limit.repo.ReconciliationVarianceRepository;
import com.helix.limit.repo.RevaluationEntryRepository;
import com.helix.limit.repo.UtilisationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * End-of-day batch for limit management (PRD limit EOD jobs):
 *
 * <ul>
 *   <li><b>Currency revaluation</b> — re-marks every non-base-currency node's
 *       {@code baseAmount} at today's FX rate and captures a {@link RevaluationEntry}
 *       per moved node. Total mark-to-market delta is recorded on the run.</li>
 *   <li><b>Utilisation reconciliation</b> — for every leaf node, sums the immutable
 *       ledger and compares to the recorded outstanding / cumulativeDrawn / reserved.
 *       For every parent, sums the children. Any mismatch above a small tolerance
 *       writes a {@link ReconciliationVariance} so the ops desk can investigate.</li>
 * </ul>
 *
 * Every run is immutable post-completion and is queryable by run id.
 */
@Service
public class EodService {

    private static final double TOLERANCE = 0.01;

    private final LimitNodeRepository nodes;
    private final UtilisationRepository utilisations;
    private final EodBatchRunRepository runs;
    private final RevaluationEntryRepository revaluations;
    private final ReconciliationVarianceRepository variances;
    private final FxService fx;
    private final AuditService audit;

    public EodService(LimitNodeRepository nodes, UtilisationRepository utilisations,
                      EodBatchRunRepository runs, RevaluationEntryRepository revaluations,
                      ReconciliationVarianceRepository variances, FxService fx, AuditService audit) {
        this.nodes = nodes;
        this.utilisations = utilisations;
        this.runs = runs;
        this.revaluations = revaluations;
        this.variances = variances;
        this.fx = fx;
        this.audit = audit;
    }

    @Transactional
    public Map<String, Object> refreshFx(String currency, double rate, String actor) {
        double prev = fx.refreshRate(currency, rate, actor);
        audit.human(actor, "FX_RATE_REFRESHED", "Fx", currency.toUpperCase(),
                "%s: %.4f -> %.4f".formatted(currency.toUpperCase(), prev, rate),
                Map.of("previous", prev, "current", rate));
        return Map.of("currency", currency.toUpperCase(), "previous", prev, "current", rate,
                "refreshedBy", actor, "refreshedAt", fx.lastRefreshedAt().toString());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> fxView() {
        return Map.of("base", FxService.BASE, "rates", fx.currentRates(),
                "lastRefreshedAt", fx.lastRefreshedAt().toString(),
                "lastRefreshedBy", fx.lastRefreshedBy());
    }

    @Transactional
    public EodBatchRun runEod(String actor) {
        LocalDate today = LocalDate.now();
        EodBatchRun run = new EodBatchRun();
        run.setRunDate(today);
        run.setRunBy(actor);
        run.setTotalNodes(0);
        run.setRevaluedCount(0);
        run.setVarianceCount(0);
        run.setRevaluationDeltaBase(0);
        run.setFxSnapshot(fx.currentRates().toString());
        EodBatchRun saved = runs.save(run);

        List<LimitNode> all = nodes.findAll();
        saved.setTotalNodes(all.size());

        // 1. Currency revaluation pass
        double netDelta = 0.0;
        int revalued = 0;
        for (LimitNode n : all) {
            if (!"ACTIVE".equalsIgnoreCase(n.getStatus())) continue;
            if (FxService.BASE.equalsIgnoreCase(n.getCurrency())) continue;
            double rate = fx.rate(n.getCurrency());
            double newBase = fx.toBase(n.getSanctionedAmount(), n.getCurrency());
            double delta = newBase - n.getBaseAmount();
            if (Math.abs(delta) < TOLERANCE) continue;
            RevaluationEntry e = new RevaluationEntry();
            e.setRunId(saved.getId());
            e.setNodeId(n.getId());
            e.setCode(n.getCode());
            e.setCurrency(n.getCurrency());
            e.setSanctionedAmount(n.getSanctionedAmount());
            e.setOldBase(n.getBaseAmount());
            e.setNewBase(newBase);
            e.setDelta(delta);
            e.setFxRate(rate);
            revaluations.save(e);
            n.setBaseAmount(newBase);
            nodes.save(n);
            netDelta += delta;
            revalued++;
            audit.engine("LIMIT_REVALUED", "LimitNode", String.valueOf(n.getId()),
                    "%s %s %.2f @ %.4f: %.2f -> %.2f (Δ %.2f)".formatted(
                            n.getCode(), n.getCurrency(), n.getSanctionedAmount(), rate,
                            e.getOldBase(), newBase, delta),
                    Map.of("runId", saved.getId(), "currency", n.getCurrency(), "delta", delta));
        }
        saved.setRevaluedCount(revalued);
        saved.setRevaluationDeltaBase(Math.round(netDelta * 100.0) / 100.0);

        // 2. Utilisation reconciliation pass
        Map<Long, List<LimitNode>> byParent = new HashMap<>();
        for (LimitNode n : all) {
            if (n.getParentId() != null) {
                byParent.computeIfAbsent(n.getParentId(), k -> new java.util.ArrayList<>()).add(n);
            }
        }
        int variancesFound = 0;
        for (LimitNode n : all) {
            List<LimitNode> children = byParent.get(n.getId());
            if (children == null || children.isEmpty()) {
                variancesFound += reconcileLeaf(saved.getId(), n);
            } else {
                variancesFound += reconcileParent(saved.getId(), n, children);
            }
        }
        saved.setVarianceCount(variancesFound);
        EodBatchRun finished = runs.save(saved);

        audit.engine("LIMIT_EOD_RUN", "EodBatchRun", String.valueOf(finished.getId()),
                "EOD: %d nodes, %d revalued (Δ %.2f INR), %d variance(s)".formatted(
                        finished.getTotalNodes(), revalued, netDelta, variancesFound),
                Map.of("revalued", revalued, "variances", variancesFound,
                        "deltaBase", finished.getRevaluationDeltaBase()));
        return finished;
    }

    @Transactional(readOnly = true)
    public List<EodBatchRun> runList() {
        return runs.findAllByOrderByIdDesc();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> runDetail(Long runId) {
        EodBatchRun r = runs.findById(runId).orElseThrow(() -> ApiException.notFound("No run: " + runId));
        return Map.of("run", r,
                "revaluations", revaluations.findByRunIdOrderByIdAsc(runId),
                "variances", variances.findByRunIdOrderByIdAsc(runId));
    }

    // --------------------------------------------------- reconciliation helpers

    private int reconcileLeaf(Long runId, LimitNode n) {
        double outstanding = 0;
        double drawn = 0;
        double reserved = 0;
        for (Utilisation u : utilisations.findByLimitNodeIdOrderByCreatedAtDesc(n.getId())) {
            if (!"CONFIRMED".equalsIgnoreCase(u.getStatus())) continue;
            switch (u.getAction().toUpperCase()) {
                case "UTILISE" -> { outstanding += u.getBaseAmount(); drawn += u.getBaseAmount(); }
                case "RELEASE" -> outstanding -= u.getBaseAmount();
                case "RESERVE" -> reserved += u.getBaseAmount();
                case "REVERSAL" -> { outstanding -= u.getBaseAmount(); drawn -= u.getBaseAmount(); }
                default -> { }
            }
        }
        return writeVariances(runId, n, "LEAF", outstanding, drawn, reserved);
    }

    private int reconcileParent(Long runId, LimitNode n, List<LimitNode> children) {
        double outstanding = 0;
        double drawn = 0;
        double reserved = 0;
        for (LimitNode c : children) {
            outstanding += c.getOutstanding();
            drawn += c.getCumulativeDrawn();
            reserved += c.getReserved();
        }
        return writeVariances(runId, n, "PARENT", outstanding, drawn, reserved);
    }

    private int writeVariances(Long runId, LimitNode n, String scope,
                               double outstandingComputed, double drawnComputed, double reservedComputed) {
        int count = 0;
        count += recordIfDiff(runId, n, scope, "outstanding", n.getOutstanding(), outstandingComputed);
        count += recordIfDiff(runId, n, scope, "cumulativeDrawn", n.getCumulativeDrawn(), drawnComputed);
        count += recordIfDiff(runId, n, scope, "reserved", n.getReserved(), reservedComputed);
        return count;
    }

    private int recordIfDiff(Long runId, LimitNode n, String scope, String field, double recorded, double computed) {
        double diff = recorded - computed;
        if (Math.abs(diff) < TOLERANCE) return 0;
        ReconciliationVariance v = new ReconciliationVariance();
        v.setRunId(runId);
        v.setNodeId(n.getId());
        v.setCode(n.getCode());
        v.setScope(scope);
        v.setField(field);
        v.setRecorded(recorded);
        v.setComputed(computed);
        v.setVariance(diff);
        variances.save(v);
        audit.engine("LIMIT_RECON_VARIANCE", "LimitNode", String.valueOf(n.getId()),
                "%s %s %s recorded %.2f vs computed %.2f (Δ %.2f)".formatted(
                        scope, n.getCode(), field, recorded, computed, diff),
                Map.of("runId", runId, "field", field, "variance", diff));
        return 1;
    }
}
