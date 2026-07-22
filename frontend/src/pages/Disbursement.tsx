/**
 * Pre-disbursement workflow + CP gate UI.
 *
 * Three panes per deal:
 *   1. Conditions Precedent — register seeded from CP_MASTER; clear / waive each.
 *   2. Drawdown tranches — request → authorise (CP gate) → release.
 *   3. Audit trail — every transition stamped with the named human.
 *
 * The gate is enforced server-side (HTTP 403 with blocker payload) — this UI
 * surfaces the same state so credit ops can see exactly what's blocking before
 * even trying to authorise. The "AUTHORISATION GATE · CP-ENFORCED" banner pairs
 * the gate state next to the authoritative facility figure, matching the
 * platform's HUMAN-GATED / DETERMINISTIC chrome.
 *
 * Input capture uses inline expanding panels (no window.prompt): one panel is
 * open at a time, amounts/dates/reasons get real inputs with in-place
 * validation, and destructive transitions (release / reverse) confirm with a
 * danger-styled button. API endpoints and payloads are unchanged.
 */
import { Fragment, useEffect, useMemo, useState, type ReactNode } from "react";
import { cps as cpApi, disbursement, fmt, origination, pf as pfApi, repayments as rpmtApi } from "../api";
import { useApp } from "../app-context";
import {
  Badge,
  Button,
  Card,
  type Col,
  DataTable,
  DeterministicBadge,
  EmptyState,
  Field,
  HumanBadge,
  Stat,
  useAsync,
} from "../ui";

/* ------------------------------------------------------------------------ */
/* Inline action panels — replace every window.prompt() on this screen.      */
/* ------------------------------------------------------------------------ */

/** Shared shell: title + fields + confirm/cancel row. Enter submits, Escape cancels. */
function InlinePanel({ title, danger, busy, disabled, submitLabel, onSubmit, onCancel, children }: {
  title: string; danger?: boolean; busy?: boolean; disabled?: boolean;
  submitLabel: string; onSubmit: () => void; onCancel: () => void; children?: ReactNode;
}) {
  return (
    <div
      className={`inline-panel${danger ? " danger" : ""}`}
      role="group"
      aria-label={title}
      onKeyDown={(e) => {
        const tag = (e.target as HTMLElement).tagName;
        if (e.key === "Escape") {
          // Let native controls consume Escape first (close a <select> popup,
          // abandon in-progress textarea editing) rather than tearing the panel down.
          if (tag !== "TEXTAREA" && tag !== "SELECT") { e.stopPropagation(); onCancel(); }
          return;
        }
        // Enter submits ONLY from a single-line text input — never from a button
        // (so Enter-on-Cancel can't submit), a <select> (so it can't fire before an
        // option is picked), a <textarea>, or a field-less confirmation panel (so an
        // irreversible Release/Reverse needs a deliberate click). Guard IME composition.
        if (e.key === "Enter" && tag === "INPUT" && !(e.nativeEvent as any).isComposing) {
          e.preventDefault();
          if (!disabled && !busy) onSubmit();
        }
      }}>
      <div className="inline-panel-title">{title}</div>
      {children && <div className="inline-panel-fields">{children}</div>}
      <div className="btnrow">
        <Button kind={danger ? "danger" : "primary"} busy={busy} disabled={disabled} onClick={onSubmit}>
          {submitLabel}
        </Button>
        <Button kind="ghost" onClick={onCancel} disabled={busy}>Cancel</Button>
      </div>
    </div>
  );
}

/** Full-width table row hosting an inline panel directly under the row it acts on. */
function PanelRow({ colSpan, children }: { colSpan: number; children: ReactNode }) {
  return (
    <tr className="inline-panel-row">
      <td colSpan={colSpan}>{children}</td>
    </tr>
  );
}

/** Single free-text capture (evidence ref, reason, …). Required fields gate the submit. */
function TextForm({ title, label, placeholder, hint, required, multiline, danger, submitLabel, busy, onSubmit, onCancel }: {
  title: string; label: string; placeholder?: string; hint?: string; required?: boolean;
  multiline?: boolean; danger?: boolean; submitLabel: string; busy?: boolean;
  onSubmit: (value: string) => void; onCancel: () => void;
}) {
  const [value, setValue] = useState("");
  const missing = !!required && value.trim() === "";
  return (
    <InlinePanel title={title} danger={danger} busy={busy} disabled={missing}
      submitLabel={submitLabel} onSubmit={() => onSubmit(value.trim())} onCancel={onCancel}>
      <Field label={label} required={required} hint={hint}>
        {multiline ? (
          <textarea rows={2} value={value} placeholder={placeholder} autoFocus
            onChange={(e) => setValue(e.target.value)} />
        ) : (
          <input type="text" value={value} placeholder={placeholder} autoFocus
            onChange={(e) => setValue(e.target.value)} />
        )}
      </Field>
    </InlinePanel>
  );
}

/** Positive-amount capture with a live formatted preview in the field hint. */
function AmountForm({ title, label, currency, submitLabel, busy, onSubmit, onCancel }: {
  title: string; label: string; currency: string; submitLabel: string; busy?: boolean;
  onSubmit: (amount: number) => void; onCancel: () => void;
}) {
  const [value, setValue] = useState("");
  const amt = value.trim() === "" ? NaN : Number(value);
  const valid = amt > 0;
  return (
    <InlinePanel title={title} busy={busy} disabled={!valid}
      submitLabel={submitLabel} onSubmit={() => onSubmit(amt)} onCancel={onCancel}>
      <Field label={`${label} (${currency})`} required
        error={value.trim() !== "" && !valid ? "Enter a positive amount" : null}
        hint={valid ? `= ${fmt.money(amt, currency)}` : `Positive amount in ${currency}`}>
        <input type="number" min={0} step="0.01" inputMode="decimal" value={value} autoFocus
          onChange={(e) => setValue(e.target.value)} />
      </Field>
    </InlinePanel>
  );
}

