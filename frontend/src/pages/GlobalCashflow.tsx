import { useState } from "react";
import { globalCashflow, initiation, fmt } from "../api";
import { useApp } from "../app-context";
import { Badge, Button, Card, Col, DataTable, DeterministicBadge, EmptyState, Field, Stat, Toast, useAsync } from "../ui";

/**
 * Global / combined cash-flow (relationship consolidated debt-service). Consolidates each
 * group member's latest CONFIRMED spread figures (revenue, EBITDA proxy, CFO, total debt
 * service) into a combined coverage view with a per-member contribution breakdown. Every
 * figure is a DETERMINISTIC sum of the members' confirmed spreads — a read-side consolidation
 * that never mutates any member's authoritative spread / rating / exposure.
 */
function dscrKind(dscr?: number | null): string {
  if (dscr == null) return "";
  if (dscr < 1.0) return "bad";
  if (dscr < 1.25) return "warn";
  return "ok";
}

export default function GlobalCashflow() {
  const { actor } = useApp();
  const [toast, setToast] = useState<{ text: string; err?: boolean } | null>(null);
  const [groupRef, setGroupRef] = useState("");
  const [busy, setBusy] = useState(false);

  const groups = useAsync(() => initiation.listGroups(), []);
  const history = useAsync(
    () => (groupRef ? globalCashflow.list(groupRef) : Promise.resolve([] as any[])),
    [groupRef],
  );
  const [current, setCurrent] = useState<any | null>(null);

  async function consolidate() {
    if (!groupRef) return;
    setBusy(true);
    try {
      const a = await globalCashflow.assemble(groupRef, actor);
      setCurrent(a);
      setToast({ text: `Consolidated ${a.gcfRef} · ${a.membersIncluded}/${a.membersConsidered} member(s)` });
      history.reload();
    } catch (e: any) {
      setToast({ text: e.message, err: true });
    } finally {
      setBusy(false);
    }
  }

  async function openAssessment(gcfRef: string) {
    try {
      setCurrent(await globalCashflow.get(gcfRef));
    } catch (e: any) {
      setToast({ text: e.message, err: true });
    }
  }

  const ccy = current?.currency ?? "INR";

  const memberCols: Col<any>[] = [
    { key: "ref", header: "Member", render: (r) => <span className="mono">{r.ref}</span>, value: (r) => r.ref ?? "" },
    { key: "name", header: "Name", render: (r) => r.name ?? "—" },
    { key: "revenue", header: "Revenue", align: "right", render: (r) => fmt.money(r.revenue, r.currency ?? ccy), value: (r) => r.revenue },
    { key: "ebitda", header: "EBITDA (proxy)", align: "right", render: (r) => fmt.money(r.ebitda, r.currency ?? ccy), value: (r) => r.ebitda },
    { key: "cfo", header: "CFO", align: "right", render: (r) => fmt.money(r.cfo, r.currency ?? ccy), value: (r) => r.cfo },
    { key: "debtService", header: "Debt service", align: "right", render: (r) => fmt.money(r.debtService, r.currency ?? ccy), value: (r) => r.debtService },
    { key: "dscr", header: "DSCR", align: "right",
      render: (r) => r.dscr == null ? "—" : <Badge kind={dscrKind(r.dscr)}>{r.dscr.toFixed(2)}x</Badge>,
      value: (r) => r.dscr ?? 0 },
  ];

  return (
    <div className="stack">
      <Toast msg={toast} onClose={() => setToast(null)} />

      <Card title="Global / combined cash-flow"
        sub="Relationship consolidated debt-service. Combined DSCR + per-member contributions are a DETERMINISTIC sum of each member's latest confirmed spread — obligor + guarantors + group members. A read-side consolidation: no member's spread, rating or exposure is ever moved."
        right={<div className="gov-chips"><DeterministicBadge label="DETERMINISTIC · CONSOLIDATED" /></div>}>
        <div className="btnrow" style={{ alignItems: "flex-end", gap: 12, flexWrap: "wrap" }}>
          <Field label="Borrower group">
            <select value={groupRef} onChange={(e) => { setGroupRef(e.target.value); setCurrent(null); }}>
              <option value="">— select group —</option>
              {(groups.data ?? []).map((g: any) => (
                <option key={g.reference} value={g.reference}>
                  {g.reference} · {g.name}
                </option>
              ))}
            </select>
          </Field>
          <Button onClick={consolidate} busy={busy} disabled={!groupRef}>Consolidate cash-flow</Button>
        </div>
      </Card>

      {current && (
        <Card title="Combined coverage"
          sub={`${current.gcfRef} · group ${current.groupName ?? current.groupReference} · ${current.membersIncluded} of ${current.membersConsidered} member(s) included · ${current.currency ?? "—"}`}
          right={<div className="btnrow" style={{ gap: 8 }}>
            <DeterministicBadge />
            <Badge kind={dscrKind(current.combinedDscr)}>DSCR {Number(current.combinedDscr).toFixed(2)}x</Badge>
          </div>}>
          <div className="statgrid gcf-stats">
            <Stat label="Combined revenue" value={fmt.money(current.combinedRevenue, ccy)} />
            <Stat label="Combined EBITDA (proxy)" value={fmt.money(current.combinedEbitda, ccy)} />
            <Stat label="Combined CFO" value={fmt.money(current.combinedCfo, ccy)} />
            <Stat label="Combined debt service" value={fmt.money(current.combinedDebtService, ccy)} />
            <Stat label="Combined DSCR"
              value={<Badge kind={dscrKind(current.combinedDscr)}>{Number(current.combinedDscr).toFixed(2)}x</Badge>} />
          </div>
          <div className="gcf-formula">
            Combined DSCR = combined CFO ÷ combined debt service ={" "}
            <span className="mono">{fmt.money(current.combinedCfo, ccy)} ÷ {fmt.money(current.combinedDebtService, ccy)} = {Number(current.combinedDscr).toFixed(4)}x</span>.
            Debt service = interest expense + current-portion LTD.
          </div>

          {(current.warnings ?? []).length > 0 && (
            <div className="gcf-warn">
              <strong>Fail-soft skips:</strong>
              <ul>{current.warnings.map((w: string, i: number) => <li key={i}>{w}</li>)}</ul>
            </div>
          )}

          <div style={{ marginTop: 12 }}>
            <DataTable
              id="gcf-members"
              columns={memberCols}
              rows={current.members ?? []}
              rowKey={(r: any) => String(r.ref)}
              empty={<EmptyState glyph="◴" title="No members with a confirmed spread" sub="A member needs a live application with a confirmed spread to contribute." />}
            />
          </div>
          <div className="gcf-note">Advisory consolidation. Every member figure is quoted verbatim from that member's authoritative confirmed spread; nothing here writes back.</div>
        </Card>
      )}

      {groupRef && (history.data ?? []).length > 0 && (
        <Card title="Consolidation history" sub={`${(history.data ?? []).length} run(s) for this group`}>
          <div className="table-scroll">
            <table>
              <thead><tr><th>Ref</th><th>Members</th><th>Combined CFO</th><th>Combined debt service</th><th>Combined DSCR</th><th>By</th><th>When</th></tr></thead>
              <tbody>
                {(history.data ?? []).map((a: any) => (
                  <tr key={a.gcfRef} className="clickable" onClick={() => openAssessment(a.gcfRef)}>
                    <td className="mono">{a.gcfRef}</td>
                    <td>{a.membersIncluded}/{a.membersConsidered}</td>
                    <td>{fmt.money(a.combinedCfo, a.currency ?? "INR")}</td>
                    <td>{fmt.money(a.combinedDebtService, a.currency ?? "INR")}</td>
                    <td><Badge kind={dscrKind(a.combinedDscr)}>{Number(a.combinedDscr).toFixed(2)}x</Badge></td>
                    <td className="mono">{a.createdBy}</td>
                    <td className="mono" style={{ whiteSpace: "nowrap" }}>{fmt.dateTime(a.createdAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}
    </div>
  );
}
