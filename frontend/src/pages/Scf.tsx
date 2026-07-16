/**
 * SCF — Supply-Chain Finance product paper.
 *
 * An anchor-backed VENDOR / DEALER finance programme that spokes (the anchor's
 * suppliers / distributors) draw against. Create a DRAFT programme, add spokes that
 * are judged DETERMINISTICALLY against the pinned SCF_ELIGIBILITY snapshot + per-spoke
 * cap (PASS/FAIL + reasons), submit for approval (which raises a linked PRODUCT_PAPER
 * noting in decision-service), and approve/reject under segregation of duties + credit
 * authority. On approval the programme limit is registered into limit-service's own
 * governed limit tree — SCF never writes an authoritative figure itself.
 */

import { useState } from "react";
import { scf, fmt } from "../api";
import { useApp } from "../app-context";
import { Badge, Button, Card, Col, DataTable, EmptyState, Field, HumanBadge, statusTone, useAsync } from "../ui";

const PROGRAM_TYPES = ["VENDOR", "DEALER"];

function eligKind(result: string): string {
  return result === "PASS" ? "ok" : result === "FAIL" ? "bad" : "info";
}

export default function Scf() {
  const { actor, notify } = useApp();
  const programs = useAsync(() => scf.list(), []);
  const [selected, setSelected] = useState<string>("");

  // ── Create-programme form ──────────────────────────────────────────────────
  const [anchorRef, setAnchorRef] = useState("");
  const [anchorName, setAnchorName] = useState("");
  const [programType, setProgramType] = useState(PROGRAM_TYPES[0]);
  const [programLimit, setProgramLimit] = useState("");
  const [perSpokeCap, setPerSpokeCap] = useState("");
  const [currency, setCurrency] = useState("INR");
  const [createBusy, setCreateBusy] = useState(false);

  async function createProgram() {
    if (!anchorRef.trim()) { notify("Anchor reference is required", true); return; }
    const limit = parseFloat(programLimit.replace(/[, ]/g, ""));
    const cap = parseFloat(perSpokeCap.replace(/[, ]/g, ""));
    if (Number.isNaN(limit) || limit <= 0) { notify("Programme limit must be positive", true); return; }
    setCreateBusy(true);
    try {
      const v = await scf.create({
        anchorRef: anchorRef.trim(),
        anchorName: anchorName.trim() || undefined,
        programType,
        programLimit: limit,
        perSpokeCap: Number.isNaN(cap) ? 0 : cap,
        currency,
      }, actor);
      notify(`Programme ${v.program.scfRef} created (DRAFT)`);
      setAnchorRef(""); setAnchorName(""); setProgramLimit(""); setPerSpokeCap("");
      programs.reload();
      setSelected(v.program.scfRef);
    } catch (e: any) { notify(e.message, true); } finally { setCreateBusy(false); }
  }

  const columns: Col<any>[] = [
    { key: "scfRef", header: "Ref", render: (r) => <span className="mono">{r.scfRef}</span> },
    { key: "programType", header: "Type", render: (r) => <Badge kind="info">{r.programType}</Badge> },
    { key: "anchorRef", header: "Anchor", render: (r) => <span className="mono">{r.anchorRef}</span> },
    { key: "programLimit", header: "Limit", align: "right", value: (r) => r.programLimit, render: (r) => fmt.money(r.programLimit, r.currency) },
    { key: "perSpokeCap", header: "Per-spoke cap", align: "right", value: (r) => r.perSpokeCap, render: (r) => fmt.money(r.perSpokeCap, r.currency) },
    { key: "status", header: "Status", render: (r) => <Badge kind={statusTone(r.status)}>{r.status}</Badge> },
    { key: "notingRef", header: "Noting", render: (r) => r.notingRef ? <span className="mono">{r.notingRef}</span> : <span className="muted">—</span> },
  ];

  return (
    <div className="grid cols-2">
      <div className="grid">
        <Card
          title="SCF programmes"
          right={<HumanBadge label="HUMAN-GATED APPROVAL" />}
          sub="Anchor-backed vendor / dealer finance. Deterministic spoke eligibility · human-gated approval · limit registered via limit-service's own governed API."
        >
          <div className="scf-note">
            Spoke eligibility is <b>deterministic</b> against the pinned <b>SCF_ELIGIBILITY</b> snapshot.
            Submit raises a linked <b>PRODUCT_PAPER</b> noting; approval (SoD: approver ≠ raiser +
            credit authority) registers the programme limit into the limit tree.
          </div>
          {(programs.data || []).length === 0 ? (
            <EmptyState glyph="◲" title="No SCF programmes yet" sub="Draft one with the form below." />
          ) : (
            <DataTable
              id="scf-programs"
              columns={columns}
              rows={programs.data || []}
              rowKey={(r) => r.scfRef}
              onRowClick={(r) => setSelected(r.scfRef)}
              initialPageSize={10}
            />
          )}
        </Card>

        <Card title="Draft a programme" sub="Anchor-backed VENDOR or DEALER finance. Eligibility criteria are pinned from the SCF_ELIGIBILITY master at create.">
          <div className="grid cols-2">
            <Field label="Anchor reference" required>
              <input value={anchorRef} onChange={(e) => setAnchorRef(e.target.value)} placeholder="e.g. CP-ANCHOR1" />
            </Field>
            <Field label="Anchor name">
              <input value={anchorName} onChange={(e) => setAnchorName(e.target.value)} placeholder="Anchor legal name" />
            </Field>
            <Field label="Programme type">
              <select value={programType} onChange={(e) => setProgramType(e.target.value)}>
                {PROGRAM_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
              </select>
            </Field>
            <Field label="Currency">
              <input value={currency} onChange={(e) => setCurrency(e.target.value.toUpperCase())} />
            </Field>
            <Field label="Programme limit" required>
              <input value={programLimit} onChange={(e) => setProgramLimit(e.target.value)} placeholder="e.g. 500000000" />
            </Field>
            <Field label="Per-spoke cap">
              <input value={perSpokeCap} onChange={(e) => setPerSpokeCap(e.target.value)} placeholder="e.g. 40000000" />
            </Field>
          </div>
          <div className="btnrow">
            <Button kind="primary" busy={createBusy} onClick={createProgram}>Create programme</Button>
          </div>
        </Card>
      </div>

      <div className="grid">
        {selected
          ? <ProgramDetail key={selected} scfRef={selected} onChanged={() => programs.reload()} />
          : <Card title="Programme detail"><div className="muted">Select a programme to view spokes, eligibility and the approval workflow.</div></Card>}
      </div>
    </div>
  );
}

function ProgramDetail({ scfRef, onChanged }: { scfRef: string; onChanged: () => void }) {
  const { actor, notify } = useApp();
  const view = useAsync(() => scf.get(scfRef), [scfRef]);

  const [spokeRef, setSpokeRef] = useState("");
  const [spokeName, setSpokeName] = useState("");
  const [requested, setRequested] = useState("");
  const [spokeBusy, setSpokeBusy] = useState(false);
  const [wfBusy, setWfBusy] = useState(false);

  const p = view.data?.program;
  const spokes: any[] = view.data?.spokes ?? [];
  const isDraft = p?.status === "DRAFT";
  const isPending = p?.status === "PENDING_APPROVAL";

  function refresh() { view.reload(); onChanged(); }

  async function addSpoke() {
    if (!spokeRef.trim()) { notify("Spoke reference is required", true); return; }
    const amt = parseFloat(requested.replace(/[, ]/g, ""));
    if (Number.isNaN(amt) || amt <= 0) { notify("Requested amount must be positive", true); return; }
    setSpokeBusy(true);
    try {
      await scf.addSpoke(scfRef, { spokeRef: spokeRef.trim(), spokeName: spokeName.trim() || undefined, requestedAmount: amt }, actor);
      notify(`Spoke ${spokeRef} evaluated`);
      setSpokeRef(""); setSpokeName(""); setRequested("");
      view.reload();
    } catch (e: any) { notify(e.message, true); } finally { setSpokeBusy(false); }
  }

  async function submit() {
    setWfBusy(true);
    try {
      const v = await scf.submit(scfRef, actor);
      notify(v.program.notingRef
        ? `Submitted — linked noting ${v.program.notingRef}`
        : "Submitted (noting service unavailable — programme continues)");
      refresh();
    } catch (e: any) { notify(e.message, true); } finally { setWfBusy(false); }
  }

  async function approve() {
    if (!window.confirm(`Approve SCF programme ${scfRef}? This registers the programme limit into the limit tree.`)) return;
    setWfBusy(true);
    try {
      const v = await scf.approve(scfRef, { note: "approved via UI" }, actor);
      notify(v.program.registeredLimitRef
        ? `Approved — limit node ${v.program.registeredLimitRef}`
        : "Approved (limit service unavailable — approval stands)");
      refresh();
    } catch (e: any) { notify(e.message, true); } finally { setWfBusy(false); }
  }

  async function reject() {
    const note = window.prompt("Rejection note (optional)") ?? undefined;
    if (note === undefined) return;
    setWfBusy(true);
    try {
      await scf.reject(scfRef, { note }, actor);
      notify("Programme rejected");
      refresh();
    } catch (e: any) { notify(e.message, true); } finally { setWfBusy(false); }
  }

  async function withdraw() {
    if (!window.confirm(`Withdraw SCF programme ${scfRef}?`)) return;
    setWfBusy(true);
    try {
      await scf.withdraw(scfRef, actor);
      notify("Programme withdrawn");
      refresh();
    } catch (e: any) { notify(e.message, true); } finally { setWfBusy(false); }
  }

  if (view.loading) return <Card title="Programme detail"><div className="muted">Loading…</div></Card>;
  if (view.error || !p) return <Card title="Programme detail"><div className="muted">Could not load {scfRef}.</div></Card>;

  return (
    <>
      <Card
        title={p.scfRef}
        right={<Badge kind={statusTone(p.status)}>{p.status}</Badge>}
        sub={`${p.programType} · anchor ${p.anchorRef}`}
      >
        <div className="kv">
          <div className="k">Anchor</div><div className="v">{p.anchorName || p.anchorRef}</div>
          <div className="k">Programme limit</div><div className="v">{fmt.money(p.programLimit, p.currency)}</div>
          <div className="k">Per-spoke cap</div><div className="v">{fmt.money(p.perSpokeCap, p.currency)}</div>
          <div className="k">Raised by</div><div className="v mono">{p.raisedBy}</div>
          {p.notingRef && <><div className="k">Linked noting</div><div className="v mono">{p.notingRef}</div></>}
          {p.registeredLimitRef && <><div className="k">Limit node</div><div className="v mono">{p.registeredLimitRef}</div></>}
          {p.decidedBy && <><div className="k">Decided by</div><div className="v mono">{p.decidedBy}</div></>}
        </div>

        <div className="scf-rollup">
          <span>Spokes <b>{view.data.spokeCount}</b></span>
          <span>Eligible <b>{view.data.eligibleCount}</b></span>
          <span>Requested Σ <b>{fmt.money(view.data.requestedTotal, p.currency)}</b></span>
          <span>Approved cap Σ <b>{fmt.money(view.data.approvedCapTotal, p.currency)}</b></span>
        </div>

        <div className="btnrow" style={{ marginTop: 12 }}>
          {isDraft && <Button kind="primary" busy={wfBusy} onClick={submit}>Submit for approval</Button>}
          {isPending && <Button kind="primary" busy={wfBusy} onClick={approve}>Approve</Button>}
          {isPending && <Button kind="ghost" busy={wfBusy} onClick={reject}>Reject</Button>}
          {(isDraft || isPending) && <Button kind="subtle" busy={wfBusy} onClick={withdraw}>Withdraw</Button>}
        </div>
      </Card>

      <Card title="Spokes" sub="Deterministic eligibility against the pinned SCF_ELIGIBILITY snapshot + per-spoke cap.">
        {spokes.length === 0 ? (
          <div className="muted">No spokes yet — add the anchor's suppliers / distributors below.</div>
        ) : (
          <table>
            <thead>
              <tr><th>Spoke</th><th>Name</th><th className="num">Requested</th><th>Eligibility</th><th className="num">Approved cap</th><th>Reasons</th></tr>
            </thead>
            <tbody>
              {spokes.map((s: any) => (
                <tr key={s.id}>
                  <td className="mono">{s.spokeRef}</td>
                  <td>{s.spokeName || "—"}</td>
                  <td className="num">{fmt.money(s.requestedAmount, p.currency)}</td>
                  <td><Badge kind={eligKind(s.eligibilityResult)}>{s.eligibilityResult}</Badge></td>
                  <td className="num">{s.approvedCap > 0 ? fmt.money(s.approvedCap, p.currency) : "—"}</td>
                  <td style={{ fontSize: 12 }}>
                    {(s.reasons ?? []).length === 0
                      ? <span className="muted">—</span>
                      : <ul className="scf-reasons">{s.reasons.map((r: string, i: number) => <li key={i}>{r}</li>)}</ul>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        {isDraft && (
          <div className="card" style={{ marginTop: 12 }}>
            <div className="grid cols-2">
              <Field label="Spoke reference" required>
                <input value={spokeRef} onChange={(e) => setSpokeRef(e.target.value)} placeholder="e.g. CP-SPOKE1" />
              </Field>
              <Field label="Spoke name">
                <input value={spokeName} onChange={(e) => setSpokeName(e.target.value)} placeholder="Supplier / distributor name" />
              </Field>
              <Field label="Requested amount" required>
                <input value={requested} onChange={(e) => setRequested(e.target.value)} placeholder="e.g. 20000000" />
              </Field>
            </div>
            <div className="btnrow">
              <Button busy={spokeBusy} disabled={!spokeRef} onClick={addSpoke}>Add + evaluate spoke</Button>
            </div>
          </div>
        )}
        {!isDraft && <div className="scf-note" style={{ marginTop: 12 }}>Spokes are locked once the programme leaves DRAFT.</div>}
      </Card>
    </>
  );
}
