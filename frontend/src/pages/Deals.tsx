import { useState } from "react";
import { counterparty, origination, fmt } from "../api";
import { useApp } from "../app-context";
import { Badge, Button, Card, EmptyState, Field, statusTone, useAsync } from "../ui";

const FACILITIES = ["TERM_LOAN", "WORKING_CAPITAL", "REVOLVING_CREDIT", "PROJECT_LOAN", "GUARANTEE", "TRADE_LINE"];
const COLLATERAL = ["", "CASH", "GOVT_SECURITIES", "PROPERTY", "RECEIVABLES", "EQUITY_LISTED"];

export default function Deals() {
  const { actor, notify, nav } = useApp();
  const apps = useAsync(() => origination.list(), []);
  const cps = useAsync(() => counterparty.list(), []);
  const [creating, setCreating] = useState(false);

  return (
    <div className="grid">
      <Card title="Origination pipeline" sub="Intake → spread → rate → capital → price → approve → book."
        right={<Button kind="ghost" onClick={() => setCreating((c) => !c)}>{creating ? "Close" : "+ New deal"}</Button>}>
        {apps.loading ? <div className="loading">Loading…</div> : (apps.data || []).length === 0 ? (
          <EmptyState
            glyph="✦"
            title="No deals on the pipeline yet"
            sub="Click + New deal to start an application — facility, sublimits and collateral. The lifecycle takes it from intake through spreading, rating, pricing, approval and booking."
          />
        ) : (
          <table>
            <thead><tr><th>Reference</th><th>Counterparty</th><th>Facility</th><th className="num">Amount</th><th>Status</th></tr></thead>
            <tbody>
              {(apps.data || []).map((a: any) => (
                <tr key={a.reference} className="rowlink" onClick={() => nav("workspace", a.reference)}>
                  <td className="mono">{a.reference}</td>
                  <td>{a.counterpartyName}</td>
                  <td>{a.facilityType}</td>
                  <td className="num">{fmt.money(a.requestedAmount, a.currency)}</td>
                  <td><Badge kind={statusTone(a.status)}>{a.status}</Badge></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>

      {creating && (
        <CreateDeal cps={cps.data || []} onDone={(ref) => { setCreating(false); apps.reload(); nav("workspace", ref); }} />
      )}
    </div>
  );

  function CreateDeal({ cps, onDone }: { cps: any[]; onDone: (ref: string) => void }) {
    const [cpId, setCpId] = useState<string>(cps[0]?.id?.toString() || "");
    const [f, setF] = useState<any>({
      facilityType: "TERM_LOAN", requestedAmount: 800000000, currency: "INR", tenorMonths: 60,
      purpose: "Capacity expansion", collateralType: "PROPERTY", collateralValue: 600000000, secured: true,
    });
    const [busy, setBusy] = useState(false);
    const submit = async () => {
      const cp = cps.find((c) => c.id.toString() === cpId);
      if (!cp) { notify("Select a counterparty", true); return; }
      setBusy(true);
      try {
        const app = await origination.create({
          counterpartyId: cp.id, counterpartyRef: cp.reference, counterpartyName: cp.legalName,
          jurisdiction: cp.jurisdiction, segment: cp.segment, ...f,
          secured: !!f.collateralType, collateralValue: f.collateralType ? f.collateralValue : 0,
        }, actor);
        notify(`Created ${app.reference}`); onDone(app.reference);
      } catch (e: any) { notify(e.message, true); } finally { setBusy(false); }
    };
    return (
      <Card title="New deal">
        {cps.length === 0 ? <div className="alert err">Onboard a counterparty first (Counterparties tab).</div> : (
          <>
            <Field label="Counterparty">
              <select value={cpId} onChange={(e) => setCpId(e.target.value)}>
                {cps.map((c) => <option key={c.id} value={c.id}>{c.legalName} · {c.segment} · {c.jurisdiction}</option>)}
              </select>
            </Field>
            <div className="grid cols-2">
              <Field label="Facility type">
                <select value={f.facilityType} onChange={(e) => setF({ ...f, facilityType: e.target.value })}>
                  {FACILITIES.map((x) => <option key={x}>{x}</option>)}
                </select>
              </Field>
              <Field label="Requested amount">
                <input type="number" value={f.requestedAmount} onChange={(e) => setF({ ...f, requestedAmount: +e.target.value })} />
              </Field>
              <Field label="Currency"><input value={f.currency} onChange={(e) => setF({ ...f, currency: e.target.value })} /></Field>
              <Field label="Tenor (months)"><input type="number" value={f.tenorMonths} onChange={(e) => setF({ ...f, tenorMonths: +e.target.value })} /></Field>
              <Field label="Collateral type">
                <select value={f.collateralType} onChange={(e) => setF({ ...f, collateralType: e.target.value })}>
                  {COLLATERAL.map((x) => <option key={x} value={x}>{x || "(unsecured)"}</option>)}
                </select>
              </Field>
              <Field label="Collateral value">
                <input type="number" value={f.collateralValue} onChange={(e) => setF({ ...f, collateralValue: +e.target.value })} />
              </Field>
            </div>
            <Field label="Purpose"><input value={f.purpose} onChange={(e) => setF({ ...f, purpose: e.target.value })} /></Field>
            <Button onClick={submit} busy={busy}>Create &amp; open workspace</Button>
          </>
        )}
      </Card>
    );
  }
}
