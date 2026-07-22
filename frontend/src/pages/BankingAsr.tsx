import { useState } from "react";
import { bankingAsr, fmt, origination } from "../api";
import { useApp } from "../app-context";
import {
  AiBadge, Badge, Button, Card, Col, DataTable, DeterministicBadge, EmptyState, Field,
  GovFlow, HumanBadge, Stat, statusTone, useAsync,
} from "../ui";

/**
 * Banking ASR (Account Statement Review) — account-conduct analysis captured during
 * origination (CLoM R1-10). An analyst captures a borrower's banking-arrangement monthly
 * statement lines; the service computes conduct metrics DETERMINISTICALLY (average balance,
 * peak/avg utilisation, credit/debit summations, cheque returns, min/max). An OPTIONAL
 * advisory narrative is drafted at the AI boundary and never mutates a metric. A named human
 * confirms the review. The ASR never writes to a limit, exposure, rating or price.
 */

type LineRow = {
  monthLabel: string;
  openingBalance: string; closingBalance: string;
  totalCredit: string; totalDebit: string;
  peakBalance: string; minBalanceInMonth: string;
  drawn: string;
  chequeReturnsInward: string; chequeReturnsOutward: string;
  transactionCount: string;
};

const emptyLine = (label: string): LineRow => ({
  monthLabel: label, openingBalance: "", closingBalance: "", totalCredit: "", totalDebit: "",
  peakBalance: "", minBalanceInMonth: "", drawn: "", chequeReturnsInward: "", chequeReturnsOutward: "",
  transactionCount: "",
});

export default function BankingAsr() {
  const { actor, notify } = useApp();
  const list = useAsync(() => bankingAsr.list(), []);
  const [selected, setSelected] = useState<string | null>(null);

  const reload = () => { list.reload(); };

  const columns: Col<any>[] = [
    { key: "asrRef", header: "Ref", render: (r) => <span className="mono">{r.asrRef}</span> },
    { key: "applicationRef", header: "Application", render: (r) => <span className="mono">{r.applicationRef}</span> },
    { key: "bankName", header: "Bank" },
    {
      key: "averageBankBalance", header: "Avg balance", align: "right",
      value: (r) => r.averageBankBalance || 0,
      render: (r) => <span className="mono">{fmt.money(r.averageBankBalance, r.currency)}</span>,
    },
    {
      key: "avgUtilisationPct", header: "Avg util", align: "right",
      value: (r) => r.avgUtilisationPct || 0,
      render: (r) => <span className="mono">{fmt.pct(r.avgUtilisationPct)}</span>,
    },
    { key: "status", header: "Status", render: (r) => <Badge kind={statusTone(r.status)}>{r.status}</Badge> },
    { key: "createdAt", header: "Created", value: (r) => r.createdAt || "", render: (r) => fmt.dateTime(r.createdAt) },
  ];

  return (
    <div className="grid cols-2">
      <div className="grid">
        <Card
          title="Banking ASR"
          right={<DeterministicBadge label="DETERMINISTIC METRICS" />}
          sub="Account statement review — conduct metrics computed deterministically from the monthly lines."
        >
          <div className="asr-note">
            An ASR captures a borrower's banking-arrangement monthly statements and computes account-conduct
            metrics <b>deterministically</b> (no AI in the figure path). An optional advisory narrative summary
            is human-confirmed. It never writes to a limit, exposure, rating or price.
          </div>
          {(list.data || []).length === 0 ? (
            <EmptyState glyph="◲" title="No banking ASRs yet" sub="Capture one with the form below." />
          ) : (
            <DataTable
              id="banking-asr"
              columns={columns}
              rows={list.data || []}
              rowKey={(r) => r.asrRef}
              onRowClick={(r) => setSelected(r.asrRef)}
              initialPageSize={10}
            />
          )}
        </Card>
        <CreateAsr actor={actor} notify={notify} onDone={(ref) => { reload(); setSelected(ref); }} />
      </div>
      <div>
        {selected ? (
          <AsrDetail asrRef={selected} onChange={reload} />
        ) : (
          <Card>
            <EmptyState glyph="◳" title="Select a banking ASR"
              sub="Click a row to open its computed metrics, advisory summary and confirm gate." />
          </Card>
        )}
      </div>
    </div>
  );
}

