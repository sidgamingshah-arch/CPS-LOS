import { portfolio, risk, fmt } from "../api";
import { useApp } from "../app-context";
import { Badge, Button, Card, Stat, useAsync } from "../ui";

export default function Dashboard() {
  const { actor, notify } = useApp();
  const summary = useAsync(() => portfolio.summary(), []);
  const conc = useAsync(() => portfolio.concentration("IN-RBI"), []);
  const watch = useAsync(() => portfolio.watchlist(), []);
  const stress = useAsync(() => portfolio.stress(), []);
  const overrides = useAsync(() => risk.overrideStats("MID_CORPORATE"), []);

  const reloadAll = () => { summary.reload(); conc.reload(); watch.reload(); stress.reload(); overrides.reload(); };

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
  return (
    <div className="grid">
      <div className="grid cols-4">
        <Stat label="Booked exposures" value={s ? s.exposureCount : "—"} />
        <Stat label="Total EAD" value={s ? fmt.money(s.totalEad) : "—"} />
        <Stat label="Total RWA" value={s ? fmt.money(s.totalRwa) : "—"} />
        <Stat label="Reported provision (ECL/IRAC)" value={s ? fmt.money(s.totalReportedProvision) : "—"} />
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
