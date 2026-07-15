import { useState } from "react";
import { origination, portfolio } from "../api";
import { useApp } from "../app-context";
import { Badge, Button, Card, DeterministicBadge, EmptyState, Field, Stat, Toast, Unchanged, useAsync } from "../ui";
import { fmt } from "../api";

/**
 * Working-capital drawing-power monitoring (RBI DP norms). Deterministic + advisory:
 * DP = stock×(1−stockMargin) + debtors×(1−debtorMargin) − creditors, capped at the sanctioned
 * limit; a shortfall is flagged when outstanding exceeds the eligible DP. It never moves the
 * authoritative utilisation — the ledger figure is UNCHANGED.
 */
export default function DrawingPower() {
  const { ref: ctxRef } = useApp();
  const apps = useAsync(() => origination.list(), []);
  const [ref, setRef] = useState(ctxRef ?? "");
  const [facilityRef, setFacilityRef] = useState("");
  const facs = useAsync(() => (ref ? origination.facilities(ref).catch(() => [] as any[]) : Promise.resolve([] as any[])), [ref]);
  const [stock, setStock] = useState("100000000");
  const [debtors, setDebtors] = useState("50000000");
  const [creditors, setCreditors] = useState("20000000");
  const [sanctioned, setSanctioned] = useState("200000000");
  const [outstanding, setOutstanding] = useState("120000000");
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<any>(null);
  const [toast, setToast] = useState<{ text: string; err?: boolean } | null>(null);

  const { data: history, reload } = useAsync(() => (ref ? portfolio.drawingPowerHistory(ref) : Promise.resolve([])), [ref]);

  async function compute() {
    setBusy(true);
    try {
      const r = await portfolio.drawingPower(ref.trim(), {
        facilityRef: facilityRef.trim(), stock: +stock, debtors: +debtors, creditors: +creditors,
        sanctionedLimit: +sanctioned, outstanding: +outstanding, currency: "INR",
      }, "credit.ops");
      setResult(r);
      setToast({ text: r.capped ? "Drawing-power SHORTFALL flagged" : "Within drawing power" });
      reload();
    } catch (e: any) {
      setToast({ text: e.message, err: true });
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="stack">
      <Toast msg={toast} onClose={() => setToast(null)} />
      <Card title="Drawing-power monitoring"
        sub="Working-capital DP from the borrowing base (RBI norms). Deterministic + advisory — flags a shortfall; the authoritative utilisation ledger is never touched."
        right={<div className="gov-chips"><DeterministicBadge label="DETERMINISTIC · ADVISORY" /></div>}>
        <div className="btnrow" style={{ alignItems: "flex-end", gap: 12, flexWrap: "wrap" }}>
          <Field label="Deal">
            <select value={ref} onChange={(e) => { setRef(e.target.value); setFacilityRef(""); }}>
              <option value="">— select deal —</option>
              {(apps.data ?? []).map((a: any) => (
                <option key={a.reference} value={a.reference}>{a.reference} · {a.counterpartyName} · {a.status}</option>
              ))}
            </select>
          </Field>
          <Field label="Facility">
            <select value={facilityRef} onChange={(e) => setFacilityRef(e.target.value)} disabled={!ref}>
              <option value="">— select facility —</option>
              {(facs.data ?? []).map((f: any) => (
                <option key={f.reference} value={f.reference}>
                  {f.reference} · {f.facilityType} · {fmt.money(f.amount, f.currency)}
                </option>
              ))}
            </select>
          </Field>
          <Field label="Stock"><input value={stock} onChange={(e) => setStock(e.target.value)} /></Field>
          <Field label="Debtors"><input value={debtors} onChange={(e) => setDebtors(e.target.value)} /></Field>
          <Field label="Creditors"><input value={creditors} onChange={(e) => setCreditors(e.target.value)} /></Field>
          <Field label="Sanctioned limit"><input value={sanctioned} onChange={(e) => setSanctioned(e.target.value)} /></Field>
          <Field label="Outstanding"><input value={outstanding} onChange={(e) => setOutstanding(e.target.value)} /></Field>
          <Button onClick={compute} busy={busy} disabled={!ref.trim() || !facilityRef.trim()}>Compute</Button>
        </div>
      </Card>

      {result && (
        <Card title="Assessment" right={<Badge kind={result.capped ? "bad" : "ok"}>{result.capped ? "SHORTFALL" : "WITHIN DP"}</Badge>}>
          <div className="statgrid">
            <Stat label="Drawing power" value={fmt.money(result.drawingPower)} />
            <Stat label="Sanctioned limit" value={<span>{fmt.money(result.sanctionedLimit)} <Unchanged label="LEDGER UNCHANGED" /></span>} />
            <Stat label="Outstanding" value={fmt.money(result.outstanding)} />
            <Stat label="Shortfall" value={fmt.money(result.shortfall)} tone={result.capped ? "var(--bad)" : undefined} />
            <Stat label="Stock margin" value={`${(result.stockMarginPct * 100).toFixed(0)}%`} />
            <Stat label="Debtor margin" value={`${(result.debtorMarginPct * 100).toFixed(0)}%`} />
          </div>
          {result.components && (
            <div className="sub" style={{ marginTop: 8 }}>
              Eligible: stock {fmt.money(result.components.stockEligible)} + debtors {fmt.money(result.components.debtorsEligible)} − creditors {fmt.money(result.components.creditors)} = DP {fmt.money(result.components.eligibleDrawingPower)}
            </div>
          )}
        </Card>
      )}

      {ref && (
        <Card title="History" sub={`${(history || []).length} assessment(s)`}>
          {(history || []).length === 0 ? <EmptyState glyph="◴" title="No assessments yet" /> : (
            <div className="table-scroll">
            <table>
              <thead><tr><th>When</th><th>Facility</th><th>DP</th><th>Outstanding</th><th>Shortfall</th><th>Status</th></tr></thead>
              <tbody>
                {(history || []).map((h: any) => (
                  <tr key={h.id}>
                    <td className="mono" style={{ whiteSpace: "nowrap" }}>{fmt.dateTime(h.createdAt)}</td>
                    <td className="mono">{h.facilityRef}</td>
                    <td>{fmt.money(h.drawingPower)}</td>
                    <td>{fmt.money(h.outstanding)}</td>
                    <td>{fmt.money(h.shortfall)}</td>
                    <td><Badge kind={h.capped ? "bad" : "ok"}>{h.capped ? "shortfall" : "ok"}</Badge></td>
                  </tr>
                ))}
              </tbody>
            </table>
            </div>
          )}
        </Card>
      )}
    </div>
  );
}