function CreateAsr({ actor, notify, onDone }: {
  actor: string; notify: (t: string, e?: boolean) => void; onDone: (ref: string) => void;
}) {
  const apps = useAsync(() => origination.list(), []);
  const [applicationRef, setApplicationRef] = useState("");
  const [bankName, setBankName] = useState("");
  const [accountNoMasked, setAccountNoMasked] = useState("");
  const [currency, setCurrency] = useState("INR");
  const [sanctionedLimit, setSanctionedLimit] = useState("");
  const [periodFrom, setPeriodFrom] = useState("");
  const [periodTo, setPeriodTo] = useState("");
  const [lines, setLines] = useState<LineRow[]>([emptyLine("M1"), emptyLine("M2"), emptyLine("M3")]);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const setLine = (i: number, patch: Partial<LineRow>) =>
    setLines((ls) => ls.map((l, idx) => (idx === i ? { ...l, ...patch } : l)));
  const addLine = () => setLines((ls) => [...ls, emptyLine(`M${ls.length + 1}`)]);
  const removeLine = (i: number) => setLines((ls) => ls.filter((_, idx) => idx !== i));

  const num = (v: string) => (v.trim() === "" ? 0 : Number(v));

  const submit = async () => {
    if (!applicationRef.trim()) { setErr("Select an application"); return; }
    if (!bankName.trim()) { setErr("Bank name is required"); return; }
    if (lines.length === 0) { setErr("At least one monthly line is required"); return; }
    if (lines.some((l) => !l.monthLabel.trim())) { setErr("Every line needs a month label"); return; }
    setErr(null); setBusy(true);
    try {
      const a = await bankingAsr.create({
        applicationRef: applicationRef.trim(), bankName: bankName.trim(),
        accountNoMasked: accountNoMasked.trim(), currency,
        sanctionedLimit: num(sanctionedLimit), periodFrom, periodTo,
        lines: lines.map((l) => ({
          monthLabel: l.monthLabel.trim(),
          openingBalance: num(l.openingBalance), closingBalance: num(l.closingBalance),
          totalCredit: num(l.totalCredit), totalDebit: num(l.totalDebit),
          peakBalance: num(l.peakBalance), minBalanceInMonth: num(l.minBalanceInMonth),
          drawn: num(l.drawn),
          chequeReturnsInward: num(l.chequeReturnsInward), chequeReturnsOutward: num(l.chequeReturnsOutward),
          transactionCount: Math.trunc(num(l.transactionCount)),
        })),
      }, actor);
      notify(`ASR ${a.asrRef} computed (DRAFT)`);
      onDone(a.asrRef);
    } catch (e: any) { notify(e.message, true); }
    finally { setBusy(false); }
  };

  return (
    <Card title="Capture a banking ASR" sub="Metrics are computed deterministically on save; you then confirm the review.">
      <Field label="Application" required hint="Origination application the statements belong to"
        error={err && !applicationRef.trim() ? err : null}>
        <select value={applicationRef} onChange={(e) => setApplicationRef(e.target.value)}>
          <option value="">— select an application —</option>
          {(apps.data || []).map((a: any) => (
            <option key={a.reference} value={a.reference}>{a.reference} · {a.counterpartyName}</option>
          ))}
        </select>
      </Field>
      <div className="grid cols-2">
        <Field label="Bank" required error={err && applicationRef.trim() && !bankName.trim() ? err : null}>
          <input value={bankName} onChange={(e) => setBankName(e.target.value)} placeholder="e.g. State Bank" />
        </Field>
        <Field label="Account (masked)">
          <input value={accountNoMasked} onChange={(e) => setAccountNoMasked(e.target.value)} placeholder="e.g. XXXXXX1234" />
        </Field>
        <Field label="Currency" required>
          <input value={currency} onChange={(e) => setCurrency(e.target.value.toUpperCase())} maxLength={5} />
        </Field>
        <Field label="Sanctioned limit" hint="Utilisation is drawn ÷ this limit">
          <input value={sanctionedLimit} onChange={(e) => setSanctionedLimit(e.target.value)} inputMode="numeric" placeholder="e.g. 100000000" />
        </Field>
        <Field label="Period from">
          <input value={periodFrom} onChange={(e) => setPeriodFrom(e.target.value)} placeholder="e.g. 2025-01" />
        </Field>
        <Field label="Period to">
          <input value={periodTo} onChange={(e) => setPeriodTo(e.target.value)} placeholder="e.g. 2025-03" />
        </Field>
      </div>

      <div className="asr-lines-head">
        <b>Monthly conduct lines</b>
        <button className="btn ghost" type="button" onClick={addLine}>+ Add month</button>
      </div>
      <div className="asr-lines">
        {lines.map((l, i) => (
          <div className="asr-line" key={i}>
            <div className="asr-line-top">
              <input className="asr-month" value={l.monthLabel} onChange={(e) => setLine(i, { monthLabel: e.target.value })} placeholder="Month" />
              {lines.length > 1 && (
                <button className="btn danger small" type="button" onClick={() => removeLine(i)}>Remove</button>
              )}
            </div>
            <div className="asr-line-grid">
              <label>Opening<input value={l.openingBalance} onChange={(e) => setLine(i, { openingBalance: e.target.value })} inputMode="numeric" /></label>
              <label>Closing<input value={l.closingBalance} onChange={(e) => setLine(i, { closingBalance: e.target.value })} inputMode="numeric" /></label>
              <label>Credits<input value={l.totalCredit} onChange={(e) => setLine(i, { totalCredit: e.target.value })} inputMode="numeric" /></label>
              <label>Debits<input value={l.totalDebit} onChange={(e) => setLine(i, { totalDebit: e.target.value })} inputMode="numeric" /></label>
              <label>Peak bal<input value={l.peakBalance} onChange={(e) => setLine(i, { peakBalance: e.target.value })} inputMode="numeric" /></label>
              <label>Min bal<input value={l.minBalanceInMonth} onChange={(e) => setLine(i, { minBalanceInMonth: e.target.value })} inputMode="numeric" /></label>
              <label>Drawn<input value={l.drawn} onChange={(e) => setLine(i, { drawn: e.target.value })} inputMode="numeric" /></label>
              <label>Chq in<input value={l.chequeReturnsInward} onChange={(e) => setLine(i, { chequeReturnsInward: e.target.value })} inputMode="numeric" /></label>
              <label>Chq out<input value={l.chequeReturnsOutward} onChange={(e) => setLine(i, { chequeReturnsOutward: e.target.value })} inputMode="numeric" /></label>
              <label>Txns<input value={l.transactionCount} onChange={(e) => setLine(i, { transactionCount: e.target.value })} inputMode="numeric" /></label>
            </div>
          </div>
        ))}
      </div>
      <Button onClick={submit} busy={busy}>Compute ASR</Button>
    </Card>
  );
}

