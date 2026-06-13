/**
 * Structuring — specialised deal / counterparty structure page.
 *
 * Covers group, joint/dual-obligor, syndication, FI ICR, and renewal-copy
 * structures that sit alongside a deal application in origination-service.
 * Lets an RM or credit analyst set the structure type, manage participants
 * (obligors, guarantors, lead bank, participant lenders), view validation
 * findings, and copy a structure from a prior deal for amendments/renewals.
 */

import { useState } from "react";
import { amendments, origination, structure, syndication, fmt } from "../api";
import { useApp } from "../app-context";
import { Badge, Button, Card, Field, Stat, useAsync } from "../ui";

const STRUCTURE_TYPES = ["SINGLE", "GROUP", "JOINT_OBLIGOR", "DUAL_OBLIGOR", "SYNDICATION", "FI_ICR"];
const PARTICIPANT_ROLES = ["PRIMARY_OBLIGOR", "CO_OBLIGOR", "GUARANTOR", "GROUP_MEMBER", "LEAD_BANK", "PARTICIPANT_LENDER"];
const LIABILITY_TYPES = ["JOINT", "SEVERAL", "JOINT_AND_SEVERAL"];

function findingKind(level: string): string {
  if (level === "OK") return "ok";
  if (level === "WARN") return "warn";
  if (level === "ERROR") return "bad";
  return "info";
}

