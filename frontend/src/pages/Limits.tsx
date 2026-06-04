import { useState } from "react";
import { counterparty, fmt, limits as L, origination } from "../api";
import { useApp } from "../app-context";
import { Badge, Button, Card, Field, statusTone, useAsync } from "../ui";

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
  const tree = useAsync(() => (cif ? L.view(cif) : Promise.resolve(null)), [cif]);
  const ledger = useAsync(() => (cif ? L.ledger(cif) : Promise.resolve([])), [cif]);

  return (
    <div className="grid">
      <Card title="Limit management"
        sub="Multi-level tree, fungibility pools, View / Validation / Utilisation APIs. Build the tree from an approved deal once.">
        <div className="grid cols-3" style={{ alignItems: "end" }}>
          <Field label="Counterparty (CIF)">
            <select value={cif} onChange={(e) => setCif(e.target.value)}>
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

      {tree.data && <TreeView tree={tree.data} reload={() => { tree.reload(); ledger.reload(); }} actor={actor} notify={notify} />}

      {(ledger.data || []).length > 0 && (
        <Card title="Utilisation ledger" sub="Append-only — every UTILISE / RELEASE / RESERVE / REVERSAL recorded.">
          <table>
            <thead><tr><th>When</th><th>Line</th><th>Action</th><th className="num">Amount</th><th>Status</th><th>Note</th></tr></thead>
            <tbody>
              {ledger.data!.slice(0, 25).map((u: any) => (
                <tr key={u.id}>
                  <td className="mono">{new Date(u.createdAt).toLocaleString()}</td>
                  <td className="mono">{u.limitNodeId}</td>
                  <td><Badge>{u.action}</Badge></td>
                  <td className="num">{fmt.money(u.amount, u.currency)}</td>
                  <td><Badge kind={u.status === "CONFIRMED" ? "ok" : "bad"}>{u.status}</Badge>{u.overrideApplied && " · override"}</td>
                  <td><small className="prov">{u.message || ""}</small></td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}
    </div>
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