function AsrDetail({ asrRef, onChange }: { asrRef: string; onChange: () => void }) {
  const { actor, notify } = useApp();
  const view = useAsync(() => bankingAsr.get(asrRef), [asrRef]);

  const run = async (fn: () => Promise<any>, ok: string) => {
    try { await fn(); notify(ok); view.reload(); onChange(); }
    catch (e: any) { notify(e.message, true); }
  };

  if (view.loading) return <Card title="Loading…"><div className="loading" /></Card>;
  if (view.error) return <Card title="Error"><div className="err">{view.error}</div></Card>;
  const a = view.data;
  const st: string = a.status;
  const ccy = a.currency;

  return (
    <div className="grid">
      <Card title={`Banking ASR · ${a.asrRef}`}
        sub={`${a.bankName}${a.accountNoMasked ? ` · ${a.accountNoMasked}` : ""} · ${a.applicationRef}`}
        right={<Badge kind={statusTone(st)}>{st}</Badge>}>
        <div className="gate">
          <DeterministicBadge /> These figures are computed deterministically from the monthly lines — no AI in
          the figure path. The ASR never mutates a limit, exposure, rating or price.
        </div>
        <div className="asr-stats">
          <Stat label="Average bank balance" value={fmt.moneyFull(a.averageBankBalance, ccy)} />
          <Stat label="Avg utilisation" value={fmt.pct(a.avgUtilisationPct)} />
          <Stat label="Peak utilisation" value={fmt.pct(a.peakUtilisationPct)} />
          <Stat label="Total credits" value={fmt.moneyFull(a.totalCredits, ccy)} />
          <Stat label="Total debits" value={fmt.moneyFull(a.totalDebits, ccy)} />
          <Stat label="Credit summation (monthly avg)" value={fmt.moneyFull(a.creditSummationMonthlyAvg, ccy)} />
          <Stat label="Cheque returns (in / out)" value={`${fmt.num(a.chequeReturnsInward, 0)} / ${fmt.num(a.chequeReturnsOutward, 0)}`} />
          <Stat label="Balance min / max" value={`${fmt.money(a.minBalance, ccy)} / ${fmt.money(a.maxBalance, ccy)}`} />
          <Stat label="Transactions" value={fmt.num(a.transactionCount, 0)} />
          <Stat label="Sanctioned limit" value={fmt.moneyFull(a.sanctionedLimit, ccy)} />
        </div>
      </Card>

      <Card title="Monthly conduct">
        <table className="asr-month-table">
          <thead>
            <tr><th>Month</th><th>Opening</th><th>Closing</th><th>Credits</th><th>Debits</th><th>Util</th><th>Chq returns</th></tr>
          </thead>
          <tbody>
            {(a.lines || []).map((l: any) => (
              <tr key={l.id}>
                <td>{l.monthLabel}</td>
                <td className="mono">{fmt.money(l.openingBalance, ccy)}</td>
                <td className="mono">{fmt.money(l.closingBalance, ccy)}</td>
                <td className="mono">{fmt.money(l.totalCredit, ccy)}</td>
                <td className="mono">{fmt.money(l.totalDebit, ccy)}</td>
                <td className="mono">{fmt.pct(l.utilisationPct)}</td>
                <td className="mono">{fmt.num(l.chequeReturns, 0)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>

      <Card title="Advisory summary" right={<AiBadge />}
        sub="Optional narrative drafted at the AI boundary — advisory, human-confirmed, metrics unchanged.">
        <GovFlow ai="AI DRAFTS SUMMARY" human="HUMAN CONFIRMS REVIEW"
          note="the deterministic metrics above are never changed by the narrative" />
        {a.advisorySummary
          ? <p className="prov asr-summary">{a.advisorySummary}</p>
          : <p className="muted">No summary drafted yet.</p>}
        <div className="btnrow">
          <Button kind="ghost" onClick={() => run(() => bankingAsr.summary(asrRef, actor), "Advisory summary drafted")}>
            {a.advisorySummary ? "Redraft summary" : "Draft advisory summary"}
          </Button>
          {st === "DRAFT" && (
            <Button onClick={() => run(() => bankingAsr.confirm(asrRef, undefined, actor), "ASR confirmed")}>Confirm review</Button>
          )}
          {st === "CONFIRMED" && (
            <span className="asr-confirmed"><HumanBadge label="CONFIRMED" /> by {a.confirmedBy}{a.confirmedAt ? ` · ${fmt.dateTime(a.confirmedAt)}` : ""}</span>
          )}
        </div>
        <small className="prov">
          Acting as <b>{actor}</b>. Confirm records named-human accountability for the reviewed conduct record.
          The advisory summary is best-effort at the AI boundary; when no model is wired a deterministic template is used.
        </small>
      </Card>
    </div>
  );
}