export default function Structuring() {
  const { actor, notify } = useApp();

  // ── Deal selector ──────────────────────────────────────────────────────────
  const deals = useAsync(() => origination.list(), []);
  const [ref, setRef] = useState("");

  // ── Structure fetch (rejects with Error when none set) ────────────────────
  const sv = useAsync(
    () => (ref ? structure.get(ref) : Promise.reject(new Error("no-ref"))),
    [ref],
  );

  // ── "Set structure" form state ────────────────────────────────────────────
  const [sType, setSType] = useState("SINGLE");
  const [sIslamic, setSIslamic] = useState(false);
  const [sGroup, setSGroup] = useState("");
  const [sLead, setSLead] = useState("");
  const [sTotalAmt, setSTotalAmt] = useState(0);
  const [sOurAmt, setSourAmt] = useState(0);
  const [sNotes, setSNotes] = useState("");
  const [sBusy, setSBusy] = useState(false);

  // ── Add participant form state ────────────────────────────────────────────
  const [pOpen, setPOpen] = useState(false);
  const [pRole, setPRole] = useState(PARTICIPANT_ROLES[0]);
  const [pName, setPName] = useState("");
  const [pExtRef, setPExtRef] = useState("");
  const [pSharePct, setPSharePct] = useState(0);
  const [pCommitted, setPCommitted] = useState(0);
  const [pLiability, setPLiability] = useState(LIABILITY_TYPES[0]);
  const [pBusy, setPBusy] = useState(false);

  // ── Copy-from state ───────────────────────────────────────────────────────
  const [copyRef, setCopyRef] = useState("");
  const [copyBusy, setCopyBusy] = useState(false);

  const noStructure = !sv.loading && !!sv.error && ref !== "";
  const hasStructure = !sv.loading && !sv.error && !!sv.data;
  const s = sv.data?.structure;
  const parts: any[] = sv.data?.participants ?? [];

  // ── Mutations ─────────────────────────────────────────────────────────────
  async function handleSet() {
    if (!ref) return;
    setSBusy(true);
    try {
      await structure.set(
        ref,
        {
          structureType: sType,
          islamic: sIslamic,
          groupReference: sGroup || undefined,
          leadArranger: sLead || undefined,
          totalDealAmount: sTotalAmt || undefined,
          ourShareAmount: sOurAmt || undefined,
          notes: sNotes || undefined,
        },
        actor,
      );
      notify("Structure saved");
      sv.reload();
    } catch (e: any) {
      notify(e.message, true);
    } finally {
      setSBusy(false);
    }
  }

  async function handleAddParticipant() {
    if (!ref) return;
    setPBusy(true);
    try {
      await structure.addParticipant(
        ref,
        {
          role: pRole,
          name: pName,
          externalRef: pExtRef || undefined,
          sharePct: pSharePct || undefined,
          committedAmount: pCommitted || undefined,
          liabilityType: pLiability || undefined,
        },
        actor,
      );
      notify("Participant added");
      setPName(""); setPExtRef(""); setPSharePct(0); setPCommitted(0); setPOpen(false);
      sv.reload();
    } catch (e: any) {
      notify(e.message, true);
    } finally {
      setPBusy(false);
    }
  }

  async function handleRemoveParticipant(id: number) {
    try {
      await structure.removeParticipant(id, actor);
      notify("Participant removed");
      sv.reload();
    } catch (e: any) {
      notify(e.message, true);
    }
  }

  async function handleCopyFrom() {
    if (!ref || !copyRef) return;
    setCopyBusy(true);
    try {
      await structure.copyFrom(ref, copyRef, actor);
      notify(`Structure copied from ${copyRef}`);
      sv.reload();
    } catch (e: any) {
      notify(e.message, true);
    } finally {
      setCopyBusy(false);
    }
  }

  const otherDeals = (deals.data ?? []).filter((d: any) => d.reference !== ref);

  return (
    <div className="grid">

      {/* ── Deal selector ── */}
      <Card title="Deal structuring" sub="Group, joint/dual-obligor, syndication, FI ICR · manage participants and copy for renewals">
        <Field label="Select deal">
          <select value={ref} onChange={(e) => { setRef(e.target.value); setPOpen(false); }}>
            <option value="">— choose a deal —</option>
            {(deals.data ?? []).map((d: any) => (
              <option key={d.reference} value={d.reference}>
                {d.reference} · {d.counterpartyName} · {d.status}
              </option>
            ))}
          </select>
        </Field>
        {deals.loading && <div className="muted">Loading deals…</div>}
      </Card>

      {/* ── Copy-from control (always shown when a deal is selected) ── */}
      {ref && (
        <Card title="Copy structure from another deal" sub="Use for amendments and renewals — copies structure type and participants from the source deal.">
          <div className="grid cols-2">
            <Field label="Source deal">
              <select value={copyRef} onChange={(e) => setCopyRef(e.target.value)}>
                <option value="">— choose source —</option>
                {otherDeals.map((d: any) => (
                  <option key={d.reference} value={d.reference}>
                    {d.reference} · {d.counterpartyName}
                  </option>
                ))}
              </select>
            </Field>
            <div className="btnrow" style={{ alignSelf: "flex-end" }}>
              <Button disabled={!copyRef} busy={copyBusy} onClick={handleCopyFrom}>Copy from</Button>
            </div>
          </div>
          <small className="prov">Server returns 409 if a structure already exists and the copy is not permitted.</small>
        </Card>
      )}

      {/* ── Set structure form (shown when no structure exists) ── */}
      {noStructure && (
        <Card title="Set structure" sub="Define the deal structure type before adding participants.">
          <div className="grid cols-2">
            <Field label="Structure type">
              <select value={sType} onChange={(e) => setSType(e.target.value)}>
                {STRUCTURE_TYPES.map((t) => <option key={t}>{t}</option>)}
              </select>
            </Field>
            <Field label="Islamic window">
              <input type="checkbox" checked={sIslamic} onChange={(e) => setSIslamic(e.target.checked)} />
              <span style={{ marginLeft: 8 }}>Islamic (Shariah-compliant)</span>
            </Field>
            <Field label="Group reference">
              <input value={sGroup} onChange={(e) => setSGroup(e.target.value)} placeholder="e.g. GRP-001" />
            </Field>
            <Field label="Lead arranger">
              <input value={sLead} onChange={(e) => setSLead(e.target.value)} placeholder="Bank name" />
            </Field>
            <Field label="Total deal amount">
              <input type="number" value={sTotalAmt} onChange={(e) => setSTotalAmt(+e.target.value)} />
            </Field>
            <Field label="Our share amount">
              <input type="number" value={sOurAmt} onChange={(e) => setSourAmt(+e.target.value)} />
            </Field>
          </div>
          <Field label="Notes">
            <input value={sNotes} onChange={(e) => setSNotes(e.target.value)} placeholder="Optional context" />
          </Field>
          <div className="btnrow">
            <Button busy={sBusy} onClick={handleSet}>Save structure</Button>
          </div>
        </Card>
      )}

      {/* ── Structure detail ── */}
      {hasStructure && s && (
        <>
          <Card
            title="Structure"
            sub={`ref: ${s.applicationReference}`}
            right={
              <Badge kind={sv.data!.valid ? "ok" : "bad"}>
                {sv.data!.valid ? "valid" : "invalid"}
              </Badge>
            }
          >
            <div className="grid cols-2">
              <div>
                <div style={{ marginBottom: 10 }}>
                  <Badge kind="info">{s.structureType}</Badge>
                  {s.islamic && <span style={{ marginLeft: 6 }}><Badge kind="ai">Islamic</Badge></span>}
                  {s.copiedFromReference && (
                    <span className="prov" style={{ marginLeft: 8 }}>
                      copied from <span className="mono">{s.copiedFromReference}</span>
                    </span>
                  )}
                </div>
                <div className="kv">
                  {s.groupReference && <><div className="k">Group ref</div><div className="v mono">{s.groupReference}</div></>}
                  {s.leadArranger && <><div className="k">Lead arranger</div><div className="v">{s.leadArranger}</div></>}
                  {s.notes && <><div className="k">Notes</div><div className="v">{s.notes}</div></>}
                </div>
              </div>
              <div className="grid cols-2">
                <Stat label="Our share" value={`${fmt.num(s.ourSharePct ?? 0, 1)}%`} />
                <Stat label="Total deal" value={fmt.money(s.totalDealAmount ?? 0)} />
                <Stat label="Our amount" value={fmt.money(s.ourShareAmount ?? 0)} />
                <Stat label="Obligor Σ%" value={`${fmt.num(sv.data!.obligorShareSumPct ?? 0, 1)}%`} />
              </div>
            </div>

            {/* Findings */}
            {(sv.data!.findings ?? []).length > 0 && (
              <div style={{ marginTop: 12 }}>
                <h4>Validation findings</h4>
                <ul style={{ listStyle: "none", padding: 0, margin: 0 }}>
                  {sv.data!.findings.map((f: any, i: number) => (
                    <li key={i} style={{ marginBottom: 4 }}>
                      <Badge kind={findingKind(f.level)}>{f.level}</Badge>
                      <span style={{ marginLeft: 8 }}>{f.message}</span>
                    </li>
                  ))}
                </ul>
              </div>
            )}

            {/* Lender committed sum */}
            <div style={{ marginTop: 12 }}>
              <Stat label="Lender committed Σ" value={fmt.money(sv.data!.lenderCommittedSum ?? 0)} />
            </div>
          </Card>

          {/* ── Participants table ── */}
          <Card
            title="Participants"
            right={
              <Button kind="ghost" onClick={() => setPOpen((o) => !o)}>
                {pOpen ? "Cancel" : "+ Add participant"}
              </Button>
            }
          >
            {parts.length === 0 ? (
              <div className="muted">No participants yet — add obligors, guarantors, or lenders.</div>
            ) : (
              <table>
                <thead>
                  <tr>
                    <th>#</th>
                    <th>Role</th>
                    <th>Name</th>
                    <th>Ext ref</th>
                    <th className="num">Share %</th>
                    <th className="num">Committed</th>
                    <th>Liability</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody>
                  {parts.map((p: any) => (
                    <tr key={p.id}>
                      <td className="num">{p.ordinal}</td>
                      <td><Badge kind="info">{p.role}</Badge></td>
                      <td>{p.name}</td>
                      <td className="mono">{p.externalRef || "—"}</td>
                      <td className="num">{p.sharePct ? `${fmt.num(p.sharePct, 1)}%` : "—"}</td>
                      <td className="num">{p.committedAmount != null ? fmt.money(p.committedAmount) : "—"}</td>
                      <td>{p.liabilityType ? <Badge>{p.liabilityType}</Badge> : <span className="muted">—</span>}</td>
                      <td>
                        <Button kind="subtle" onClick={() => handleRemoveParticipant(p.id)}>Remove</Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}

            {/* Add participant inline form */}
            {pOpen && (
              <div className="card" style={{ marginTop: 12, background: "#fbfaff" }}>
                <div className="grid cols-2">
                  <Field label="Role">
                    <select value={pRole} onChange={(e) => setPRole(e.target.value)}>
                      {PARTICIPANT_ROLES.map((r) => <option key={r}>{r}</option>)}
                    </select>
                  </Field>
                  <Field label="Name">
                    <input value={pName} onChange={(e) => setPName(e.target.value)} placeholder="Counterparty name" />
                  </Field>
                  <Field label="External ref">
                    <input value={pExtRef} onChange={(e) => setPExtRef(e.target.value)} placeholder="CIF / external ID" />
                  </Field>
                  <Field label="Liability type">
                    <select value={pLiability} onChange={(e) => setPLiability(e.target.value)}>
                      {LIABILITY_TYPES.map((l) => <option key={l}>{l}</option>)}
                    </select>
                  </Field>
                  <Field label="Share %">
                    <input type="number" value={pSharePct} onChange={(e) => setPSharePct(+e.target.value)} />
                  </Field>
                  <Field label="Committed amount">
                    <input type="number" value={pCommitted} onChange={(e) => setPCommitted(+e.target.value)} />
                  </Field>
                </div>
                <div className="btnrow">
                  <Button busy={pBusy} disabled={!pName} onClick={handleAddParticipant}>Add participant</Button>
                  <Button kind="subtle" onClick={() => setPOpen(false)}>Cancel</Button>
                </div>
              </div>
            )}
          </Card>
        </>
      )}

      {hasStructure && s?.structureType === "SYNDICATION" && (
        <SyndicationLifecycleCard refValue={ref} participants={parts} />
      )}

      {ref && <AmendmentsCard refValue={ref} />}

      {/* Loading / empty state */}
      {ref && sv.loading && <div className="muted">Loading structure…</div>}
      {!ref && <div className="muted">Select a deal above to view or configure its structure.</div>}
    </div>
  );
}

/**
 * Post-sanction facility amendments: propose an increase / decrease / tenor
 * extension; the required authority is routed from the DoA matrix on the
 * post-amendment total exposure, and approval needs that authority RANK plus a
 * different human than the proposer. On approval the facility of record and the
 * limit tree update immediately.
 */
function AmendmentsCard({ refValue }: { refValue: string }) {
  const { actor, notify } = useApp();
  const facs = useAsync(() => origination.facilities(refValue), [refValue]);
  const history = useAsync(() => amendments.history(refValue), [refValue]);
  const [facilityRef, setFacilityRef] = useState("");
  const [newAmount, setNewAmount] = useState("");
  const [newTenor, setNewTenor] = useState("");
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);

  async function propose() {
    if (!facilityRef || !reason) { notify("Pick a facility and give a reason", true); return; }
    setBusy(true);
    try {
      const body: any = { facilityRef, reason };
      const amt = parseFloat(newAmount.replace(/[, ]/g, ""));
      if (!Number.isNaN(amt) && amt > 0) body.newAmount = amt;
      const ten = parseInt(newTenor, 10);
      if (!Number.isNaN(ten) && ten > 0) body.newTenorMonths = ten;
      const a = await amendments.propose(refValue, body, actor);
      notify(`Amendment proposed — needs ${a.requiredAuthority} to approve`);
      setNewAmount(""); setNewTenor(""); setReason("");
      history.reload();
    } catch (e: any) { notify(e.message, true); } finally { setBusy(false); }
  }

  async function decide(id: number, approve: boolean) {
    try {
      if (approve) await amendments.approve(id, { comment: "approved via UI" }, actor);
      else {
        const r = prompt("Rejection reason") || "";
        if (!r) return;
        await amendments.reject(id, { reason: r }, actor);
      }
      notify(approve ? "Amendment approved — facility + limit tree updated" : "Amendment rejected");
      history.reload();
    } catch (e: any) { notify(e.message, true); }
  }

  return (
    <Card title="Post-sanction amendments"
      sub="Increase / decrease / tenor extension — routed through the SAME DoA matrix that sanctioned the deal. Approver needs the routed authority rank; proposer ≠ approver.">
      <div className="grid cols-2">
        <div>
          <Field label="Facility">
            <select value={facilityRef} onChange={(e) => setFacilityRef(e.target.value)}>
              <option value="">— select —</option>
              {(facs.data ?? []).map((f: any) => (
                <option key={f.reference} value={f.reference}>
                  {f.facilityType} · {f.amount.toLocaleString()} {f.currency} · {f.tenorMonths}m
                </option>
              ))}
            </select>
          </Field>
          <Field label="New amount (blank = unchanged)">
            <input value={newAmount} onChange={(e) => setNewAmount(e.target.value)} placeholder="e.g. 900000000" />
          </Field>
          <Field label="New tenor months (blank = unchanged)">
            <input value={newTenor} onChange={(e) => setNewTenor(e.target.value)} placeholder="e.g. 84" />
          </Field>
          <Field label="Reason (required)">
            <input value={reason} onChange={(e) => setReason(e.target.value)} />
          </Field>
          <Button kind="primary" busy={busy} onClick={propose}>Propose amendment</Button>
        </div>
        <div>
          {(history.data ?? []).length === 0 ? (
            <div className="muted">No amendments on this deal yet.</div>
          ) : (
            <table>
              <thead>
                <tr><th>#</th><th>Type</th><th>Change</th><th>Authority</th><th>Status</th><th /></tr>
              </thead>
              <tbody>
                {(history.data ?? []).map((a: any) => (
                  <tr key={a.id}>
                    <td>{a.id}</td>
                    <td className="mono">{a.amendmentType}</td>
                    <td style={{ fontSize: 12 }}>
                      {a.proposedAmount != null &&
                        <>{a.currentAmount.toLocaleString()} → <b>{a.proposedAmount.toLocaleString()}</b><br /></>}
                      {a.proposedTenorMonths != null &&
                        <>{a.currentTenorMonths}m → <b>{a.proposedTenorMonths}m</b></>}
                    </td>
                    <td><Badge kind="info">{a.requiredAuthority}</Badge></td>
                    <td>
                      <Badge kind={a.status === "APPROVED" ? "ok" : a.status === "REJECTED" ? "bad" : "warn"}>
                        {a.status}
                      </Badge>
                    </td>
                    <td>
                      {a.status === "PROPOSED" && (
                        <div style={{ display: "flex", gap: 4 }}>
                          <Button kind="subtle" onClick={() => decide(a.id, true)}>Approve</Button>
                          <Button kind="ghost" onClick={() => decide(a.id, false)}>Reject</Button>
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
  );
}

/**
 * Syndication lifecycle: invitations (lead sends, invitee accepts/declines —
 * accepted invitees materialise as DealParticipant rows) and secondary
 * transfers (transferor sells unfunded commitment to a new bank, agent
 * settles — funded historical allocations stay with the seller).
 */
function SyndicationLifecycleCard({ refValue, participants }: { refValue: string; participants: any[] }) {
  const { actor, notify } = useApp();
  const invs = useAsync(() => syndication.invitations(refValue), [refValue]);
  const xfers = useAsync(() => syndication.transfers(refValue), [refValue]);

  const [iName, setIName] = useState("");
  const [iRef, setIRef] = useState("");
  const [iAmt, setIAmt] = useState("");
  const [iDays, setIDays] = useState("30");
  const [iBusy, setIBusy] = useState(false);

  const [tFromId, setTFromId] = useState<number | "">("");
  const [tToBank, setTToBank] = useState("");
  const [tToBankRef, setTToBankRef] = useState("");
  const [tAmt, setTAmt] = useState("");
  const [tReason, setTReason] = useState("");
  const [tBusy, setTBusy] = useState(false);

  const lenders = participants.filter((p: any) =>
    p.role === "LEAD_BANK" || p.role === "PARTICIPANT_LENDER");

  async function sendInvitation() {
    const amt = parseFloat(iAmt.replace(/[, ]/g, ""));
    if (!iName || !amt) { notify("Bank name and commitment are required", true); return; }
    setIBusy(true);
    try {
      await syndication.invite(refValue, {
        invitedBank: iName, invitedBankRef: iRef || undefined,
        proposedCommitment: amt, proposedRole: "PARTICIPANT_LENDER",
        currency: "INR", terms: "standard",
        expiresInDays: parseInt(iDays, 10) || 30,
      }, actor);
      notify(`Invitation sent to ${iName}`);
      setIName(""); setIRef(""); setIAmt("");
      invs.reload();
    } catch (e: any) { notify(e.message, true); } finally { setIBusy(false); }
  }

  async function decideInvitation(inv: any, action: "accept" | "decline" | "withdraw") {
    try {
      if (action === "accept") {
        await syndication.acceptInvitation(inv.id, actor);
      } else {
        const reason = prompt(action === "decline" ? "Decline reason" : "Withdraw reason") || "";
        if (!reason && action === "decline") return;
        if (action === "decline") await syndication.declineInvitation(inv.id, { reason }, actor);
        else await syndication.withdrawInvitation(inv.id, { reason }, actor);
      }
      notify(`Invitation ${action}ed`);
      invs.reload();
    } catch (e: any) { notify(e.message, true); }
  }

  async function proposeTransfer() {
    const amt = parseFloat(tAmt.replace(/[, ]/g, ""));
    if (!tFromId || !tToBank || !amt) { notify("Source lender, target bank and amount are required", true); return; }
    setTBusy(true);
    try {
      await syndication.proposeTransfer(refValue, {
        fromParticipantId: tFromId, toBank: tToBank,
        toBankRef: tToBankRef || undefined,
        transferAmount: amt, currency: "INR", reason: tReason || "balance-sheet rotation",
      }, actor);
      notify(`Transfer proposed: ${tToBank} buys ${amt.toLocaleString()}`);
      setTToBank(""); setTToBankRef(""); setTAmt(""); setTReason("");
      xfers.reload();
    } catch (e: any) { notify(e.message, true); } finally { setTBusy(false); }
  }

  async function decideTransfer(t: any, action: "settle" | "reject") {
    try {
      if (action === "settle") {
        await syndication.settleTransfer(t.id, { comment: "agent settles" }, actor);
      } else {
        const reason = prompt("Rejection reason") || "";
        if (!reason) return;
        await syndication.rejectTransfer(t.id, { reason }, actor);
      }
      notify(`Transfer ${action === "settle" ? "settled" : "rejected"}`);
      xfers.reload();
    } catch (e: any) { notify(e.message, true); }
  }

  return (
    <>
      <Card title="Syndicate invitations"
        sub="Lead bank invites a participant lender. Accept materialises a syndicate member; SoD: invitee ≠ inviter."
        right={<Badge kind="info">SYNDICATION LIFECYCLE</Badge>}>
        <div className="grid cols-4" style={{ alignItems: "end" }}>
          <Field label="Bank name">
            <input value={iName} onChange={(e) => setIName(e.target.value)} placeholder="e.g. New Capital Bank" />
          </Field>
          <Field label="External ref">
            <input value={iRef} onChange={(e) => setIRef(e.target.value)} placeholder="optional" />
          </Field>
          <Field label="Proposed commitment (INR)">
            <input value={iAmt} onChange={(e) => setIAmt(e.target.value)} placeholder="500000000" />
          </Field>
          <Field label="Expires in (days)">
            <input value={iDays} onChange={(e) => setIDays(e.target.value)} />
          </Field>
        </div>
        <div className="btnrow" style={{ marginTop: 8 }}>
          <Button kind="primary" busy={iBusy} onClick={sendInvitation}>Send invitation</Button>
        </div>
        {(invs.data ?? []).length > 0 && (
          <table>
            <thead>
              <tr><th>#</th><th>Bank</th><th>Commitment</th><th>Role</th><th>Status</th><th>By</th><th /></tr>
            </thead>
            <tbody>
              {(invs.data ?? []).map((inv: any) => (
                <tr key={inv.id}>
                  <td>{inv.id}</td>
                  <td>{inv.invitedBank}</td>
                  <td>{fmt.money(inv.proposedCommitment)}</td>
                  <td className="mono">{inv.proposedRole}</td>
                  <td>
                    <Badge kind={inv.status === "ACCEPTED" ? "ok"
                                : inv.status === "DECLINED" || inv.status === "WITHDRAWN" || inv.status === "EXPIRED" ? "bad"
                                : "warn"}>{inv.status}</Badge>
                  </td>
                  <td className="mono" style={{ fontSize: 12 }}>{inv.decidedBy ?? inv.invitedBy}</td>
                  <td>
                    {inv.status === "SENT" && (
                      <div style={{ display: "flex", gap: 4 }}>
                        <Button kind="subtle" onClick={() => decideInvitation(inv, "accept")}>Accept</Button>
                        <Button kind="ghost" onClick={() => decideInvitation(inv, "decline")}>Decline</Button>
                        <Button kind="ghost" onClick={() => decideInvitation(inv, "withdraw")}>Withdraw</Button>
                      </div>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>

      <Card title="Secondary market transfers"
        sub="Sell down UNFUNDED commitment to a new bank. Funded historical allocations stay with the original lender. SoD: agent settler ≠ transferor.">
        <div className="grid cols-4" style={{ alignItems: "end" }}>
          <Field label="Selling lender">
            <select value={tFromId} onChange={(e) => setTFromId(e.target.value ? Number(e.target.value) : "")}>
              <option value="">— select —</option>
              {lenders.map((p: any) => (
                <option key={p.id} value={p.id}>{p.name} ({p.role}, {fmt.money(p.committedAmount || 0)})</option>
              ))}
            </select>
          </Field>
          <Field label="Buyer (bank)">
            <input value={tToBank} onChange={(e) => setTToBank(e.target.value)} placeholder="e.g. Polestar Asset Mgmt" />
          </Field>
          <Field label="Buyer external ref">
            <input value={tToBankRef} onChange={(e) => setTToBankRef(e.target.value)} placeholder="optional" />
          </Field>
          <Field label="Transfer amount (INR)">
            <input value={tAmt} onChange={(e) => setTAmt(e.target.value)} placeholder="300000000" />
          </Field>
        </div>
        <Field label="Reason">
          <input value={tReason} onChange={(e) => setTReason(e.target.value)} placeholder="balance-sheet rotation" />
        </Field>
        <div className="btnrow" style={{ marginTop: 8 }}>
          <Button kind="primary" busy={tBusy} onClick={proposeTransfer}>Propose transfer</Button>
        </div>
        {(xfers.data ?? []).length > 0 && (
          <table>
            <thead>
              <tr><th>#</th><th>From</th><th>To</th><th>Amount</th><th>Status</th><th>Agent</th><th /></tr>
            </thead>
            <tbody>
              {(xfers.data ?? []).map((t: any) => (
                <tr key={t.id}>
                  <td>{t.id}</td>
                  <td>{t.fromName}</td>
                  <td>{t.toBank}</td>
                  <td>{fmt.money(t.transferAmount)}</td>
                  <td>
                    <Badge kind={t.status === "SETTLED" ? "ok"
                                : t.status === "REJECTED" ? "bad" : "warn"}>{t.status}</Badge>
                  </td>
                  <td className="mono" style={{ fontSize: 12 }}>{t.agentDecidedBy ?? t.proposedBy}</td>
                  <td>
                    {t.status === "PROPOSED" && (
                      <div style={{ display: "flex", gap: 4 }}>
                        <Button kind="subtle" onClick={() => decideTransfer(t, "settle")}>Settle</Button>
                        <Button kind="ghost" onClick={() => decideTransfer(t, "reject")}>Reject</Button>
                      </div>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
    </>
  );
}