/** Drawdown request: amount + (for PF deals) the milestone the tranche draws against. */
function RequestDrawForm({ currency, isPf, milestones, busy, onSubmit, onCancel }: {
  currency: string; isPf: boolean; milestones: any[]; busy?: boolean;
  onSubmit: (amount: number, milestoneSequence?: number) => void; onCancel: () => void;
}) {
  const [value, setValue] = useState("");
  const [seq, setSeq] = useState("");
  const amt = value.trim() === "" ? NaN : Number(value);
  const valid = amt > 0;
  const submit = () => {
    const s = seq.trim() === "" ? NaN : parseInt(seq, 10);
    onSubmit(amt, Number.isNaN(s) ? undefined : s);
  };
  return (
    <InlinePanel title="Request drawdown" busy={busy} disabled={!valid}
      submitLabel="Request drawdown" onSubmit={submit} onCancel={onCancel}>
      <Field label={`Drawdown amount (${currency})`} required
        error={value.trim() !== "" && !valid ? "Enter a positive amount" : null}
        hint={valid ? `= ${fmt.money(amt, currency)}` : `Positive amount in ${currency}`}>
        <input type="number" min={0} step="0.01" inputMode="decimal" value={value} autoFocus
          onChange={(e) => setValue(e.target.value)} />
      </Field>
      {isPf && (milestones.length > 0 ? (
        <Field label="Milestone this tranche draws against"
          hint="The PF gate blocks the draw until this milestone is LIE-certified">
          <select value={seq} onChange={(e) => setSeq(e.target.value)}>
            <option value="">— none —</option>
            {milestones.map((m: any) => (
              <option key={m.id} value={m.sequence}>
                #{m.sequence} · {m.name} · {m.status}
              </option>
            ))}
          </select>
        </Field>
      ) : (
        <Field label="Milestone sequence" hint="e.g. 1 — leave blank if not milestone-gated">
          <input type="number" min={1} step={1} inputMode="numeric" value={seq}
            onChange={(e) => setSeq(e.target.value)} />
        </Field>
      ))}
    </InlinePanel>
  );
}

/** Amend a DRAFT drawdown: new amount + purpose (unchanged purpose is kept). */
function AmendForm({ current, busy, onSubmit, onCancel }: {
  current: any; busy?: boolean;
  onSubmit: (amount: number, purpose: string) => void; onCancel: () => void;
}) {
  const [value, setValue] = useState(String(current.amount ?? ""));
  const [purpose, setPurpose] = useState(current.purpose ?? "");
  const amt = value.trim() === "" ? NaN : Number(value);
  const valid = amt > 0;
  return (
    <InlinePanel title={`Amend drawdown #${current.drawdownNo}`} busy={busy} disabled={!valid}
      submitLabel="Amend drawdown" onSubmit={() => onSubmit(amt, purpose)} onCancel={onCancel}>
      <Field label={`New amount (${current.currency})`} required
        error={value.trim() !== "" && !valid ? "Enter a positive amount" : null}
        hint={valid ? `= ${fmt.money(amt, current.currency)}`
                    : `Current ${fmt.money(current.amount, current.currency)}`}>
        <input type="number" min={0} step="0.01" inputMode="decimal" value={value} autoFocus
          onChange={(e) => setValue(e.target.value)} />
      </Field>
      <Field label="Purpose" hint="Left as-is = keep the current purpose">
        <input type="text" value={purpose} onChange={(e) => setPurpose(e.target.value)} />
      </Field>
    </InlinePanel>
  );
}

/** Record a repayment: amount + optional principal split (blank = all principal). */
function RecordRepaymentForm({ currency, busy, onSubmit, onCancel }: {
  currency: string; busy?: boolean;
  onSubmit: (amount: number, principal: number | null) => void; onCancel: () => void;
}) {
  const [value, setValue] = useState("");
  const [prinValue, setPrinValue] = useState("");
  const amt = value.trim() === "" ? NaN : Number(value);
  const validAmt = amt > 0;
  const prin = prinValue.trim() === "" ? null : Number(prinValue);
  const prinError =
    prin != null && !(prin >= 0) ? "Enter a non-negative number"
    : prin != null && validAmt && prin > amt ? "Cannot exceed the repayment amount"
    : null;
  return (
    <InlinePanel title="Record repayment — a different actor must confirm" busy={busy}
      disabled={!validAmt || !!prinError} submitLabel="Record repayment"
      onSubmit={() => onSubmit(amt, prin)} onCancel={onCancel}>
      <Field label={`Repayment amount (${currency})`} required
        error={value.trim() !== "" && !validAmt ? "Enter a positive amount" : null}
        hint={validAmt ? `= ${fmt.money(amt, currency)}` : `Positive amount in ${currency}`}>
        <input type="number" min={0} step="0.01" inputMode="decimal" value={value} autoFocus
          onChange={(e) => setValue(e.target.value)} />
      </Field>
      <Field label="Principal component" error={prinError}
        hint="Blank = all principal; the remainder books as interest">
        <input type="number" min={0} step="0.01" inputMode="decimal" value={prinValue}
          onChange={(e) => setPrinValue(e.target.value)} />
      </Field>
    </InlinePanel>
  );
}

