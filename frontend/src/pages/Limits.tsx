import { useState } from "react";
import { counterparty, fmt, limits as L, origination } from "../api";
import { useApp } from "../app-context";
import { Badge, Button, Card, EmptyState, Field, Stat, statusTone, useAsync } from "../ui";

/**
 * Limit management: build limit tree from a deal, view per-CIF tree with
 * fungibility pools, run product-processor Validation / Utilisation actions,
 * and inspect the immutable utilisation ledger.
 */
export default function Limits() {
  const { actor, notify } = useApp();
  const cps = useAsync(() => counterparty.list(), []);
  const apps = useAsync(() => origination.list(), []);
  const [cif, setCif] = useState<string>("");
  const [showAllLedger, setShowAllLedger] = useState(false);
  const tree = useAsync(() => (cif ? L.view(cif) : Promise.resolve(null)), [cif]);
  const ledger = useAsync(() => (cif ? L.ledger(cif) : Promise.resolve([])), [cif]);

  return (
    <div className="grid">
      <Card title="Limit management"
        sub="Multi-level tree, fungibility pools, View / Validation / Utilisation APIs. Build the tree from an approved deal once.">
        <div className="grid cols-3" style={{ alignItems: "end" }}>
          <Field label="Counterparty (CIF)">
            <select value={cif} onChange={(e) => { setCif(e.target.value); setShowAllLedger(false); }}>
              <option value="">— select —</option>
              {(cps.data || []).map((c: any) => <option key={c.id} value={c.reference}>{c.legalName} · {c.reference}</option>)}
            </select>
          </Field>
          <div>
            <BuildFromDeal apps={apps.data || []} actor={actor} notify={notify}
              onBuilt={(builtCif) => { setCif(builtCif); tree.reload(); ledger.reload(); }} />
          </div>
        </div>
      </Card>

      {!cif && (
        <Card>
          <EmptyState
            glyph="◔"
            title="Select a counterparty to load its limit tree"
            sub="Pick a CIF above, or build a new tree from an approved deal. The View / Validation / Utilisation APIs and the ledger then anchor on that CIF."
          />
        </Card>
      )}

      {tree.data && <TreeView tree={tree.data} reload={() => { tree.reload(); ledger.reload(); }} actor={actor} notify={notify} />}

      {(ledger.data || []).length > 0 && (
        <Card title="Utilisation ledger" sub="Append-only — every UTILISE / RELEASE / RESERVE / REVERSAL recorded.">
          <div className="table-scroll">
          <table>
            <thead><tr><th>When</th><th>Line</th><th>Action</th><th className="num">Amount</th><th>Status</th><th>Note</th></tr></thead>
            <tbody>
              {(showAllLedger ? ledger.data! : ledger.data!.slice(0, 25)).map((u: any) => (
                <tr key={u.id}>
                  <td className="mono">{fmt.dateTime(u.createdAt)}</td>
                  <td className="mono">{u.limitNodeId}</td>
                  <td><Badge>{u.action}</Badge></td>
                  <td className="num">{fmt.money(u.amount, u.currency)}</td>
                  <td><Badge kind={u.status === "CONFIRMED" ? "ok" : "bad"}>{u.status}</Badge>{u.overrideApplied && " · override"}</td>
                  <td><small className="prov">{u.message || ""}</small></td>
                </tr>
              ))}
            </tbody>
          </table>
          </div>
          {ledger.data!.length > 25 && (
            <div className="table-more">
              <span>showing {showAllLedger ? ledger.data!.length : 25} of {ledger.data!.length}</span>
              <button onClick={() => setShowAllLedger((s) => !s)}>{showAllLedger ? "show less" : "show all"}</button>
            </div>
          )}
        </Card>
      )}

      <EodPanel actor={actor} notify={notify} />
    </div>
  );
}

