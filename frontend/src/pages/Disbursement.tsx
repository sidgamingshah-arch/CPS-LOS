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
 */
import { useEffect, useMemo, useState } from "react";
import { cps as cpApi, disbursement, fmt, origination, pf as pfApi, repayments as rpmtApi } from "../api";
import { useApp } from "../app-context";
import {
  Badge,
  Button,
  Card,
  DeterministicBadge,
  EmptyState,
  Field,
  HumanBadge,
  Stat,
  useAsync,
} from "../ui";

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

  async function certifyMilestone(id: number) {
    const certRef = prompt("LIE certification reference (e.g. LIE-CERT-003)") || "";
    if (!certRef) return;
    try { await pfApi.certify(id, { certificationRef: certRef }, actor);
      notify("Milestone LIE-certified"); milestones.reload();
    } catch (e: any) { notify(e.message, true); }
  }

  async function fundReserve(id: number) {
    const amtStr = prompt("Funding amount") || "";
    const amt = parseFloat(amtStr.replace(/[, ]/g, ""));
    if (!amt || amt <= 0) return;
    try { await pfApi.fund(id, { amount: amt }, actor);
      notify("Reserve funded"); reserves.reload();
    } catch (e: any) { notify(e.message, true); }
  }

  async function seed() {
    if (!ref) return;
    try {
      const seeded = await cpApi.seed(ref, actor);
      notify(`Seeded ${seeded.length} CP(s) from CP_MASTER`);
      register.reload();
    } catch (e: any) { notify(e.message, true); }
  }

  async function clearCp(id: number) {
    const evidence = prompt("Evidence reference (e.g. DOC-12345)") || "";
    try { await cpApi.clear(id, { evidenceRef: evidence }, actor);
      notify("CP cleared");
      register.reload(); gate.reload();
    } catch (e: any) { notify(e.message, true); }
  }

  async function waiveCp(id: number) {
    const reason = prompt("Waiver reason (required)") || "";
    if (!reason) return;
    try { await cpApi.waive(id, { reason }, actor);
      notify("CP waived");
      register.reload(); gate.reload();
    } catch (e: any) { notify(e.message, true); }
  }

  async function requestDraw() {
    if (!facility) return;
    const amtStr = prompt(`Drawdown amount (${facility.currency})`) || "";
    const amt = parseFloat(amtStr.replace(/[, ]/g, ""));
    if (!amt || amt <= 0) return;
    // PF facilities draw against a milestone — capture the sequence.
    let milestoneSequence: number | undefined;
    if (isPf) {
      const seqStr = prompt("Milestone sequence this tranche draws against (e.g. 1)") || "";
      const seq = parseInt(seqStr, 10);
      if (!Number.isNaN(seq)) milestoneSequence = seq;
    }
    try {
      await disbursement.request(ref, {
        facilityRef, amount: amt, currency: facility.currency,
        purpose: "drawdown", narrative: "via UI", milestoneSequence,
      }, actor);
      notify("Drawdown requested");
      history.reload();
    } catch (e: any) { notify(e.message, true); }
  }

  async function authorize(id: number) {
    try { await disbursement.authorize(id, { note: "ui authorise" }, actor);
      notify("Drawdown authorised");
      history.reload();
    } catch (e: any) { notify(e.message, true); }
  }

  async function release(id: number) {
    try { await disbursement.release(id, actor);
      notify("Drawdown released — limit utilisation booked");
      history.reload();
    } catch (e: any) { notify(e.message, true); }
  }

  async function reject(id: number) {
    const reason = prompt("Rejection reason") || "";
    if (!reason) return;
    try { await disbursement.reject(id, { reason }, actor);
      notify("Drawdown rejected");
      history.reload();
    } catch (e: any) { notify(e.message, true); }
  }

  async function amend(id: number, current: any) {
    const amtStr = prompt(`New amount (current ${current.amount} ${current.currency})`,
      String(current.amount));
    if (!amtStr) return;
    const amt = parseFloat(amtStr.replace(/[, ]/g, ""));
    if (!amt || amt <= 0) return;
    const newPurpose = prompt("Purpose (blank = keep)", current.purpose ?? "");
    try {
      await disbursement.amend(id, {
        amount: amt,
        purpose: newPurpose && newPurpose !== current.purpose ? newPurpose : null,
      }, actor);
      notify("Drawdown amended");
      history.reload();
    } catch (e: any) { notify(e.message, true); }
  }

  async function cancel(id: number) {
    const reason = prompt("Cancellation reason") || "";
    if (!reason) return;
    try { await disbursement.cancel(id, { reason }, actor);
      notify("Drawdown cancelled");
      history.reload();
    } catch (e: any) { notify(e.message, true); }
  }

  async function reverse(id: number) {
    const reason = prompt("Reversal reason (mandatory — this undoes the limit booking)") || "";
    if (!reason) return;
    try { await disbursement.reverse(id, { reason }, actor);
      notify("Drawdown reversed — limit ledger restored");
      history.reload(); rpmts.reload();
    } catch (e: any) { notify(e.message, true); }
  }

  async function recordRepayment() {
    const amtStr = prompt(`Repayment amount (${facility?.currency ?? ""})`) || "";
    const amt = parseFloat(amtStr.replace(/[, ]/g, ""));
    if (!amt || amt <= 0) return;
    const prinStr = prompt("Principal component (blank = all principal)", String(amt)) || "";
    const prin = parseFloat(prinStr.replace(/[, ]/g, ""));
    const body: any = { facilityRef, amount: amt };
    if (!Number.isNaN(prin) && prin !== amt) {
      body.principalComponent = prin;
      body.interestComponent = Math.round((amt - prin) * 100) / 100;
    }
    try { await rpmtApi.record(ref, body, actor);
      notify("Repayment recorded — a different actor must confirm");
      rpmts.reload();
    } catch (e: any) { notify(e.message, true); }
  }

  async function confirmRepayment(id: number) {
    try { await rpmtApi.confirm(id, actor);
      notify("Repayment confirmed — principal released on the limit ledger");
      rpmts.reload();
    } catch (e: any) { notify(e.message, true); }
  }

  async function rejectRepayment(id: number) {
    const reason = prompt("Rejection reason") || "";
    if (!reason) return;
    try { await rpmtApi.reject(id, { reason }, actor);
      notify("Repayment entry rejected");
      rpmts.reload();
    } catch (e: any) { notify(e.message, true); }
  }

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
          {(register.data ?? []).length === 0 ? (
            <EmptyState glyph="○" title="No CPs yet"
              sub="Click 'Seed CPs from master' to populate from CP_MASTER, or add a custom item." />
          ) : (
            <table>
              <thead>
                <tr>
                  <th>Code</th>
                  <th>Title</th>
                  <th>Facility</th>
                  <th>Mandatory</th>
                  <th>Status</th>
                  <th>Source</th>
                  <th>Cleared / waived by</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {(register.data ?? []).map((cp: any) => (
                  <tr key={cp.id}>
                    <td className="mono">{cp.code}</td>
                    <td>
                      <div>{cp.title}</div>
                      {cp.description && (
                        <div className="muted" style={{ fontSize: 12 }}>{cp.description}</div>
                      )}
                    </td>
                    <td className="mono">{cp.facilityRef}</td>
                    <td>
                      {cp.mandatory ? <Badge kind="warn">MANDATORY</Badge>
                                    : <Badge kind="">advisory</Badge>}
                    </td>
                    <td>
                      <Badge kind={cp.status === "CLEARED" ? "ok"
                                  : cp.status === "WAIVED" ? "info"
                                  : cp.status === "REJECTED" ? "bad" : "warn"}>
                        {cp.status}
                      </Badge>
                    </td>
                    <td>
                      <Badge kind={cp.source === "TEMPLATE" ? "" : "info"}>
                        {cp.source}
                      </Badge>
                    </td>
                    <td className="muted" style={{ fontSize: 12 }}>
                      {cp.clearedBy && <>cleared by {cp.clearedBy}<br /></>}
                      {cp.waivedBy && <>waived by {cp.waivedBy}<br /></>}
                      {cp.waivedReason && <span className="muted">"{cp.waivedReason}"</span>}
                    </td>
                    <td>
                      {cp.status === "OPEN" && (
                        <div style={{ display: "flex", gap: 4 }}>
                          <Button kind="subtle" onClick={() => clearCp(cp.id)}>Clear</Button>
                          <Button kind="ghost" onClick={() => waiveCp(cp.id)}>Waive</Button>
                        </div>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Card>
      )}

      {ref && facilityRef && isPf && (
        <Card title="Construction milestone schedule"
          sub="Planned tranches with their LIE-certified gate. The PF gate blocks a drawdown until its milestone is certified; reserve shortfalls block too.">
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
                  <tr key={m.id}>
                    <td>#{m.sequence}</td>
                    <td>{m.name}</td>
                    <td className="num">{fmt.money(m.plannedAmount, m.currency)}</td>
                    <td className="mono" style={{ fontSize: 12 }}>{m.plannedDate ?? "—"}</td>
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
                        <Button kind="subtle" onClick={() => certifyMilestone(m.id)}>LIE certify</Button>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </Card>
      )}

      {ref && isPf && (reserves.data ?? []).length > 0 && (
        <Card title="Reserve accounts · DSRA / TRA"
          sub="Required reserves must be funded before drawdowns proceed — a shortfall blocks the PF gate.">
          <table>
            <thead>
              <tr><th>Account</th><th className="num">Required</th><th className="num">Balance</th><th>Status</th><th /></tr>
            </thead>
            <tbody>
              {(reserves.data ?? []).map((r: any) => (
                <tr key={r.id}>
                  <td className="mono">{r.accountType}</td>
                  <td className="num">{fmt.money(r.requiredAmount, r.currency)}</td>
                  <td className="num">{fmt.money(r.currentBalance, r.currency)}</td>
                  <td>
                    <Badge kind={r.status === "FUNDED" ? "ok" : "bad"}>{r.status}</Badge>
                  </td>
                  <td><Button kind="subtle" onClick={() => fundReserve(r.id)}>Fund</Button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}

      {ref && facilityRef && isPf && (history.data ?? []).some((d: any) => d.status === "RELEASED") && (
        <WaterfallCard refValue={ref} facilityRef={facilityRef} />
      )}

      {ref && facilityRef && (
        <Card title="Drawdown tranches"
          sub="One row per draw. Multi-tranche PF, partial WC, and revolver use all map to repeated rows on the same facility. Each transition is SoD-gated."
          right={
            <Button kind="primary" onClick={requestDraw}
              disabled={!facility}>
              Request drawdown
            </Button>
          }>
          {(history.data ?? []).length === 0 ? (
            <EmptyState glyph="◯" title="No drawdowns yet"
              sub="Request the first tranche once the gate is open." />
          ) : (
            <table>
              <thead>
                <tr>
                  <th>#</th>
                  <th>Amount</th>
                  <th>Requested (orig.)</th>
                  <th>Status</th>
                  <th>Requested · authorised · released</th>
                  <th>Utilisation ref</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {(history.data ?? []).map((d: any) => (
                  <tr key={d.id}>
                    <td>#{d.drawdownNo}</td>
                    <td className="mono">{fmt.money(d.amount, d.currency)}</td>
                    <td>
                      {d.fxRate ? (
                        <div style={{ display: "flex", flexDirection: "column", gap: 2 }}>
                          <span className="mono">{fmt.money(d.requestedAmount, d.requestedCurrency)}</span>
                          <Badge kind="info">FX @ {Number(d.fxRate).toFixed(4)}</Badge>
                        </div>
                      ) : (
                        <span className="muted">—</span>
                      )}
                    </td>
                    <td>
                      <Badge kind={d.status === "RELEASED" ? "ok"
                                  : d.status === "AUTHORIZED" ? "info"
                                  : d.status === "REJECTED" || d.status === "CANCELLED" ? "bad" : "warn"}>
                        {d.status}
                      </Badge>
                    </td>
                    <td className="muted" style={{ fontSize: 12 }}>
                      <div>{d.requestedBy ?? "—"}</div>
                      <div>{d.authorizedBy ?? "—"}</div>
                      <div>{d.releasedBy ?? d.cancelledBy ?? d.rejectedBy ?? "—"}</div>
                    </td>
                    <td className="mono" style={{ fontSize: 12 }}>{d.utilisationRef ?? "—"}</td>
                    <td>
                      <div style={{ display: "flex", gap: 4, flexWrap: "wrap" }}>
                        {d.status === "DRAFT" && (
                          <>
                            <Button kind="primary" onClick={() => authorize(d.id)}>Authorise</Button>
                            <Button kind="subtle" onClick={() => amend(d.id, d)}>Amend</Button>
                            <Button kind="ghost" onClick={() => cancel(d.id)}>Cancel</Button>
                            <Button kind="ghost" onClick={() => reject(d.id)}>Reject</Button>
                          </>
                        )}
                        {d.status === "AUTHORIZED" && (
                          <>
                            <Button kind="primary" onClick={() => release(d.id)}>Release</Button>
                            <Button kind="ghost" onClick={() => cancel(d.id)}>Cancel</Button>
                          </>
                        )}
                        {d.status === "RELEASED" && (
                          <Button kind="ghost" onClick={() => reverse(d.id)}>Reverse</Button>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
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
                <Button kind="primary" onClick={recordRepayment}>Record repayment</Button>
              </div>
              {schedule.data ? (
                <>
                  <div style={{ fontSize: 12, opacity: 0.75, marginBottom: 6 }}>
                    Outstanding <b>{fmt.money(schedule.data.principal)}</b> · rate{" "}
                    <b>{(schedule.data.annualRate * 100).toFixed(2)}%</b>{" "}
                    <span className="mono">({schedule.data.rateSource})</span> ·{" "}
                    {schedule.data.periods} periods · total interest {fmt.money(schedule.data.totalInterest)}
                  </div>
                  <table>
                    <thead>
                      <tr><th>#</th><th>Due</th><th>Payment</th><th>Principal</th><th>Interest</th><th>Balance</th></tr>
                    </thead>
                    <tbody>
                      {schedule.data.rows.slice(0, 6).map((r: any) => (
                        <tr key={r.periodNo}>
                          <td>{r.periodNo}</td>
                          <td className="mono">{r.dueDate}</td>
                          <td>{fmt.money(r.payment)}</td>
                          <td>{fmt.money(r.principal)}</td>
                          <td>{fmt.money(r.interest)}</td>
                          <td>{fmt.money(r.closingBalance)}</td>
                        </tr>
                      ))}
                      {schedule.data.rows.length > 6 && (
                        <tr><td colSpan={6} style={{ opacity: 0.6 }}>
                          … {schedule.data.rows.length - 6} more periods
                        </td></tr>
                      )}
                    </tbody>
                  </table>
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
                <table>
                  <thead>
                    <tr><th>#</th><th>Amount</th><th>Principal</th><th>Source</th><th>Status</th><th>By</th><th /></tr>
                  </thead>
                  <tbody>
                    {(rpmts.data ?? []).map((p: any) => (
                      <tr key={p.id}>
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
                              <Button kind="ghost" onClick={() => rejectRepayment(p.id)}>Reject</Button>
                            </div>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
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
            First 12 periods · {proj.frequency} · {proj.periods} total · covenant {proj.minDscrCovenant.toFixed(2)}
          </div>
          <table>
            <thead>
              <tr>
                <th>#</th><th>Due</th><th>CFADS</th><th>O&amp;M</th><th>Debt svc</th>
                <th>DSRA</th><th>MMRA</th><th>Distrib.</th><th>DSCR</th><th />
              </tr>
            </thead>
            <tbody>
              {proj.rows.slice(0, 12).map((r: any) => (
                <tr key={r.periodNo} style={r.covenantBreach ? { background: "var(--bad-soft, #fee)" } : undefined}>
                  <td>{r.periodNo}</td>
                  <td className="mono" style={{ fontSize: 12 }}>{r.periodDate}</td>
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
        </>
      )}
    </Card>
  );
}
