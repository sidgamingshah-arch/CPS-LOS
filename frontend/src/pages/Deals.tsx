import { useState } from "react";
import { counterparty, origination, fmt } from "../api";
import { useApp } from "../app-context";
import { Badge, Button, Card, type Col, DataTable, EmptyState, Field, statusTone, useAsync } from "../ui";
import { useCodes } from "../code-values";
import { evalPolicy, useFieldPolicy } from "../field-policy";

export default function Deals() {
  const { actor, notify, nav } = useApp();
  const apps = useAsync(() => origination.list(), []);
  const cps = useAsync(() => counterparty.list(), []);
  const facilities = useCodes("FACILITY_TYPE");
  const collaterals = useCodes("COLLATERAL_TYPE");
  const [creating, setCreating] = useState(false);

  const cols: Col<any>[] = [
    { key: "reference", header: "Reference", render: (a) => <span className="mono">{a.reference}</span> },
    { key: "counterpartyName", header: "Counterparty" },
    { key: "facilityType", header: "Facility" },
    {
      key: "requestedAmount", header: "Amount", align: "right",
      render: (a) => fmt.money(a.requestedAmount, a.currency), value: (a) => a.requestedAmount ?? 0,
    },
    {
      key: "status", header: "Status",
      render: (a) => <Badge kind={statusTone(a.status)}>{a.status}</Badge>, value: (a) => a.status ?? "",
    },
  ];

  return (
    <div className="grid">
      <Card title="Origination pipeline" sub="Intake → spread → rate → capital → price → approve → book.">
        <DataTable
          id="deals"
          columns={cols}
          rows={apps.data || []}
          rowKey={(a) => a.reference}
          onRowClick={(a) => nav("workspace", a.reference)}
          toolbarRight={<Button kind="ghost" onClick={() => setCreating((c) => !c)}>{creating ? "Close" : "+ New deal"}</Button>}
          empty={apps.loading ? <div className="loading">Loading…</div> : (
            <EmptyState
              glyph="✦"
              title="No deals on the pipeline yet"
              sub="Click + New deal to start an application — facility, sublimits and collateral. The lifecycle takes it from intake through spreading, rating, pricing, approval and booking."
            />
          )}
        />
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
    // Config-driven field behaviour (FIELD_POLICY): label/help overrides + conditional
    // visibility/required, evaluated against the live form values. Empty policy ⇒ today's form.
    const specs = useFieldPolicy("ORIGINATION_APPLICATION");
    const policy = evalPolicy(specs, f);
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
                  {facilities.map((x) => <option key={x.code} value={x.code}>{x.label}</option>)}
                </select>
              </Field>
              <Field label={policy.requestedAmount?.label || "Requested amount"}
                     hint={policy.requestedAmount?.help} required={policy.requestedAmount?.required}>
                <input type="number" value={f.requestedAmount} onChange={(e) => setF({ ...f, requestedAmount: +e.target.value })} />
              </Field>
              <Field label="Currency"><input value={f.currency} onChange={(e) => setF({ ...f, currency: e.target.value })} /></Field>
              <Field label="Tenor (months)"><input type="number" value={f.tenorMonths} onChange={(e) => setF({ ...f, tenorMonths: +e.target.value })} /></Field>
              <Field label="Collateral type">
                <select value={f.collateralType} onChange={(e) => setF({ ...f, collateralType: e.target.value })}>
                  <option value="">(unsecured)</option>
                  {collaterals.map((x) => <option key={x.code} value={x.code}>{x.label}</option>)}
                </select>
              </Field>
              {!policy.collateralValue?.hidden && (
                <Field label={policy.collateralValue?.label || "Collateral value"}
                       hint={policy.collateralValue?.help} required={policy.collateralValue?.required}>
                  <input type="number" value={f.collateralValue} onChange={(e) => setF({ ...f, collateralValue: +e.target.value })} />
                </Field>
              )}
            </div>
            <Field label="Purpose"><input value={f.purpose} onChange={(e) => setF({ ...f, purpose: e.target.value })} /></Field>
            <Button onClick={submit} busy={busy}>Create &amp; open workspace</Button>
          </>
        )}
      </Card>
    );
  }
}