export default function Disbursement() {
  const { actor, notify } = useApp();
  const apps = useAsync(() => origination.list(), []);
  const [ref, setRef] = useState<string>("");
  const [facilityRef, setFacilityRef] = useState<string>("");

  const app = useAsync(
    () => (ref ? origination.envelope(ref) : Promise.resolve(null)),
    [ref]);

  const facilities = useMemo(() => app.data?.facilities ?? [], [app.data]);

  // Auto-select the first facility once the application loads.
  useEffect(() => {
    if (facilities.length > 0 && !facilityRef) setFacilityRef(facilities[0].reference);
  }, [facilities, facilityRef]);

  const register = useAsync(
    () => (ref ? cpApi.register(ref, facilityRef) : Promise.resolve([])),
    [ref, facilityRef]);

  const gate = useAsync(
    () => (ref && facilityRef ? cpApi.gate(ref, facilityRef) : Promise.resolve(null)),
    [ref, facilityRef, register.data]);

  const history = useAsync(
    () => (ref ? disbursement.history(ref, facilityRef) : Promise.resolve([])),
    [ref, facilityRef]);

  // PF mechanics — milestones + reserves (only populated for project-finance facilities).
  const milestones = useAsync(
    () => (ref && facilityRef ? pfApi.milestones(ref, facilityRef) : Promise.resolve([])),
    [ref, facilityRef]);
  const reserves = useAsync(
    () => (ref ? pfApi.reserves(ref) : Promise.resolve([])),
    [ref]);

  const facility = facilities.find((f: any) => f.reference === facilityRef);
  const isPf = (milestones.data ?? []).length > 0 || (reserves.data ?? []).length > 0;

  // Repayments — the inbound money leg, visible once any draw has been released.
  const rpmts = useAsync(
    () => (ref && facilityRef ? rpmtApi.history(ref, facilityRef) : Promise.resolve([])),
    [ref, facilityRef, history.data]);
  const [scheduleMethod, setScheduleMethod] = useState("EMI");
  const schedule = useAsync(
    () => {
      const released = (history.data ?? []).some((d: any) => d.status === "RELEASED");
      return ref && facilityRef && released
        ? rpmtApi.schedule(ref, facilityRef, scheduleMethod).catch(() => null)
        : Promise.resolve(null);
    },
    [ref, facilityRef, scheduleMethod, history.data, rpmts.data]);
  const [showAllSchedule, setShowAllSchedule] = useState(false);
  const scheduleRows: any[] = schedule.data?.rows ?? [];
  const visibleScheduleRows = showAllSchedule ? scheduleRows : scheduleRows.slice(0, 6);

  // One inline action panel open at a time, keyed "<action>:<row id>".
  const [panel, setPanel] = useState<string | null>(null);
  const [panelBusy, setPanelBusy] = useState(false);
  const closePanel = () => setPanel(null);
  const togglePanel = (key: string) => setPanel((p) => (p === key ? null : key));
  useEffect(() => { setPanel(null); }, [ref, facilityRef]);

  /** Run a panel action: close on success, toast on failure, busy-gate the buttons. */
  async function run(fn: () => Promise<void>) {
    setPanelBusy(true);
    try { await fn(); setPanel(null); }
    catch (e: any) { notify(e.message, true); }
    finally { setPanelBusy(false); }
  }

  const certifyMilestone = (id: number, certificationRef: string) => run(async () => {
    await pfApi.certify(id, { certificationRef }, actor);
    notify("Milestone LIE-certified"); milestones.reload();
  });

  const fundReserve = (id: number, amount: number) => run(async () => {
    await pfApi.fund(id, { amount }, actor);
    notify("Reserve funded"); reserves.reload();
  });

  async function seed() {
    if (!ref) return;
    try {
      const seeded = await cpApi.seed(ref, actor);
      notify(`Seeded ${seeded.length} CP(s) from CP_MASTER`);
      register.reload();
    } catch (e: any) { notify(e.message, true); }
  }

  const clearCp = (id: number, evidenceRef: string) => run(async () => {
    await cpApi.clear(id, { evidenceRef }, actor);
    notify("CP cleared");
    register.reload(); gate.reload();
  });

  const waiveCp = (id: number, reason: string) => run(async () => {
    await cpApi.waive(id, { reason }, actor);
    notify("CP waived");
    register.reload(); gate.reload();
  });

  const requestDraw = (amount: number, milestoneSequence?: number) => run(async () => {
    if (!facility) return;
    await disbursement.request(ref, {
      facilityRef, amount, currency: facility.currency,
      purpose: "drawdown", narrative: "via UI", milestoneSequence,
    }, actor);
    notify("Drawdown requested");
    history.reload();
  });

  async function authorize(id: number) {
    try { await disbursement.authorize(id, { note: "ui authorise" }, actor);
      notify("Drawdown authorised");
      history.reload();
    } catch (e: any) { notify(e.message, true); }
  }

  const release = (id: number) => run(async () => {
    await disbursement.release(id, actor);
    notify("Drawdown released — limit utilisation booked");
    history.reload();
  });

  const reject = (id: number, reason: string) => run(async () => {
    await disbursement.reject(id, { reason }, actor);
    notify("Drawdown rejected");
    history.reload();
  });

  const amend = (id: number, current: any, amount: number, newPurpose: string) => run(async () => {
    await disbursement.amend(id, {
      amount,
      purpose: newPurpose && newPurpose !== current.purpose ? newPurpose : null,
    }, actor);
    notify("Drawdown amended");
    history.reload();
  });

  const cancel = (id: number, reason: string) => run(async () => {
    await disbursement.cancel(id, { reason }, actor);
    notify("Drawdown cancelled");
    history.reload();
  });

  const reverse = (id: number, reason: string) => run(async () => {
    await disbursement.reverse(id, { reason }, actor);
    notify("Drawdown reversed — limit ledger restored");
    history.reload(); rpmts.reload();
  });

  const recordRepayment = (amount: number, principal: number | null) => run(async () => {
    const body: any = { facilityRef, amount };
    if (principal != null && !Number.isNaN(principal) && principal !== amount) {
      body.principalComponent = principal;
      body.interestComponent = Math.round((amount - principal) * 100) / 100;
    }
    await rpmtApi.record(ref, body, actor);
    notify("Repayment recorded — a different actor must confirm");
    rpmts.reload();
  });

  async function confirmRepayment(id: number) {
    try { await rpmtApi.confirm(id, actor);
      notify("Repayment confirmed — principal released on the limit ledger");
      rpmts.reload();
    } catch (e: any) { notify(e.message, true); }
  }

  const rejectRepayment = (id: number, reason: string) => run(async () => {
    await rpmtApi.reject(id, { reason }, actor);
    notify("Repayment entry rejected");
    rpmts.reload();
  });

  // Conditions Precedent register columns. Row action buttons open an inline panel
  // (rendered below the table, since DataTable owns the tbody and cannot host
  // interleaved expansion rows).
  const cpCols: Col<any>[] = [
    { key: "code", header: "Code", render: (cp) => <span className="mono">{cp.code}</span>, value: (cp) => cp.code ?? "" },
    {
      key: "title", header: "Title", value: (cp) => cp.title ?? "",
      render: (cp) => (
        <>
          <div>{cp.title}</div>
          {cp.description && <div className="muted" style={{ fontSize: 12 }}>{cp.description}</div>}
        </>
      ),
    },
    { key: "facilityRef", header: "Facility", render: (cp) => <span className="mono">{cp.facilityRef}</span>, value: (cp) => cp.facilityRef ?? "" },
    {
      key: "mandatory", header: "Mandatory", value: (cp) => (cp.mandatory ? "MANDATORY" : "advisory"),
      render: (cp) => (cp.mandatory ? <Badge kind="warn">MANDATORY</Badge> : <Badge kind="">advisory</Badge>),
    },
    {
      key: "status", header: "Status", value: (cp) => cp.status ?? "",
      render: (cp) => <Badge kind={cp.status === "CLEARED" ? "ok" : cp.status === "WAIVED" ? "info" : cp.status === "REJECTED" ? "bad" : "warn"}>{cp.status}</Badge>,
    },
    {
      key: "source", header: "Source", value: (cp) => cp.source ?? "",
      render: (cp) => <Badge kind={cp.source === "TEMPLATE" ? "" : "info"}>{cp.source}</Badge>,
    },
    {
      key: "by", header: "Cleared / waived by", sortable: false,
      value: (cp) => [cp.clearedBy && `cleared by ${cp.clearedBy}`, cp.waivedBy && `waived by ${cp.waivedBy}`, cp.waivedReason].filter(Boolean).join(" "),
      render: (cp) => (
        <span className="muted" style={{ fontSize: 12 }}>
          {cp.clearedBy && <>cleared by {cp.clearedBy}<br /></>}
          {cp.waivedBy && <>waived by {cp.waivedBy}<br /></>}
          {cp.waivedReason && <span className="muted">"{cp.waivedReason}"</span>}
        </span>
      ),
    },
    {
      key: "_actions", header: "", sortable: false, filterable: false, csv: false,
      render: (cp) => cp.status === "OPEN" ? (
        <div style={{ display: "flex", gap: 4 }}>
          <Button kind="subtle" onClick={() => togglePanel(`cp-clear:${cp.id}`)}>Clear</Button>
          <Button kind="ghost" onClick={() => togglePanel(`cp-waive:${cp.id}`)}>Waive</Button>
        </div>
      ) : null,
    },
  ];
  const cpForPanel = (prefix: string) =>
    panel?.startsWith(prefix) ? (register.data ?? []).find((x: any) => String(x.id) === panel.slice(prefix.length)) : null;

  // Drawdown tranche columns. Same panel-below-table pattern as the CP register.
  const trancheCols: Col<any>[] = [
    { key: "drawdownNo", header: "#", render: (d) => `#${d.drawdownNo}`, value: (d) => d.drawdownNo ?? 0 },
    { key: "amount", header: "Amount", render: (d) => <span className="mono">{fmt.money(d.amount, d.currency)}</span>, value: (d) => d.amount ?? 0 },
    {
      key: "requested", header: "Requested (orig.)", value: (d) => d.requestedAmount ?? 0,
      render: (d) => d.fxRate ? (
        <div style={{ display: "flex", flexDirection: "column", gap: 2 }}>
          <span className="mono">{fmt.money(d.requestedAmount, d.requestedCurrency)}</span>
          <Badge kind="info">FX @ {Number(d.fxRate).toFixed(4)}</Badge>
        </div>
      ) : <span className="muted">—</span>,
    },
    {
      key: "status", header: "Status", value: (d) => d.status ?? "",
      render: (d) => <Badge kind={d.status === "RELEASED" ? "ok" : d.status === "AUTHORIZED" ? "info" : d.status === "REJECTED" || d.status === "CANCELLED" ? "bad" : "warn"}>{d.status}</Badge>,
    },
    {
      key: "actors", header: "Requested · authorised · released", sortable: false,
      value: (d) => [d.requestedBy, d.authorizedBy, d.releasedBy ?? d.cancelledBy ?? d.rejectedBy].map((x: any) => x ?? "—").join(" · "),
      render: (d) => (
        <div className="muted" style={{ fontSize: 12 }}>
          <div>{d.requestedBy ?? "—"}</div>
          <div>{d.authorizedBy ?? "—"}</div>
          <div>{d.releasedBy ?? d.cancelledBy ?? d.rejectedBy ?? "—"}</div>
        </div>
      ),
    },
    { key: "utilisationRef", header: "Utilisation ref", render: (d) => <span className="mono" style={{ fontSize: 12 }}>{d.utilisationRef ?? "—"}</span>, value: (d) => d.utilisationRef ?? "" },
    {
      key: "_actions", header: "", sortable: false, filterable: false, csv: false,
      render: (d) => (
        <div style={{ display: "flex", gap: 4, flexWrap: "wrap" }}>
          {d.status === "DRAFT" && (
            <>
              <Button kind="primary" onClick={() => authorize(d.id)}>Authorise</Button>
              <Button kind="subtle" onClick={() => togglePanel(`dd-amend:${d.id}`)}>Amend</Button>
              <Button kind="ghost" onClick={() => togglePanel(`dd-cancel:${d.id}`)}>Cancel</Button>
              <Button kind="ghost" onClick={() => togglePanel(`dd-reject:${d.id}`)}>Reject</Button>
            </>
          )}
          {d.status === "AUTHORIZED" && (
            <>
              <Button kind="primary" onClick={() => togglePanel(`dd-release:${d.id}`)}>Release</Button>
              <Button kind="ghost" onClick={() => togglePanel(`dd-cancel:${d.id}`)}>Cancel</Button>
            </>
          )}
          {d.status === "RELEASED" && (
            <Button kind="ghost" onClick={() => togglePanel(`dd-reverse:${d.id}`)}>Reverse</Button>
          )}
        </div>
      ),
    },
  ];
  const drawForPanel = (prefix: string) =>
    panel?.startsWith(prefix) ? (history.data ?? []).find((x: any) => String(x.id) === panel.slice(prefix.length)) : null;

  return (
    <div className="grid">
      <Card title="Pre-disbursement workflow"
        sub="Conditions Precedent are the explicit list of things that must be met before the FIRST drawdown of a facility. Server-side gate · maker-checker SoD on every transition."
        right={<HumanBadge label="DRAWDOWN GATE · HUMAN-AUTHORISED" />}>
        <div className="grid cols-2" style={{ alignItems: "end" }}>
          <Field label="Application">
            <select value={ref} onChange={(e) => { setRef(e.target.value); setFacilityRef(""); }}>
              <option value="">— select —</option>
              {(apps.data || []).map((a: any) => (
                <option key={a.reference} value={a.reference}>
                  {a.counterpartyName} · {a.reference}
                </option>
              ))}
            </select>
          </Field>
          <Field label="Facility">
            <select value={facilityRef} onChange={(e) => setFacilityRef(e.target.value)}>
              <option value="">— select —</option>
              {facilities.map((f: any) => (
                <option key={f.reference} value={f.reference}>
                  {f.facilityType} · {fmt.money(f.amount, f.currency)} · {f.reference}
                </option>
              ))}
            </select>
          </Field>
        </div>
        {ref && (
          <div style={{ display: "flex", gap: 12, marginTop: 12 }}>
            <Button onClick={seed} kind="primary">Seed CPs from master</Button>
          </div>
        )}
      </Card>

      {ref && facilityRef && (
        <Card title="Authorisation gate · CP-enforced"
          sub="The pre-disbursement check on this facility — every mandatory CP must be CLEARED or WAIVED before a drawdown can be authorised."
          right={
            gate.data?.canDrawdown
              ? <DeterministicBadge label="GATE OPEN — CAN DRAWDOWN" />
              : <Badge kind="bad">GATE CLOSED · {gate.data?.mandatoryOpen ?? "—"} BLOCKER(S)</Badge>
          }>
          {gate.data && (
            <div className="grid cols-3" style={{ marginBottom: 8 }}>
              <div>
                <div className="label">Facility</div>
                <div className="mono">{facility?.reference} · {facility?.facilityType}</div>
              </div>
              <div>
                <div className="label">Sanctioned</div>
                <div className="mono">{facility ? fmt.money(facility.amount, facility.currency) : "—"}</div>
              </div>
              <div>
                <div className="label">Mandatory CPs</div>
                <div>
                  <b>{(gate.data.mandatoryTotal ?? 0) - (gate.data.mandatoryOpen ?? 0)}</b> of <b>{gate.data.mandatoryTotal ?? 0}</b> cleared
                </div>
              </div>
            </div>
          )}
          {gate.data && !gate.data.canDrawdown && (gate.data.blockers ?? []).length > 0 && (
            <div style={{ borderLeft: "3px solid var(--bad)", padding: "8px 14px", background: "rgba(255,90,90,0.06)" }}>
              <div style={{ fontWeight: 600, marginBottom: 4 }}>Open blockers</div>
              <ul style={{ margin: 0 }}>
                {gate.data.blockers.map((b: any) => (
                  <li key={b.code}><span className="mono">{b.code}</span> — {b.title}</li>
                ))}
              </ul>
            </div>
          )}
        </Card>
      )}

      {ref && (
        <Card title="Conditions Precedent register"
          sub="Seeded from CP_MASTER (by facility type · jurisdiction-overridable). Clear / waive flows with named-human SoD.">
          <DataTable
            id="disbursement-cp-register"
            columns={cpCols}
            rows={register.data ?? []}
            rowKey={(cp) => String(cp.id)}
            empty={
              <EmptyState glyph="○" title="No CPs yet"
                sub="Click 'Seed CPs from master' to populate from CP_MASTER, or add a custom item." />
            }
          />
          {(() => {
            const cp = cpForPanel("cp-clear:");
            return cp ? (
              <TextForm title={`Clear ${cp.code} — ${cp.title}`}
                label="Evidence reference" placeholder="e.g. DOC-12345"
                hint="Optional — reference to the document that satisfies this CP"
                submitLabel="Clear CP" busy={panelBusy}
                onSubmit={(v) => clearCp(cp.id, v)} onCancel={closePanel} />
            ) : null;
          })()}
          {(() => {
            const cp = cpForPanel("cp-waive:");
            return cp ? (
              <TextForm title={`Waive ${cp.code} — a waiver reason is mandatory`}
                label="Waiver reason" required multiline
                submitLabel="Waive CP" busy={panelBusy}
                onSubmit={(v) => waiveCp(cp.id, v)} onCancel={closePanel} />
            ) : null;
          })()}
        </Card>
      )}

      {ref && facilityRef && isPf && (
        <Card title="Construction milestone schedule"
          sub="Planned tranches with their LIE-certified gate. The PF gate blocks a drawdown until its milestone is certified; reserve shortfalls block too.">
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>#</th>
                  <th>Milestone</th>
                  <th className="num">Planned</th>
                  <th>Planned date</th>
                  <th>Progress</th>
                  <th>LIE cert</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {(milestones.data ?? []).map((m: any) => {
                  const pct = m.status === "DRAWN" ? 100
                            : m.status === "LIE_CERTIFIED" ? 60
                            : 10;
                  const tone = m.status === "DRAWN" ? "var(--ok)"
                            : m.status === "LIE_CERTIFIED" ? "var(--ai)"
                            : "var(--warn, #c79a37)";
                  return (
                    <Fragment key={m.id}>
                      <tr>
                        <td>#{m.sequence}</td>
                        <td>{m.name}</td>
                        <td className="num">{fmt.money(m.plannedAmount, m.currency)}</td>
                        <td className="mono" style={{ fontSize: 12 }}>{fmt.date(m.plannedDate)}</td>
                        <td style={{ minWidth: 220 }}>
                          <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                            <div style={{ flex: 1, height: 6, borderRadius: 3,
                                          background: "rgba(0,0,0,0.08)", overflow: "hidden" }}>
                              <div style={{ width: `${pct}%`, height: "100%", background: tone }} />
                            </div>
                            <Badge kind={m.status === "DRAWN" ? "ok"
                                        : m.status === "LIE_CERTIFIED" ? "info" : "warn"}>
                              {m.status}
                            </Badge>
                          </div>
                        </td>
                        <td className="muted" style={{ fontSize: 12 }}>
                          {m.certificationRef ? `${m.certificationRef} · ${m.lieCertifiedBy}` : "—"}
                        </td>
                        <td>
                          {m.status === "PLANNED" && (
                            <Button kind="subtle" onClick={() => togglePanel(`ms-certify:${m.id}`)}>LIE certify</Button>
                          )}
                        </td>
                      </tr>
                      {panel === `ms-certify:${m.id}` && (
                        <PanelRow colSpan={7}>
                          <TextForm title={`LIE-certify milestone #${m.sequence} · ${m.name}`}
                            label="LIE certification reference" placeholder="e.g. LIE-CERT-003" required
                            hint="Reference issued by the Lender's Independent Engineer"
                            submitLabel="Certify" busy={panelBusy}
                            onSubmit={(v) => certifyMilestone(m.id, v)} onCancel={closePanel} />
                        </PanelRow>
                      )}
                    </Fragment>
                  );
                })}
              </tbody>
            </table>
          </div>
        </Card>
      )}

      {ref && isPf && (reserves.data ?? []).length > 0 && (
        <Card title="Reserve accounts · DSRA / TRA"
          sub="Required reserves must be funded before drawdowns proceed — a shortfall blocks the PF gate.">
          <div className="table-scroll">
            <table>
              <thead>
                <tr><th>Account</th><th className="num">Required</th><th className="num">Balance</th><th>Status</th><th /></tr>
              </thead>
              <tbody>
                {(reserves.data ?? []).map((r: any) => (
                  <Fragment key={r.id}>
                    <tr>
                      <td className="mono">{r.accountType}</td>
                      <td className="num">{fmt.money(r.requiredAmount, r.currency)}</td>
                      <td className="num">{fmt.money(r.currentBalance, r.currency)}</td>
                      <td>
                        <Badge kind={r.status === "FUNDED" ? "ok" : "bad"}>{r.status}</Badge>
                      </td>
                      <td><Button kind="subtle" onClick={() => togglePanel(`res-fund:${r.id}`)}>Fund</Button></td>
                    </tr>
                    {panel === `res-fund:${r.id}` && (
                      <PanelRow colSpan={5}>
                        <AmountForm title={`Fund ${r.accountType} reserve`}
                          label="Funding amount" currency={r.currency ?? "INR"}
                          submitLabel="Fund reserve" busy={panelBusy}
                          onSubmit={(amt) => fundReserve(r.id, amt)} onCancel={closePanel} />
                      </PanelRow>
                    )}
                  </Fragment>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}

      {ref && facilityRef && isPf && (history.data ?? []).some((d: any) => d.status === "RELEASED") && (
        <WaterfallCard refValue={ref} facilityRef={facilityRef} />
      )}

      {ref && facilityRef && (
        <Card title="Drawdown tranches"
          sub="One row per draw. Multi-tranche PF, partial WC, and revolver use all map to repeated rows on the same facility. Each transition is SoD-gated."
          right={
            <Button kind="primary" onClick={() => togglePanel("draw")}
              disabled={!facility}>
              Request drawdown
            </Button>
          }>
          {panel === "draw" && facility && (
            <RequestDrawForm currency={facility.currency} isPf={isPf}
              milestones={milestones.data ?? []} busy={panelBusy}
              onSubmit={requestDraw} onCancel={closePanel} />
          )}
          <DataTable
            id="disbursement-tranches"
            columns={trancheCols}
            rows={history.data ?? []}
            rowKey={(d) => String(d.id)}
            empty={
              <EmptyState glyph="◯" title="No drawdowns yet"
                sub="Request the first tranche once the gate is open." />
            }
          />
          {(() => {
            const d = drawForPanel("dd-amend:");
            return d ? (
              <AmendForm current={d} busy={panelBusy}
                onSubmit={(amt, purpose) => amend(d.id, d, amt, purpose)}
                onCancel={closePanel} />
            ) : null;
          })()}
          {(() => {
            const d = drawForPanel("dd-cancel:");
            return d ? (
              <TextForm title={`Cancel drawdown #${d.drawdownNo}`}
                label="Cancellation reason" required
                submitLabel="Cancel drawdown" busy={panelBusy}
                onSubmit={(v) => cancel(d.id, v)} onCancel={closePanel} />
            ) : null;
          })()}
          {(() => {
            const d = drawForPanel("dd-reject:");
            return d ? (
              <TextForm title={`Reject drawdown #${d.drawdownNo}`}
                label="Rejection reason" required
                submitLabel="Reject drawdown" busy={panelBusy}
                onSubmit={(v) => reject(d.id, v)} onCancel={closePanel} />
            ) : null;
          })()}
          {(() => {
            const d = drawForPanel("dd-release:");
            return d ? (
              <InlinePanel danger busy={panelBusy}
                title={`Release drawdown #${d.drawdownNo} · ${fmt.money(d.amount, d.currency)} — books limit utilisation on the ledger; undo requires a reversal entry`}
                submitLabel="Confirm release"
                onSubmit={() => release(d.id)} onCancel={closePanel} />
            ) : null;
          })()}
          {(() => {
            const d = drawForPanel("dd-reverse:");
            return d ? (
              <TextForm danger multiline required
                title={`Reverse drawdown #${d.drawdownNo} — this undoes the limit booking`}
                label="Reversal reason"
                submitLabel="Reverse drawdown" busy={panelBusy}
                onSubmit={(v) => reverse(d.id, v)} onCancel={closePanel} />
            ) : null;
          })()}
        </Card>
      )}

      {ref && facilityRef && (history.data ?? []).some((d: any) => d.status === "RELEASED") && (
        <Card title="Repayments — the inbound leg"
          sub="Record (maker) → Confirm (checker ≠ recorder) books a limit RELEASE for the principal. Core-banking events arrive via the idempotent connector and confirm as SYSTEM."
          right={<DeterministicBadge label="SCHEDULE · DETERMINISTIC" />}>
          <div className="grid cols-2">
            <div>
              <div style={{ display: "flex", gap: 8, alignItems: "center", marginBottom: 8 }}>
                <Field label="Schedule method">
                  <select value={scheduleMethod} onChange={(e) => setScheduleMethod(e.target.value)}>
                    <option value="EMI">EMI (annuity)</option>
                    <option value="EQUAL_PRINCIPAL">Equal principal</option>
                    <option value="BULLET">Bullet</option>
                  </select>
                </Field>
                <Button kind="primary" onClick={() => togglePanel("record")}>Record repayment</Button>
              </div>
              {panel === "record" && (
                <RecordRepaymentForm currency={facility?.currency ?? "INR"} busy={panelBusy}
                  onSubmit={recordRepayment} onCancel={closePanel} />
              )}
              {schedule.data ? (
                <>
                  <div style={{ fontSize: 12, opacity: 0.75, marginBottom: 6 }}>
                    Outstanding <b>{fmt.money(schedule.data.principal)}</b> · rate{" "}
                    <b>{(schedule.data.annualRate * 100).toFixed(2)}%</b>{" "}
                    <span className="mono">({schedule.data.rateSource})</span> ·{" "}
                    {schedule.data.periods} periods · total interest {fmt.money(schedule.data.totalInterest)}
                  </div>
                  <div className="table-scroll">
                    <table>
                      <thead>
                        <tr><th>#</th><th>Due</th><th>Payment</th><th>Principal</th><th>Interest</th><th>Balance</th></tr>
                      </thead>
                      <tbody>
                        {visibleScheduleRows.map((r: any) => (
                          <tr key={r.periodNo}>
                            <td>{r.periodNo}</td>
                            <td className="mono">{fmt.date(r.dueDate)}</td>
                            <td>{fmt.money(r.payment)}</td>
                            <td>{fmt.money(r.principal)}</td>
                            <td>{fmt.money(r.interest)}</td>
                            <td>{fmt.money(r.closingBalance)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                  {scheduleRows.length > 6 && (
                    <div className="table-more">
                      <span>showing {visibleScheduleRows.length} of {scheduleRows.length} periods</span>
                      <button type="button" onClick={() => setShowAllSchedule((s) => !s)}>
                        {showAllSchedule ? "show first 6" : "show all"}
                      </button>
                    </div>
                  )}
                </>
              ) : (
                <EmptyState glyph="▦" title="No schedule yet"
                  sub="Release a drawdown to generate the deterministic repayment plan." />
              )}
            </div>
            <div>
              {(rpmts.data ?? []).length === 0 ? (
                <EmptyState glyph="↩" title="No repayments yet"
                  sub="Record a repayment, or let the core-banking connector feed one in." />
              ) : (
                <div className="table-scroll">
                  <table>
                    <thead>
                      <tr><th>#</th><th>Amount</th><th>Principal</th><th>Source</th><th>Status</th><th>By</th><th /></tr>
                    </thead>
                    <tbody>
                      {(rpmts.data ?? []).map((p: any) => (
                        <Fragment key={p.id}>
                          <tr>
                            <td>{p.id}</td>
                            <td>{fmt.money(p.amount)}</td>
                            <td>{fmt.money(p.principalComponent)}</td>
                            <td><Badge kind={p.source === "CORE_BANKING" ? "info" : "ok"}>{p.source}</Badge></td>
                            <td>
                              <Badge kind={p.status === "CONFIRMED" ? "ok"
                                          : p.status === "REJECTED" ? "bad" : "warn"}>
                                {p.status}
                              </Badge>
                            </td>
                            <td className="mono" style={{ fontSize: 12 }}>
                              {p.confirmedBy ?? p.recordedBy}
                            </td>
                            <td>
                              {p.status === "RECORDED" && (
                                <div style={{ display: "flex", gap: 4 }}>
                                  <Button kind="subtle" onClick={() => confirmRepayment(p.id)}>Confirm</Button>
                                  <Button kind="ghost" onClick={() => togglePanel(`rp-reject:${p.id}`)}>Reject</Button>
                                </div>
                              )}
                            </td>
                          </tr>
                          {panel === `rp-reject:${p.id}` && (
                            <PanelRow colSpan={7}>
                              <TextForm title={`Reject repayment entry #${p.id}`}
                                label="Rejection reason" required
                                submitLabel="Reject entry" busy={panelBusy}
                                onSubmit={(v) => rejectRepayment(p.id, v)} onCancel={closePanel} />
                            </PanelRow>
                          )}
                        </Fragment>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          </div>
        </Card>
      )}
    </div>
  );
}

/**
 * Forward DSCR + payment waterfall projection for a PF facility. Given a base
 * annual CFADS, projects per-period O&M → debt service → DSRA top-up → MMRA
 * top-up → distributions, and reports min/avg DSCR, LLCR, cushion to covenant,
 * and per-period breach flags. Pure read — computed on every submit.
 */
function WaterfallCard({ refValue, facilityRef }: { refValue: string; facilityRef: string }) {
  const { actor, notify } = useApp();
  const [cfads, setCfads] = useState("400000000");
  const [omRatio, setOmRatio] = useState("0.30");
  const [covenant, setCovenant] = useState("1.20");
  const [ramp, setRamp] = useState("1.0");
  const [proj, setProj] = useState<any>(null);
  const [busy, setBusy] = useState(false);
  const [showAllRows, setShowAllRows] = useState(false);

  async function run() {
    const cf = parseFloat(cfads.replace(/[, ]/g, ""));
    const om = parseFloat(omRatio);
    const cov = parseFloat(covenant);
    const rmp = parseFloat(ramp);
    if (!cf || cf <= 0) { notify("Provide a positive baseAnnualCfads", true); return; }
    setBusy(true);
    try {
      const w = await pfApi.waterfall(refValue, {
        facilityRef, baseAnnualCfads: cf, omRatio: om, minDscrCovenant: cov,
        cfadsRampFactor: rmp,
      }, actor);
      setProj(w);
    } catch (e: any) { notify(e.message, true); } finally { setBusy(false); }
  }

  const s = proj?.summary;
  const cushionGood = s && s.cushionToCovenantPct > 0;
  const wfRows: any[] = proj?.rows ?? [];
  const visibleWfRows = showAllRows ? wfRows : wfRows.slice(0, 12);
  return (
    <Card title="Payment waterfall · forward DSCR"
      sub="O&M → senior debt → DSRA top-up → MMRA → distributions. Computed view; never persisted. Re-run any time CFADS, reserves or the schedule changes."
      right={<DeterministicBadge label="PROJECTION · DETERMINISTIC" />}>
      <div className="grid cols-4" style={{ alignItems: "end" }}>
        <Field label="Base annual CFADS">
          <input value={cfads} onChange={(e) => setCfads(e.target.value)} />
        </Field>
        <Field label="O&M ratio (of CFADS)">
          <input value={omRatio} onChange={(e) => setOmRatio(e.target.value)} />
        </Field>
        <Field label="Min DSCR covenant">
          <input value={covenant} onChange={(e) => setCovenant(e.target.value)} />
        </Field>
        <Field label="CFADS ramp (1 = flat)">
          <input value={ramp} onChange={(e) => setRamp(e.target.value)} />
        </Field>
      </div>
      <div className="btnrow" style={{ marginTop: 8 }}>
        <Button kind="primary" busy={busy} onClick={run}>Project waterfall</Button>
      </div>
      {proj && s && (
        <>
          <div style={{ display: "grid", gridTemplateColumns: "repeat(5,1fr)", gap: 12, marginTop: 12 }}>
            <Stat label="Min DSCR" value={s.minDscr.toFixed(3)}
              delta={cushionGood ? `+${(s.cushionToCovenantPct * 100).toFixed(1)}% cushion`
                                  : `${(s.cushionToCovenantPct * 100).toFixed(1)}% vs covenant`}
              tone={cushionGood ? "ok" : "bad"} />
            <Stat label="Avg DSCR" value={s.avgDscr.toFixed(3)} />
            <Stat label="Rolling 12 min" value={s.rollingForward12MinDscr.toFixed(3)} />
            <Stat label="LLCR" value={s.llcr.toFixed(3)} />
            <Stat label="Breaches"
              value={`${s.totalBreachPeriods}${s.firstBreachPeriod > 0 ? " (1st @ " + s.firstBreachPeriod + ")" : ""}`}
              tone={s.totalBreachPeriods === 0 ? "ok" : "bad"} />
          </div>
          <div style={{ fontSize: 12, opacity: 0.75, margin: "8px 0" }}>
            {proj.frequency} · {proj.periods} periods · covenant {proj.minDscrCovenant.toFixed(2)}
          </div>
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>#</th><th>Due</th><th>CFADS</th><th>O&amp;M</th><th>Debt svc</th>
                  <th>DSRA</th><th>MMRA</th><th>Distrib.</th><th>DSCR</th><th />
                </tr>
              </thead>
              <tbody>
                {visibleWfRows.map((r: any) => (
                  <tr key={r.periodNo} style={r.covenantBreach ? { background: "var(--bad-soft, #fee)" } : undefined}>
                    <td>{r.periodNo}</td>
                    <td className="mono" style={{ fontSize: 12 }}>{fmt.date(r.periodDate)}</td>
                    <td>{fmt.money(r.cfads)}</td>
                    <td>{fmt.money(r.om)}</td>
                    <td>{fmt.money(r.debtService)}</td>
                    <td>{fmt.money(r.dsraTopUp)}</td>
                    <td>{fmt.money(r.mmraTopUp)}</td>
                    <td>{fmt.money(r.distributions)}</td>
                    <td>{r.dscr.toFixed(3)}</td>
                    <td>
                      {r.covenantBreach && <Badge kind="bad">BREACH</Badge>}
                      {r.cashBreach && <Badge kind="bad">CASH</Badge>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {wfRows.length > 12 && (
            <div className="table-more">
              <span>showing {visibleWfRows.length} of {wfRows.length} periods</span>
              <button type="button" onClick={() => setShowAllRows((v) => !v)}>
                {showAllRows ? "show first 12" : "show all"}
              </button>
            </div>
          )}
        </>
      )}
    </Card>
  );
}
