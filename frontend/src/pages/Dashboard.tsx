import { portfolio, risk, mis, fmt } from "../api";
import { useApp } from "../app-context";
import { Badge, Button, Card, DeterministicBadge, Stat, useAsync } from "../ui";

export default function Dashboard() {
  const { actor, notify } = useApp();
  const summary = useAsync(() => portfolio.summary(), []);
  const conc = useAsync(() => portfolio.concentration("IN-RBI"), []);
  const watch = useAsync(() => portfolio.watchlist(), []);
  const stress = useAsync(() => portfolio.stress(), []);
  const overrides = useAsync(() => risk.overrideStats("MID_CORPORATE"), []);
  // Richer at-a-glance book analytics (all deterministic MIS aggregations).
  const dash = useAsync(() => mis.dashboard(), []);
  const p360 = useAsync(() => mis.portfolio360(), []);
  const multi = useAsync(() => portfolio.concentrationMulti("IN-RBI"), []);

  const reloadAll = () => {
    summary.reload(); conc.reload(); watch.reload(); stress.reload(); overrides.reload();
    dash.reload(); p360.reload(); multi.reload();
  };

  const scanAll = async () => {
    try { const s = await portfolio.scanAll(actor); notify(`EWS scan raised ${s.length} signal(s)`); reloadAll(); }
    catch (e: any) { notify(e.message, true); }
  };

  const monitorSweep = async () => {
    try {
      const r = await portfolio.monitorSweepAll(actor);
      const escalated = r.filter((x: any) => x.escalated);
      notify(escalated.length
        ? `Monitoring sweep escalated ${escalated.length} deal(s) to collections`
        : `Monitoring sweep: ${r.length} deal(s) scanned, none escalated`);
      reloadAll();
    } catch (e: any) { notify(e.message, true); }
  };

  const s = summary.data;

  // ---- derived KPIs from the one-shot MIS dashboard payload (all deterministic) ----
  const comp = dash.data?.composition || {};
  const variance = dash.data?.rarocVariance || {};
  const ageing = dash.data?.pipelineAgeing || {};
  const wl = dash.data?.watchlist || {};

  const counts: Record<string, number> = ageing.countByStatus || {};
  const ages: Record<string, number> = ageing.avgAgeDays || {};
  const pipelineCount = Object.values(counts).reduce((a, b) => a + (b || 0), 0);
  let ageDays = 0, ageN = 0;
  Object.keys(ages).forEach((k) => { const n = counts[k] || 0; ageDays += (ages[k] || 0) * n; ageN += n; });
  const avgAge = ageN ? ageDays / ageN : null;

  const sev: Record<string, number> = wl.bySeverity || {};
  const critical = (sev.SEVERE || 0) + (sev.HIGH || 0);
  const segmentCount = Object.keys(comp.bySegment || {}).length;
  const jurisdictionCount = Object.keys(comp.byJurisdiction || {}).length;

  return (
    <div className="grid">
      <div className="grid cols-4">
        <Stat label="Booked exposures" value={s ? s.exposureCount : "—"} />
        <Stat label="Total EAD" value={s ? fmt.money(s.totalEad) : "—"} />
        <Stat label="Total RWA" value={s ? fmt.money(s.totalRwa) : "—"} />
        <Stat label="Reported provision (ECL/IRAC)" value={s ? fmt.money(s.totalReportedProvision) : "—"} />
      </div>

      {/* Expanded credit-portfolio KPIs — deterministic MIS aggregations across the book. */}
      <div className="grid cols-4">
        <Stat label="RAROC variance (avg)"
          value={variance.averageVariance != null ? fmt.pct(variance.averageVariance, 2) : "—"}
          delta={variance.averageVariance == null ? undefined
            : variance.materialMisses ? `${variance.materialMisses} material miss(es)` : "within tolerance"}
          tone={variance.materialMisses ? "var(--bad)" : "var(--ok)"} />
        <Stat label="Open EWS signals"
          value={wl.openCount ?? 0}
          delta={critical ? `${critical} severe/high` : "none critical"}
          tone={critical ? "var(--bad)" : "var(--ok)"} />
        <Stat label="Exposures on book"
          value={dash.data ? (comp.exposureCount ?? pipelineCount) : "—"}
          delta={avgAge != null ? `${fmt.num(avgAge, 0)} avg days on book` : undefined} />
        <Stat label="Portfolio spread"
          value={segmentCount ? `${segmentCount} segment${segmentCount === 1 ? "" : "s"}` : "—"}
          delta={jurisdictionCount ? `${jurisdictionCount} jurisdiction${jurisdictionCount === 1 ? "" : "s"}` : undefined} />
      </div>

      <div className="btnrow">
        <Button onClick={scanAll}>Run EWS scan across book</Button>
        <Button kind="primary" onClick={monitorSweep}>Run monitoring sweep</Button>
        <Button kind="subtle" onClick={reloadAll}>Refresh</Button>
        <span className="muted">Acting as {actor}</span>
      </div>

      <div className="grid cols-2">
        <Card title="IFRS 9 / Ind AS 109 staging" sub="Provision split by ECL stage (reported = per-jurisdiction policy).">
          {s && Object.keys(s.byStage || {}).length ? (
            <table>
              <thead><tr><th>Stage</th><th className="num">Exposures</th><th className="num">Provision</th></tr></thead>
              <tbody>
                {Object.entries(s.byStage).map(([stage, n]: any) => (
                  <tr key={stage}>
                    <td><Badge kind={stage === "STAGE_3" ? "bad" : stage === "STAGE_2" ? "warn" : "ok"}>{stage}</Badge></td>
                    <td className="num">{n as number}</td>
                    <td className="num">{fmt.money((s.provisionByStage || {})[stage] || 0)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : <div className="muted">No exposures booked yet. Approve a deal, then book it on the Deals tab.</div>}
        </Card>

        <Card title="Model fit — rating override rate" sub="Override rate is a model-fit signal (PRD §11; alert &gt; 25%).">
          {overrides.data ? (
            <>
              <div className="stat"><div className="value">{fmt.pct(overrides.data.overrideRate, 1)}</div>
                <div className="muted">{overrides.data.overridden}/{overrides.data.total} MID_CORPORATE ratings overridden</div></div>
              <div style={{ marginTop: 10 }}>
                {overrides.data.exceedsAlertThreshold
                  ? <Badge kind="bad">Exceeds 25% — challenge the model</Badge>
                  : <Badge kind="ok">Within tolerance</Badge>}
              </div>
            </>
          ) : <div className="muted">No ratings yet.</div>}
        </Card>
      </div>

      {/* Book composition — EAD share across segment / grade / jurisdiction. */}
      <div className="grid cols-3">
        <Card title="Composition by segment" sub="Total EAD per segment." right={<DeterministicBadge />}>
          <DistTable map={comp.bySegment} />
        </Card>
        <Card title="Composition by final grade" sub="Total EAD per internal grade.">
          <DistTable map={comp.byGrade} />
        </Card>
        <Card title="Composition by jurisdiction" sub="Total EAD per regime.">
          <DistTable map={comp.byJurisdiction} />
        </Card>
      </div>

      <div className="grid cols-2">
        <Card title="Pipeline ageing" sub="Average days on book by status.">
          {Object.keys(ages).length === 0 ? (
            <div className="muted">No exposures booked yet.</div>
          ) : (
            <div style={{ overflowX: "auto" }}>
              <table>
                <thead><tr><th>Status</th><th className="num">Count</th><th className="num">Avg age (days)</th></tr></thead>
                <tbody>
                  {Object.keys(ages).map((k) => (
                    <tr key={k}>
                      <td><Badge>{k}</Badge></td>
                      <td className="num">{counts[k] || 0}</td>
                      <td className="num">{fmt.num(ages[k], 1)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>

        <Card title="RAROC variance — worst by |Δ|"
          sub="Material miss = |actual − projected| / projected &gt; 25% (PRD §11 model governance).">
          {!variance.worstByVariance || variance.worstByVariance.length === 0 ? (
            <div className="muted">No actual-RAROC observations yet — compute actuals on the workspace.</div>
          ) : (
            <div style={{ overflowX: "auto" }}>
              <table>
                <thead><tr><th>Deal</th><th>Period</th><th className="num">Proj.</th><th className="num">Actual</th><th className="num">Δ</th><th></th></tr></thead>
                <tbody>
                  {variance.worstByVariance.slice(0, 8).map((r: any, i: number) => (
                    <tr key={i}>
                      <td className="mono">{r.reference}</td>
                      <td>{r.period}</td>
                      <td className="num">{fmt.pct(r.projectedRaroc, 2)}</td>
                      <td className="num">{fmt.pct(r.actualRaroc, 2)}</td>
                      <td className="num" style={{ color: r.variance < 0 ? "var(--bad)" : "var(--ok)" }}>{fmt.pct(r.variance, 2)}</td>
                      <td>{r.absVariancePct > 0.25 ? <Badge kind="bad">material</Badge> : <Badge kind="ok">within</Badge>}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      </div>

      {/* Portfolio-360 — book-level roll-up (surfaces vintage + status views not shown elsewhere). */}
      <Card title="Portfolio-360" sub="Book-level roll-up across every booked exposure." right={<DeterministicBadge />}>
        {!p360.data || !p360.data.exposureCount ? (
          <div className="muted">No exposures booked yet. The book-level roll-up populates once deals are booked.</div>
        ) : (
          <>
            <div className="grid cols-4" style={{ marginBottom: 6 }}>
              <Stat label="Exposures" value={p360.data.exposureCount ?? "—"} />
              <Stat label="Total EAD" value={p360.data.totalEad != null ? fmt.money(p360.data.totalEad) : "—"} />
              <Stat label="Total RWA" value={p360.data.totalRwa != null ? fmt.money(p360.data.totalRwa) : "—"} />
              <Stat label="Open signals" value={p360.data.openSignals ?? 0} />
            </div>
            <div className="grid cols-2">
              <div>
                <h4>EAD by vintage year</h4>
                <DistTable map={p360.data.byVintageYear} />
              </div>
              <div>
                <h4>Exposures by status</h4>
                <CountTable map={p360.data.byStatus} />
              </div>
            </div>
          </>
        )}
      </Card>

      <Card title="Concentration vs limits" sub={conc.data ? `Total book ${fmt.money(conc.data.totalExposure)} · single-name & sector caps from the active limit pack` : ""}>
        {conc.data ? (
          <>
            {conc.data.breaches?.length > 0 && (
              <div className="alert err">{conc.data.breaches.length} limit breach(es): {conc.data.breaches[0]}</div>
            )}
            <h4 style={{ marginTop: 6 }}>Single-name</h4>
            <ConcTable lines={conc.data.singleName} />
            <h4 style={{ marginTop: 14 }}>By segment</h4>
            <ConcTable lines={conc.data.segment} />
          </>
        ) : <div className="muted">No exposures yet.</div>}
      </Card>

      {/* Multi-dimensional concentration — compact read-only summary; full drill-down lives on MIS. */}
      <Card title="Multi-dimensional concentration"
        sub={multi.data
          ? `${multi.data.dimensionCount ?? multi.data.dimensions?.length ?? 0} dimensions · ${multi.data.totalBreaches ?? 0} breach(es) · thresholds from the CONCENTRATION_LIMITS pack.`
          : ""}
        right={<DeterministicBadge />}>
        {multi.loading ? <div className="loading">Loading…</div> :
          !multi.data || !multi.data.dimensions?.length ? <div className="muted">No exposures booked yet.</div> : (
            <div style={{ overflowX: "auto" }}>
              <table>
                <thead>
                  <tr><th>Dimension</th><th>Basis</th><th className="num">Limit %</th><th className="num">Buckets</th>
                      <th className="num">Top share</th><th className="num">HHI</th><th>Status</th></tr>
                </thead>
                <tbody>
                  {multi.data.dimensions.map((d: any) => (
                    <tr key={d.dimension}>
                      <td className="mono">{String(d.dimension).replace(/_x_/g, " × ")}</td>
                      <td><Badge kind={d.basis === "CAPITAL" ? "info" : ""}>{d.basis}</Badge></td>
                      <td className="num">{d.limitPct != null ? (d.limitPct * 100).toFixed(0) + "%" : "—"}</td>
                      <td className="num">{d.bucketCount ?? "—"}</td>
                      <td className="num">{fmt.pct(d.topBucketShare, 1)}</td>
                      <td className="num">{d.hhi != null ? d.hhi.toFixed(3) : "—"}</td>
                      <td>{bandSummary(d.bands)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
      </Card>

      <div className="grid cols-2">
        <Card title="Early-warning watchlist" sub="Agentic EWS flags & ranks; humans classify and remediate (never auto-reclassified).">
          {watch.data && watch.data.length ? (
            <table>
              <thead><tr><th>Counterparty</th><th>Signal</th><th>Severity</th><th className="num">Score</th></tr></thead>
              <tbody>
                {watch.data.slice(0, 12).map((sig: any) => (
                  <tr key={sig.id}>
                    <td>{sig.counterpartyName}</td>
                    <td><span className="mono">{sig.signalType}</span></td>
                    <td><Badge kind={sig.severity === "SEVERE" ? "bad" : sig.severity === "HIGH" ? "warn" : "info"}>{sig.severity}</Badge></td>
                    <td className="num">{fmt.num(sig.score, 2)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : <div className="muted">No open signals. Run an EWS scan.</div>}
        </Card>

        <Card title="Stress testing" sub="Baseline / adverse / severe — PD/LGD stress to ECL & RWA impact.">
          {stress.data ? (
            <table>
              <thead><tr><th>Scenario</th><th className="num">ECL</th><th className="num">Δ ECL</th><th className="num">RWA</th></tr></thead>
              <tbody>
                {stress.data.outcomes.map((o: any) => (
                  <tr key={o.scenario}>
                    <td><Badge kind={o.scenario === "SEVERE" ? "bad" : o.scenario === "ADVERSE" ? "warn" : "ok"}>{o.scenario}</Badge></td>
                    <td className="num">{fmt.money(o.stressedEcl)}</td>
                    <td className="num">{fmt.money(o.eclIncrease)}</td>
                    <td className="num">{fmt.money(o.stressedRwa)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : <div className="muted">No exposures yet.</div>}
        </Card>
      </div>
    </div>
  );
}

/** EAD share bars for a {key -> amount} distribution (lifted from the MIS screen). */
function DistTable({ map }: { map: Record<string, number> | undefined }) {
  if (!map || Object.keys(map).length === 0) return <div className="muted">No exposures booked yet.</div>;
  const total = Object.values(map).reduce((a, b) => a + b, 0);
  const sorted = Object.entries(map).sort((a: any, b: any) => b[1] - a[1]);
  return (
    <div style={{ overflowX: "auto" }}>
      <table>
        <thead><tr><th>Key</th><th className="num">EAD</th><th>Share</th></tr></thead>
        <tbody>
          {sorted.map(([k, v]) => (
            <tr key={k}>
              <td>{k}</td>
              <td className="num">{fmt.money(v as number)}</td>
              <td style={{ width: 160 }}>
                <div className="bar"><span style={{ width: `${total > 0 ? ((v as number) / total) * 100 : 0}%` }} /></div>
                <small className="prov">{total > 0 ? (((v as number) / total) * 100).toFixed(1) + "%" : ""}</small>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** Simple {key -> count} table for count distributions (e.g. exposures by status). */
function CountTable({ map }: { map: Record<string, number> | undefined }) {
  if (!map || Object.keys(map).length === 0) return <div className="muted">No exposures booked yet.</div>;
  return (
    <table>
      <tbody>
        {Object.entries(map).sort((a: any, b: any) => b[1] - a[1]).map(([k, v]) => (
          <tr key={k}><td><Badge>{k}</Badge></td><td className="num">{v as number}</td></tr>
        ))}
      </tbody>
    </table>
  );
}

/** Roll-up band badge for a multi-dimensional concentration dimension. */
function bandSummary(b: any) {
  if (!b) return <Badge kind="ok">within</Badge>;
  if (b.breach > 0) return <Badge kind="bad">{b.breach} breach</Badge>;
  if (b.warning > 0) return <Badge kind="bad">{b.warning} warning</Badge>;
  if (b.watch > 0) return <Badge kind="warn">{b.watch} watch</Badge>;
  return <Badge kind="ok">within</Badge>;
}

function ConcTable({ lines }: { lines: any[] }) {
  if (!lines?.length) return <div className="muted">—</div>;
  return (
    <table>
      <thead><tr><th>Name</th><th className="num">Exposure</th><th className="num">Share</th><th>Utilisation</th></tr></thead>
      <tbody>
        {lines.map((l) => (
          <tr key={l.key}>
            <td>{l.label}</td>
            <td className="num">{fmt.money(l.exposure)}</td>
            <td className="num">{fmt.pct(l.share, 1)}</td>
            <td style={{ width: 160 }}>
              <div className="bar"><span className={l.breach ? "over" : ""} style={{ width: `${Math.min(100, l.utilisation * 100)}%` }} /></div>
              <small className="prov">{fmt.pct(l.utilisation, 0)} of limit {l.breach ? "· BREACH" : ""}</small>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
