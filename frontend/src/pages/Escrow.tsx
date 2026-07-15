import { useState } from "react";
import { escrow, fmt } from "../api";
import { useApp } from "../app-context";
import { Badge, Button, Card, Col, DataTable, DeterministicBadge, EmptyState, Field, Stat, Toast, useAsync } from "../ui";

/**
 * Escrow monitoring — a record / monitoring surface (post-disbursement). Accounts host
 * append-only versioned budget lines and category-tagged transactions; the budget-vs-actual
 * read is DETERMINISTIC (sum of tagged transactions vs the active budget line) with a RAG
 * band from the VALIDATION_PARAMETER thresholds. It never moves an authoritative ECL /
 * exposure / limit figure.
 */
function ragKind(rag?: string): string {
  if (rag === "RED") return "bad";
  if (rag === "AMBER") return "warn";
  if (rag === "GREEN") return "ok";
  return "";
}

export default function Escrow() {
  const { actor } = useApp();
  const [toast, setToast] = useState<{ text: string; err?: boolean } | null>(null);
  const [ref, setRef] = useState("");
  const accounts = useAsync(() => escrow.list(), []);
  const view = useAsync(() => (ref ? escrow.get(ref) : Promise.resolve(null)), [ref]);
  const summary = useAsync(() => (ref ? escrow.budgetVsActual(ref) : Promise.resolve(null)), [ref]);
  const budgetHist = useAsync(() => (ref ? escrow.budgetHistory(ref) : Promise.resolve([] as any[])), [ref]);

  // create-account form
  const [subjectType, setSubjectType] = useState("OBLIGOR");
  const [subjectRef, setSubjectRef] = useState("");
  const [purpose, setPurpose] = useState("");
  const [currency, setCurrency] = useState("INR");
  const [opening, setOpening] = useState("0");

  // budget-line form
  const [bCategory, setBCategory] = useState("");
  const [bAmount, setBAmount] = useState("");
  const [bEffective, setBEffective] = useState("");
  const [bNote, setBNote] = useState("");

  // transaction form
  const [tAmount, setTAmount] = useState("");
  const [tDirection, setTDirection] = useState("DEBIT");
  const [tCategory, setTCategory] = useState("");
  const [tValueDate, setTValueDate] = useState("");
  const [tMemo, setTMemo] = useState("");

  const [busy, setBusy] = useState(false);
  const acct = (view.data as any)?.account;
  const ccy = acct?.currency ?? "INR";
  const sum = summary.data as any;

  function reloadAll() {
    view.reload();
    summary.reload();
    budgetHist.reload();
  }

  async function createAccount() {
    setBusy(true);
    try {
      const a = await escrow.create({
        subjectType, subjectRef: subjectRef.trim(), purpose: purpose.trim(),
        currency: currency.trim(), openingBalance: +opening || 0,
      }, actor);
      setToast({ text: `Escrow ${a.escrowRef} opened` });
      setSubjectRef(""); setPurpose("");
      accounts.reload();
      setRef(a.escrowRef);
    } catch (e: any) {
      setToast({ text: e.message, err: true });
    } finally {
      setBusy(false);
    }
  }

  async function addBudget() {
    // Re-versioning an existing active category supersedes its baseline — confirm the intent.
    const existing = (view.data as any)?.activeBudgetLines?.some(
      (l: any) => l.category === bCategory.trim());
    if (existing && !window.confirm(
      `A budget line for "${bCategory.trim()}" already exists. Add a new version and supersede the current baseline? (history is preserved)`)) {
      return;
    }
    setBusy(true);
    try {
      const l = await escrow.addBudgetLine(ref, {
        category: bCategory.trim(), budgetedAmount: +bAmount,
        effectiveFrom: bEffective || undefined, note: bNote.trim() || undefined,
      }, actor);
      setToast({ text: `Budget line "${l.category}" saved (v${l.versionNo})` });
      setBAmount(""); setBNote("");
      reloadAll();
    } catch (e: any) {
      setToast({ text: e.message, err: true });
    } finally {
      setBusy(false);
    }
  }

  async function postTxn() {
    setBusy(true);
    try {
      const t = await escrow.postTransaction(ref, {
        amount: +tAmount, direction: tDirection, category: tCategory.trim() || undefined,
        valueDate: tValueDate || undefined, memo: tMemo.trim() || undefined,
      }, actor);
      setToast({ text: `${t.direction} ${fmt.money(t.amount, ccy)} posted` });
      setTAmount(""); setTMemo("");
      reloadAll();
    } catch (e: any) {
      setToast({ text: e.message, err: true });
    } finally {
      setBusy(false);
    }
  }

  const txnCols: Col<any>[] = [
    { key: "createdAt", header: "Posted", render: (r) => <span className="mono">{fmt.dateTime(r.createdAt)}</span>,
      value: (r) => r.createdAt ?? "" },
    { key: "valueDate", header: "Value date", render: (r) => fmt.date(r.valueDate), value: (r) => r.valueDate ?? "" },
    { key: "direction", header: "Direction",
      render: (r) => <Badge kind={r.direction === "CREDIT" ? "ok" : ""}>{r.direction}</Badge> },
    { key: "taggedCategory", header: "Category", render: (r) => r.taggedCategory ?? "—" },
    { key: "amount", header: "Amount", align: "right", render: (r) => fmt.money(r.amount, ccy), value: (r) => r.amount },
    { key: "memo", header: "Memo", render: (r) => r.memo ?? "—" },
    { key: "postedBy", header: "By", render: (r) => <span className="mono">{r.postedBy}</span> },
  ];

  return (
    <div className="stack">
      <Toast msg={toast} onClose={() => setToast(null)} />

      <Card title="Escrow monitoring"
        sub="A record / monitoring surface. Budget-vs-actual is deterministic — the sum of tagged transactions vs the active budget line — with a RAG band from VALIDATION_PARAMETER thresholds. No authoritative ECL / exposure / limit figure is ever moved."
        right={<div className="gov-chips"><DeterministicBadge label="DETERMINISTIC · MONITORING" /></div>}>
        <div className="btnrow" style={{ alignItems: "flex-end", gap: 12, flexWrap: "wrap" }}>
          <Field label="Escrow account">
            <select value={ref} onChange={(e) => setRef(e.target.value)}>
              <option value="">— select account —</option>
              {(accounts.data ?? []).map((a: any) => (
                <option key={a.escrowRef} value={a.escrowRef}>
                  {a.escrowRef} · {a.subjectRef || a.purpose || a.subjectType} · {a.currency}
                </option>
              ))}
            </select>
          </Field>
          {ref && <Button kind="subtle" onClick={reloadAll}>Refresh</Button>}
        </div>
      </Card>

      <Card title="Open an escrow account" sub="Escrow-of-record; opening balance is captured for context only.">
        <div className="btnrow" style={{ alignItems: "flex-end", gap: 12, flexWrap: "wrap" }}>
          <Field label="Subject type">
            <select value={subjectType} onChange={(e) => setSubjectType(e.target.value)}>
              <option value="OBLIGOR">OBLIGOR</option>
              <option value="FACILITY">FACILITY</option>
            </select>
          </Field>
          <Field label="Subject ref"><input value={subjectRef} onChange={(e) => setSubjectRef(e.target.value)} placeholder="obligor / facility ref" /></Field>
          <Field label="Purpose"><input value={purpose} onChange={(e) => setPurpose(e.target.value)} placeholder="e.g. RERA project escrow" /></Field>
          <Field label="Currency"><input value={currency} onChange={(e) => setCurrency(e.target.value)} style={{ width: 80 }} /></Field>
          <Field label="Opening balance"><input value={opening} onChange={(e) => setOpening(e.target.value)} /></Field>
          <Button onClick={createAccount} busy={busy} disabled={!currency.trim()}>Open account</Button>
        </div>
      </Card>

      {ref && sum && (
        <Card title="Budget vs actual"
          sub={`Thresholds ${sum.thresholdSource} · amber ${sum.amberPct}% · red ${sum.redPct}%`}
          right={<div className="btnrow" style={{ gap: 8 }}>
            <DeterministicBadge />
            <Badge kind={ragKind(sum.overallRag)}>{sum.overallRag}</Badge>
          </div>}>
          <div className="statgrid" style={{ marginBottom: 10 }}>
            <Stat label="Total budgeted" value={fmt.money(sum.totalBudgeted, sum.currency)} />
            <Stat label="Total actual (spend)" value={fmt.money(sum.totalActual, sum.currency)} />
            <Stat label="Overall RAG" value={<Badge kind={ragKind(sum.overallRag)}>{sum.overallRag}</Badge>} />
          </div>
          {(sum.categories ?? []).length === 0 ? (
            <EmptyState glyph="◴" title="No active budget lines yet" sub="Add a budget line below to baseline a category." />
          ) : (
            <div className="table-scroll">
              <table>
                <thead>
                  <tr>
                    <th>Category</th><th>Ver</th><th>Budgeted</th><th>Credited</th><th>Debited (actual)</th>
                    <th>Variance</th><th>Utilisation</th><th>RAG</th>
                  </tr>
                </thead>
                <tbody>
                  {sum.categories.map((c: any) => (
                    <tr key={c.category}>
                      <td>{c.category}</td>
                      <td className="mono">v{c.budgetVersion}</td>
                      <td>{fmt.money(c.budgetedAmount, sum.currency)}</td>
                      <td>{fmt.money(c.credited, sum.currency)}</td>
                      <td>{fmt.money(c.actual, sum.currency)}</td>
                      <td style={{ color: c.variance < 0 ? "var(--bad)" : undefined }}>{fmt.money(c.variance, sum.currency)}</td>
                      <td>{c.utilisationPct == null ? "—" : `${c.utilisationPct.toFixed(1)}%`}</td>
                      <td><Badge kind={ragKind(c.rag)}>{c.rag}</Badge></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <div className="escrow-note">Figures are deterministic aggregations; the RAG band is derived from config thresholds only.</div>
        </Card>
      )}

      {ref && (
        <Card title="Add / version a budget line"
          sub="Append-only: a new version for a category supersedes the active baseline and preserves history.">
          <div className="btnrow" style={{ alignItems: "flex-end", gap: 12, flexWrap: "wrap" }}>
            <Field label="Category"><input value={bCategory} onChange={(e) => setBCategory(e.target.value)} placeholder="e.g. MATERIALS" /></Field>
            <Field label="Budgeted amount"><input value={bAmount} onChange={(e) => setBAmount(e.target.value)} /></Field>
            <Field label="Effective from"><input type="date" value={bEffective} onChange={(e) => setBEffective(e.target.value)} /></Field>
            <Field label="Note"><input value={bNote} onChange={(e) => setBNote(e.target.value)} /></Field>
            <Button onClick={addBudget} busy={busy} disabled={!bCategory.trim() || !bAmount}>Save budget line</Button>
          </div>
        </Card>
      )}

      {ref && (
        <Card title="Post a transaction" sub="Tag to a category so it counts toward that category's actuals.">
          <div className="btnrow" style={{ alignItems: "flex-end", gap: 12, flexWrap: "wrap" }}>
            <Field label="Direction">
              <select value={tDirection} onChange={(e) => setTDirection(e.target.value)}>
                <option value="DEBIT">DEBIT (spend)</option>
                <option value="CREDIT">CREDIT (receipt)</option>
              </select>
            </Field>
            <Field label="Amount"><input value={tAmount} onChange={(e) => setTAmount(e.target.value)} /></Field>
            <Field label="Category">
              <select value={tCategory} onChange={(e) => setTCategory(e.target.value)}>
                <option value="">— untagged —</option>
                {((view.data as any)?.activeBudgetLines ?? []).map((l: any) => (
                  <option key={l.category} value={l.category}>{l.category}</option>
                ))}
              </select>
            </Field>
            <Field label="Value date"><input type="date" value={tValueDate} onChange={(e) => setTValueDate(e.target.value)} /></Field>
            <Field label="Memo"><input value={tMemo} onChange={(e) => setTMemo(e.target.value)} /></Field>
            <Button onClick={postTxn} busy={busy} disabled={!tAmount}>Post</Button>
          </div>
        </Card>
      )}

      {ref && (
        <Card title="Transactions" sub={`${((view.data as any)?.transactions ?? []).length} posted`}>
          <DataTable
            id="escrow-txns"
            columns={txnCols}
            rows={(view.data as any)?.transactions ?? []}
            rowKey={(r: any) => String(r.id)}
            empty={<EmptyState glyph="◴" title="No transactions yet" />}
          />
        </Card>
      )}

      {ref && (budgetHist.data ?? []).length > 0 && (
        <Card title="Budget line history" sub="Append-only versions across all categories (active pointer highlighted).">
          <div className="table-scroll">
            <table>
              <thead><tr><th>Category</th><th>Version</th><th>Budgeted</th><th>Effective</th><th>Active</th><th>By</th><th>When</th></tr></thead>
              <tbody>
                {(budgetHist.data ?? []).map((l: any) => (
                  <tr key={l.id}>
                    <td>{l.category}</td>
                    <td className="mono">v{l.versionNo}</td>
                    <td>{fmt.money(l.budgetedAmount, ccy)}</td>
                    <td>{fmt.date(l.effectiveFrom)}</td>
                    <td>{l.active ? <Badge kind="ok">active</Badge> : <span className="muted">superseded</span>}</td>
                    <td className="mono">{l.createdBy}</td>
                    <td className="mono" style={{ whiteSpace: "nowrap" }}>{fmt.dateTime(l.createdAt)}</td>
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