function EodPanel({ actor, notify }: { actor: string; notify: any }) {
  const fxv = useAsync(() => L.eodFx(), []);
  const runs = useAsync(() => L.eodRuns(), []);
  const [selected, setSelected] = useState<number | null>(null);
  const [showAllRuns, setShowAllRuns] = useState(false);
  const detail = useAsync(() => (selected ? L.eodRunDetail(selected) : Promise.resolve(null)), [selected]);

  const reloadAll = () => { fxv.reload(); runs.reload(); detail.reload(); };

  const refresh = async () => {
    const ccy = window.prompt("Currency code (e.g. USD):", "USD");
    if (!ccy) return;
    const rateStr = window.prompt(`Today's mid-market rate for 1 ${ccy.toUpperCase()} in ${fxv.data?.base || "INR"}:`,
      String(fxv.data?.rates?.[ccy.toUpperCase()] || ""));
    if (!rateStr) return;
    try {
      const r = await L.eodRefreshFx({ currency: ccy, rate: +rateStr }, actor);
      notify(`${r.currency}: ${r.previous} → ${r.current}`);
      reloadAll();
    } catch (e: any) { notify(e.message, true); }
  };

  const runEod = async () => {
    try {
      const r = await L.eodRun(actor);
      notify(`EOD #${r.id}: ${r.revaluedCount} revalued · Δ ${fmt.money(r.revaluationDeltaBase)} · ${r.varianceCount} variance(s)`);
      setSelected(r.id);
      reloadAll();
    } catch (e: any) { notify(e.message, true); }
  };

  const latest = runs.data?.[0];

  return (
    <Card title="EOD batch · FX revaluation + utilisation reconciliation"
      sub="Refresh market rates, mark sanctioned limits to today's FX, and reconcile the utilisation ledger against recorded balances. Variances are surfaced for the ops desk."
      right={
        <div className="btnrow">
          <Button kind="ghost" onClick={refresh}>Refresh FX…</Button>
          <Button onClick={runEod}>Run EOD</Button>
        </div>
      }>
      <div className="grid cols-3">
        <Stat label={`Rates (base ${fxv.data?.base || "INR"})`}
          value={<span className="mono" style={{ fontSize: 12 }}>{fxv.data
            ? Object.entries(fxv.data.rates).map(([k, v]) => `${k} ${v}`).join(" · ")
            : "—"}</span>} />
        <Stat label="Last EOD"
          value={latest ? `#${latest.id} · ${fmt.date(latest.runDate)}` : "—"}
          delta={latest ? `${latest.revaluedCount} revalued · ${latest.varianceCount} variance(s)` : undefined} />
        <Stat label="Net revaluation Δ (base)"
          value={latest ? fmt.money(latest.revaluationDeltaBase) : "—"}
          tone={latest && latest.revaluationDeltaBase < 0 ? "var(--bad)" : undefined} />
      </div>

      {(runs.data || []).length > 0 && (
        <>
        <div className="table-scroll" style={{ marginTop: 10 }}>
        <table>
          <thead><tr><th>Run</th><th>Date</th><th>By</th><th className="num">Revalued</th><th className="num">Δ base</th><th className="num">Variances</th><th>Completed</th></tr></thead>
          <tbody>
            {(showAllRuns ? runs.data! : runs.data!.slice(0, 8)).map((r: any) => (
              <tr key={r.id} className={selected === r.id ? "rowlink active" : "rowlink"} onClick={() => setSelected(r.id)}>
                <td className="mono">#{r.id}</td>
                <td className="mono">{fmt.date(r.runDate)}</td>
                <td className="mono">{r.runBy}</td>
                <td className="num">{r.revaluedCount}</td>
                <td className="num">{fmt.money(r.revaluationDeltaBase)}</td>
                <td className="num">{r.varianceCount > 0 ? <Badge kind="warn">{r.varianceCount}</Badge> : 0}</td>
                <td className="mono"><small className="prov">{fmt.dateTime(r.completedAt)}</small></td>
              </tr>
            ))}
          </tbody>
        </table>
        </div>
        {runs.data!.length > 8 && (
          <div className="table-more">
            <span>showing {showAllRuns ? runs.data!.length : 8} of {runs.data!.length}</span>
            <button onClick={() => setShowAllRuns((s) => !s)}>{showAllRuns ? "show less" : "show all"}</button>
          </div>
        )}
        </>
      )}

      {detail.data && (
        <div className="grid cols-2" style={{ marginTop: 10 }}>
          <Card title={`Revaluations · run #${selected}`}
            sub={(detail.data.revaluations || []).length === 0 ? "No revaluations" : `${detail.data.revaluations.length} entry(ies)`}>
            {(detail.data.revaluations || []).length > 0 && (
              <div className="table-scroll">
              <table>
                <thead><tr><th>Line</th><th>Ccy</th><th className="num">Sanctioned</th><th className="num">Old base</th><th className="num">New base</th><th className="num">Δ</th><th className="num">Rate</th></tr></thead>
                <tbody>
                  {detail.data.revaluations.map((r: any) => (
                    <tr key={r.id}>
                      <td><b>{r.code}</b></td>
                      <td className="mono">{r.currency}</td>
                      <td className="num">{fmt.money(r.sanctionedAmount, r.currency)}</td>
                      <td className="num">{fmt.money(r.oldBase)}</td>
                      <td className="num">{fmt.money(r.newBase)}</td>
                      <td className="num">{fmt.money(r.delta)}</td>
                      <td className="num mono">{r.fxRate}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              </div>
            )}
          </Card>
          <Card title={`Reconciliation variances · run #${selected}`}
            sub={(detail.data.variances || []).length === 0 ? "No variances — ledger reconciles" : `${detail.data.variances.length} variance(s)`}>
            {(detail.data.variances || []).length > 0 ? (
              <table>
                <thead><tr><th>Line</th><th>Scope</th><th>Field</th><th className="num">Recorded</th><th className="num">Computed</th><th className="num">Δ</th></tr></thead>
                <tbody>
                  {detail.data.variances.map((v: any) => (
                    <tr key={v.id}>
                      <td><b>{v.code}</b></td>
                      <td><Badge>{v.scope}</Badge></td>
                      <td className="mono">{v.field}</td>
                      <td className="num">{fmt.money(v.recorded)}</td>
                      <td className="num">{fmt.money(v.computed)}</td>
                      <td className="num"><Badge kind={Math.abs(v.variance) > 0 ? "bad" : ""}>{fmt.money(v.variance)}</Badge></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            ) : <div className="muted">Clean run.</div>}
          </Card>
        </div>
      )}
    </Card>
  );
}

function BuildFromDeal({ apps, actor, notify, onBuilt }: { apps: any[]; actor: string; notify: any; onBuilt: (cif: string) => void }) {
  const [ref, setRef] = useState<string>("");
  const submit = async () => {
    if (!ref) { notify("Pick an approved deal", true); return; }
    try {
      const t = await L.build(ref, actor);
      notify(`Built tree with ${t.nodes.length} nodes`);
      onBuilt(t.cif);
    } catch (e: any) { notify(e.message, true); }
  };
  return (
    <div className="btnrow">
      <select value={ref} onChange={(e) => setRef(e.target.value)} style={{ flex: 1 }}>
        <option value="">— build from deal —</option>
        {apps.map((a: any) => <option key={a.reference} value={a.reference}>{a.reference} · {a.counterpartyName}</option>)}
      </select>
      <Button kind="ghost" onClick={submit}>Build tree</Button>
    </div>
  );
}

function TreeView({ tree, reload, actor, notify }: { tree: any; reload: () => void; actor: string; notify: any }) {
  const root = tree.nodes.find((n: any) => n.level === 0);
  return (
    <div className="grid">
      <Card title={`Limit tree · ${tree.cif}`}
        sub={`Base ${tree.baseCurrency} · sanctioned ${fmt.money(tree.totalSanctionedBase)} · outstanding ${fmt.money(tree.totalOutstandingBase)} · available ${fmt.money(tree.totalAvailableBase)}`}>
        {tree.interchangeabilityGroups?.length > 0 && (
          <div style={{ marginBottom: 10 }}>
            <small className="prov">Fungibility pools:</small>
            <ul style={{ margin: "4px 0 0", paddingLeft: 18, fontSize: 12 }}>
              {tree.interchangeabilityGroups.map((g: any) => (
                <li key={g.groupKey}><Badge kind="ai">{g.groupKey}</Badge> · combined cap <b>{fmt.money(g.combinedCap)}</b> · members: {g.members.join(", ")}</li>
              ))}
            </ul>
          </div>
        )}
        {tree.nodes.length === 0 ? <div className="muted">No tree.</div> : (
          <div className="table-scroll">
          <table>
            <thead><tr><th></th><th>Code</th><th>Reference</th><th className="num">Sanctioned (base)</th>
              <th className="num">Outstanding</th><th className="num">Available</th><th>Type</th><th>Status</th><th>Action</th></tr></thead>
            <tbody>
              {tree.nodes.map((n: any) => (
                <tr key={n.id}>
                  <td style={{ paddingLeft: 8 + n.level * 16 }}>{"·".repeat(n.level)}</td>
                  <td><b>{n.code}</b>{n.fungible && <span title="fungible"> ↔</span>}</td>
                  <td className="mono">{n.reference}</td>
                  <td className="num">{fmt.money(n.baseAmount)}</td>
                  <td className="num">{fmt.money(n.outstanding)}</td>
                  <td className="num">{fmt.money(n.available)}</td>
                  <td><Badge>{n.revolving ? "REV" : "TERM"}</Badge>{n.interchangeableGroup && <> <Badge kind="ai">{n.interchangeableGroup}</Badge></>}</td>
                  <td><Badge kind={statusTone(n.status)}>{n.status}</Badge></td>
                  <td>
                    {n.level > 0 && (
                      <div className="btnrow">
                        <ActionButton cif={tree.cif} line={n.reference} action="UTILISE" available={n.available}
                          actor={actor} notify={notify} reload={reload} />
                        <ActionButton cif={tree.cif} line={n.reference} action="RELEASE"
                          actor={actor} notify={notify} reload={reload} />
                        <FreezeButton id={n.id} status={n.status} actor={actor} notify={notify} reload={reload} />
                      </div>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          </div>
        )}
      </Card>
    </div>
  );
}

function ActionButton({ cif, line, action, available, actor, notify, reload }:
                     { cif: string; line: string; action: string; available?: number; actor: string; notify: any; reload: () => void }) {
  const click = async () => {
    const def = available ? Math.round(available * 0.1) : 100000;
    const amt = window.prompt(`${action} amount for ${line}:`, String(def));
    if (!amt) return;
    try {
      const r = await L.utilise({ cif, productProcessor: "UI",
        actions: [{ lineId: line, action, amount: +amt, currency: "INR" }] }, actor);
      const ok = r.results?.[0]?.success;
      notify(ok ? `${action} OK · new available ${r.results[0].newAvailable.toLocaleString()}`
        : `${action} rejected: ${r.results?.[0]?.message}`, !ok);
      reload();
    } catch (e: any) { notify(e.message, true); }
  };
  return <button className="btn subtle" style={{ fontSize: 11, padding: "3px 8px" }} onClick={click}>{action}</button>;
}

function FreezeButton({ id, status, actor, notify, reload }:
                     { id: number; status: string; actor: string; notify: any; reload: () => void }) {
  const isFrozen = status === "FROZEN";
  const click = async () => {
    try {
      if (isFrozen) await L.unfreeze(id, actor); else await L.freeze(id, { reason: "manual" }, actor);
      notify(isFrozen ? "Unfrozen" : "Frozen");
      reload();
    } catch (e: any) { notify(e.message, true); }
  };
  return <button className="btn subtle" style={{ fontSize: 11, padding: "3px 8px" }} onClick={click}>{isFrozen ? "Unfreeze" : "Freeze"}</button>;
}
