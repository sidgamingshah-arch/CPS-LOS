import { Fragment, useState } from "react";
import { mis, fmt, portfolio } from "../api";
import { useApp } from "../app-context";
import { Badge, Button, Card, Field, Stat, useAsync } from "../ui";

function bandBadge(band: string) {
  if (band === "BREACH") return <Badge kind="bad">breach</Badge>;
  if (band === "WARNING") return <Badge kind="bad">warning</Badge>;
  if (band === "WATCH") return <Badge kind="warn">watch</Badge>;
  return <Badge kind="ok">normal</Badge>;
}

function bandSummary(b: any) {
  if (!b) return <Badge kind="ok">within</Badge>;
  if (b.breach > 0) return <Badge kind="bad">{b.breach} breach</Badge>;
  if (b.warning > 0) return <Badge kind="bad">{b.warning} warning</Badge>;
  if (b.watch > 0) return <Badge kind="warn">{b.watch} watch</Badge>;
  return <Badge kind="ok">within</Badge>;
}

export default function Mis() {
  const dash = useAsync(() => mis.dashboard(), []);
  const ecl = useAsync(() => mis.eclByStage(), []);
  const multi = useAsync(() => portfolio.concentrationMulti("IN-RBI"), []);
  const [openDim, setOpenDim] = useState<string | null>(null);

  if (dash.loading) return <div className="loading">Loading MIS dashboard…</div>;
  if (dash.error) return <div className="alert err">{dash.error}</div>;
  const d = dash.data || {};
  const comp = d.composition || {};
  const variance = d.rarocVariance || {};
  const ageing = d.pipelineAgeing || {};
  const watch = d.watchlist || {};

  return (
    <div className="grid">
      <div className="grid cols-4">
        <Stat label="Booked exposures" value={comp.exposureCount ?? "—"} />
        <Stat label="Total EAD" value={comp.totalEad != null ? fmt.money(comp.totalEad) : "—"} />
        <Stat label="RAROC variance (avg)"
          value={variance.averageVariance != null ? (variance.averageVariance * 100).toFixed(2) + "%" : "—"}
          delta={variance.materialMisses ? variance.materialMisses + " material miss(es)" : "within tolerance"}
          tone={variance.materialMisses ? "var(--bad)" : "var(--ok)"} />
        <Stat label="Open EWS signals" value={watch.openCount ?? 0} />
      </div>

      <div className="grid cols-2">
        <Card title="Composition by segment" sub="Total EAD per segment.">
          <DistTable map={comp.bySegment} />
        </Card>
        <Card title="Composition by final grade">
          <DistTable map={comp.byGrade} />
        </Card>
        <Card title="By jurisdiction">
          <DistTable map={comp.byJurisdiction} />
        </Card>
        <Card title="By status">
          <DistTable map={comp.byStatus} />
        </Card>
      </div>

      <Card title="RAROC variance (model fit)" sub="Material miss = |actual − projected| / projected > 25 %; alongside override-rate, this drives PRD §11 governance.">
        {!variance.worstByVariance || variance.worstByVariance.length === 0 ? (
          <div className="muted">No actual-RAROC observations yet — book some deals and compute actuals on the workspace.</div>
        ) : (
          <table>
            <thead><tr><th>Deal</th><th>Period</th><th className="num">Projected</th><th className="num">Actual</th><th className="num">Δ</th><th>|Δ| / projected</th><th></th></tr></thead>
            <tbody>
              {variance.worstByVariance.map((r: any, i: number) => (
                <tr key={i}>
                  <td className="mono">{r.reference}</td>
                  <td>{r.period}</td>
                  <td className="num">{fmt.pct(r.projectedRaroc, 2)}</td>
                  <td className="num">{fmt.pct(r.actualRaroc, 2)}</td>
                  <td className="num" style={{ color: r.variance < 0 ? "var(--bad)" : "var(--ok)" }}>{fmt.pct(r.variance, 2)}</td>
                  <td>{fmt.pct(r.absVariancePct, 1)}</td>
                  <td>{r.absVariancePct > 0.25 ? <Badge kind="bad">material</Badge> : <Badge kind="ok">within</Badge>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>

      <div className="grid cols-2">
        <Card title="Pipeline ageing (avg days on book by status)">
          {!ageing.avgAgeDays || Object.keys(ageing.avgAgeDays).length === 0 ? <div className="muted">No exposures.</div> : (
            <table>
              <thead><tr><th>Status</th><th className="num">Count</th><th className="num">Avg age (days)</th></tr></thead>
              <tbody>
                {Object.keys(ageing.avgAgeDays).map((k) => (
                  <tr key={k}><td><Badge>{k}</Badge></td>
                    <td className="num">{(ageing.countByStatus || {})[k] || 0}</td>
                    <td className="num">{fmt.num(ageing.avgAgeDays[k], 1)}</td></tr>
                ))}
              </tbody>
            </table>
          )}
        </Card>
        <Card title="Watchlist summary">
          <h4>By severity</h4>
          <DistCounts map={watch.bySeverity} />
          <h4 style={{ marginTop: 10 }}>By signal type</h4>
          <DistCounts map={watch.bySignalType} />
        </Card>
      </div>

      <Card title="Multi-dimensional concentration"
        sub={multi.data
          ? `${multi.data.dimensionCount} dimensions · ${multi.data.totalBreaches} breach(es) · thresholds from the CONCENTRATION_LIMITS pack. Click a row for buckets.`
          : "Loading…"}>
        {multi.loading ? <div className="loading">Loading…</div> :
         !multi.data ? <div className="muted">No exposures booked.</div> : (
          <table>
            <thead>
              <tr><th>Dimension</th><th>Basis</th><th className="num">Limit %</th><th className="num">Buckets</th>
                  <th className="num">Top share</th><th className="num">HHI</th><th>Status</th></tr>
            </thead>
            <tbody>
              {multi.data.dimensions.map((d: any) => (
                <Fragment key={d.dimension}>
                  <tr style={{ cursor: "pointer" }}
                      onClick={() => setOpenDim(openDim === d.dimension ? null : d.dimension)}>
                    <td className="mono">{d.dimension.replace(/_x_/g, " × ")}</td>
                    <td><Badge kind={d.basis === "CAPITAL" ? "info" : ""}>{d.basis}</Badge></td>
                    <td className="num">{(d.limitPct * 100).toFixed(0)}%</td>
                    <td className="num">{d.bucketCount}</td>
                    <td className="num">{fmt.pct(d.topBucketShare, 1)}</td>
                    <td className="num">{d.hhi.toFixed(3)}</td>
                    <td>{bandSummary(d.bands)}</td>
                  </tr>
                  {openDim === d.dimension && (
                    <tr>
                      <td colSpan={7} style={{ background: "var(--surface-2, rgba(0,0,0,0.02))" }}>
                        <table>
                          <thead><tr><th>Bucket</th><th className="num">EAD</th><th className="num">Share</th><th className="num">Limit</th><th className="num">Utilisation</th><th>Band</th></tr></thead>
                          <tbody>
                            {d.lines.slice(0, 12).map((l: any) => (
                              <tr key={l.key}>
                                <td>{l.key}</td>
                                <td className="num">{fmt.money(l.exposure, "")}</td>
                                <td className="num">{fmt.pct(l.share, 1)}</td>
                                <td className="num">{fmt.money(l.limitAmount, "")}</td>
                                <td className="num">{fmt.pct(l.utilisation, 0)}</td>
                                <td>{bandBadge(l.band)}</td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </td>
                    </tr>
                  )}
                </Fragment>
              ))}
            </tbody>
          </table>
        )}
      </Card>

      <ConcentrationStressCard />

      <Card title="ECL by stage × jurisdiction" sub="Reported provision per jurisdiction policy (max(ECL, IRAC) for IN-RBI; ECL only for AE-CBUAE).">
        {ecl.loading ? <div className="loading">Loading…</div> :
          !ecl.data?.byJurisdictionStage || Object.keys(ecl.data.byJurisdictionStage).length === 0 ? <div className="muted">No exposures booked.</div> : (
            <table>
              <thead><tr><th>Jurisdiction</th><th className="num">Stage 1</th><th className="num">Stage 2</th><th className="num">Stage 3</th><th className="num">Total</th></tr></thead>
              <tbody>
                {Object.entries(ecl.data.byJurisdictionStage).map(([j, stages]: any) => {
                  const s = stages as Record<string, number>;
                  const total = (s.STAGE_1 || 0) + (s.STAGE_2 || 0) + (s.STAGE_3 || 0);
                  return (
                    <tr key={j}>
                      <td>{j}</td>
                      <td className="num">{fmt.money(s.STAGE_1 || 0, "")}</td>
                      <td className="num">{fmt.money(s.STAGE_2 || 0, "")}</td>
                      <td className="num">{fmt.money(s.STAGE_3 || 0, "")}</td>
                      <td className="num"><b>{fmt.money(total, "")}</b></td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
      </Card>
    </div>
  );
}

function DistTable({ map }: { map: Record<string, number> | undefined }) {
  if (!map || Object.keys(map).length === 0) return <div className="muted">—</div>;
  const total = Object.values(map).reduce((a, b) => a + b, 0);
  const sorted = Object.entries(map).sort((a: any, b: any) => b[1] - a[1]);
  return (
    <table>
      <thead><tr><th>Key</th><th className="num">EAD</th><th>Share</th></tr></thead>
      <tbody>
        {sorted.map(([k, v]) => (
          <tr key={k}>
            <td>{k}</td>
            <td className="num">{fmt.money(v as number, "")}</td>
            <td style={{ width: 180 }}>
              <div className="bar"><span style={{ width: `${total > 0 ? ((v as number) / total) * 100 : 0}%` }} /></div>
              <small className="prov">{total > 0 ? (((v as number) / total) * 100).toFixed(1) + "%" : ""}</small>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function DistCounts({ map }: { map: Record<string, number> | undefined }) {
  if (!map || Object.keys(map).length === 0) return <div className="muted">—</div>;
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

/**
 * Correlation-stressed concentration. Shock a sector's PD and watch it propagate
 * through the seeded correlation matrix into every co-moving sector — the hidden
 * concentration that name-level diversification masks. Stressed expected loss is
 * rolled against the capital buffer.
 */
function ConcentrationStressCard() {
  const { actor, notify } = useApp();
  const [sector, setSector] = useState("INFRASTRUCTURE");
  const [mult, setMult] = useState("4");
  const [res, setRes] = useState<any>(null);
  const [busy, setBusy] = useState(false);

  async function run() {
    setBusy(true);
    try {
      const r = await portfolio.concentrationStress("IN-RBI", {
        shockedSector: sector.toUpperCase().trim(),
        pdMultiplier: parseFloat(mult) || 3,
      }, actor);
      setRes(r);
    } catch (e: any) { notify(e.message, true); } finally { setBusy(false); }
  }

  return (
    <Card title="Correlation-stressed concentration"
      sub="Shock one sector's PD; the correlation matrix propagates it to co-moving sectors. Surfaces the concentration that name diversification hides — a single macro event hitting a correlated cluster at once.">
      <div className="grid cols-3" style={{ alignItems: "end" }}>
        <Field label="Shocked sector">
          <input value={sector} onChange={(e) => setSector(e.target.value)} placeholder="e.g. INFRASTRUCTURE" />
        </Field>
        <Field label="PD multiplier">
          <input value={mult} onChange={(e) => setMult(e.target.value)} />
        </Field>
        <Button kind="primary" busy={busy} onClick={run}>Run stress</Button>
      </div>
      {res && (
        <>
          <div style={{ display: "grid", gridTemplateColumns: "repeat(4,1fr)", gap: 12, margin: "12px 0" }}>
            <Stat label="Base expected loss" value={fmt.money(res.baseExpectedLoss, "")} />
            <Stat label="Stressed expected loss" value={fmt.money(res.stressedExpectedLoss, "")}
              tone={res.capitalBreach ? "bad" : "ok"} />
            <Stat label="Incremental loss" value={fmt.money(res.incrementalLoss, "")} />
            <Stat label="% of capital" value={fmt.pct(res.stressedLossPctOfCapital, 3)}
              delta={res.capitalBreach ? "buffer breached" : "within buffer"}
              tone={res.capitalBreach ? "bad" : "ok"} />
          </div>
          {(res.alerts ?? []).length > 0 && (
            <ul style={{ margin: "0 0 10px", paddingLeft: 18 }}>
              {res.alerts.map((a: string, i: number) => (
                <li key={i} style={{ color: "var(--bad, #c0392b)", fontSize: 13 }}>{a}</li>
              ))}
            </ul>
          )}
          <table>
            <thead>
              <tr><th>Sector</th><th className="num">EAD</th><th className="num">ρ to shock</th>
                  <th className="num">Base PD</th><th className="num">Stressed PD</th>
                  <th className="num">Base EL</th><th className="num">Stressed EL</th><th className="num">Δ Loss</th></tr>
            </thead>
            <tbody>
              {res.sectors.map((s: any) => (
                <tr key={s.sector}>
                  <td>{s.sector}{s.correlationToShock === 1 ? <> <Badge kind="bad">shocked</Badge></> : null}</td>
                  <td className="num">{fmt.money(s.exposure, "")}</td>
                  <td className="num">{fmt.pct(s.correlationToShock, 0)}</td>
                  <td className="num">{fmt.pct(s.avgBasePd, 2)}</td>
                  <td className="num">{fmt.pct(s.avgStressedPd, 2)}</td>
                  <td className="num">{fmt.money(s.baseExpectedLoss, "")}</td>
                  <td className="num">{fmt.money(s.stressedExpectedLoss, "")}</td>
                  <td className="num">{fmt.money(s.incrementalLoss, "")}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </Card>
  );
}
