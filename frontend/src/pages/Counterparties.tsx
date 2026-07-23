import { useState } from "react";
import { config, counterparty, fmt, initiation, sourceIngest } from "../api";
import { useApp } from "../app-context";
import {
  AiBadge, Badge, Button, Card, type Col, DataTable, EmptyState, Field, GovFlow,
  QuickCreate, statusTone, Unchanged, useAsync,
} from "../ui";
import { useCodes } from "../code-values";

const SAMPLE_UBO = JSON.stringify({
  nodes: [
    { key: "ROOT", name: "(this counterparty)", type: "ROOT", country: "IN", confidence: 1.0 },
    { key: "HOLDCO", name: "Holding Co", type: "ENTITY", country: "IN", confidence: 1.0 },
    { key: "P1", name: "Promoter One", type: "PERSON", country: "IN", confidence: 0.95 },
    { key: "P2", name: "Promoter Two", type: "PERSON", country: "AE", confidence: 0.7 },
  ],
  edges: [
    { parent: "HOLDCO", child: "ROOT", ownershipPct: 0.8 },
    { parent: "P1", child: "HOLDCO", ownershipPct: 0.6 },
    { parent: "P1", child: "ROOT", ownershipPct: 0.1 },
    { parent: "P2", child: "HOLDCO", ownershipPct: 0.4 },
  ],
}, null, 2);

/** A counterparty is prospect-stage until a named human promotes it to an obligor. */
function isProspect(c: any): boolean {
  return c?.recordType === "PROSPECT" || c?.lifecycleStatus === "DRAFT";
}

export default function Counterparties() {
  const { actor, notify } = useApp();
  const list = useAsync(() => counterparty.list(), []);
  const segments = useCodes("SEGMENT");
  const jurisdictionsAsync = useAsync<any[]>(() => config.jurisdictions(), []);
  const jurisdictions = (jurisdictionsAsync.data || []) as any[];
  const [selId, setSelId] = useState<number | null>(null);
  const [creating, setCreating] = useState(false);
  const [pulling, setPulling] = useState(false);

  const cols: Col<any>[] = [
    {
      key: "legalName", header: "Name",
      render: (c) => (
        <>
          {c.legalName}
          {isProspect(c) && <> <Badge kind="warn">PROSPECT</Badge></>}
          <br /><small className="prov">{c.reference}</small>
        </>
      ),
      value: (c) => `${c.legalName ?? ""} ${c.reference ?? ""}`,
    },
    { key: "segment", header: "Segment" },
    {
      key: "cddTier", header: "CDD",
      render: (c) => <Badge kind={c.cddTier === "ENHANCED" ? "warn" : "info"}>{c.cddTier}</Badge>,
      value: (c) => c.cddTier ?? "",
    },
    {
      key: "kycStatus", header: "KYC",
      render: (c) => <Badge kind={statusTone(c.kycStatus)}>{c.kycStatus}</Badge>,
      value: (c) => c.kycStatus ?? "",
    },
  ];

  return (
    <div className="grid cols-2">
      <div className="grid">
        <Card title="Counterparties">
          <DataTable
            id="counterparties"
            columns={cols}
            rows={list.data || []}
            rowKey={(c) => String(c.id)}
            onRowClick={(c) => setSelId(c.id)}
            toolbarRight={
              <div className="btnrow">
                <Button kind="subtle" onClick={async () => {
                  try { const r = await counterparty.reKycSweep(undefined, actor); notify(`Re-KYC sweep: ${r.flagged} of ${r.scanned} flagged`); list.reload(); }
                  catch (e: any) { notify(e.message, true); }
                }}>Run re-KYC sweep</Button>
                <Button kind="subtle" onClick={() => setPulling((p) => !p)}>{pulling ? "Close CRM pull" : "Pull borrower from CRM"}</Button>
                <QuickCreate
                  buttonLabel="＋ Quick create"
                  buttonKind="subtle"
                  title="Quick-onboard a counterparty"
                  sub="Fast path for a low-risk obligor. Use + New for the full form with risk flags."
                  fields={[
                    { name: "legalName", label: "Legal name", required: true, placeholder: "e.g. Meridian Steel Ltd" },
                    { name: "segment", label: "Segment", type: "select", options: segments.map((s) => ({ value: s.code, label: s.label })) },
                    { name: "jurisdiction", label: "Jurisdiction", type: "select", options: jurisdictions.map((j: any) => ({ value: j.code, label: j.code })) },
                    { name: "sector", label: "Sector", placeholder: "e.g. MANUFACTURING" },
                    { name: "country", label: "Country", placeholder: "e.g. IN" },
                  ]}
                  submitLabel="Onboard"
                  onSubmit={async (v) => {
                    const cp = await counterparty.create({
                      legalName: v.legalName.trim(), legalForm: "PRIVATE_LTD", registrationNo: "",
                      jurisdiction: v.jurisdiction || "IN-RBI", segment: v.segment || "MID_CORPORATE",
                      sector: v.sector.trim() || "MANUFACTURING", country: v.country.trim() || "IN",
                      listedEntity: false, regulatedFi: false, pep: false, adverseMedia: false,
                      highRiskJurisdiction: false, complexOwnership: false,
                    }, actor);
                    notify(`Onboarded ${cp.legalName} · CDD ${cp.cddTier}`);
                    list.reload();
                    setSelId(cp.id);
                  }}
                />
                <Button kind="ghost" onClick={() => setCreating((c) => !c)}>{creating ? "Close" : "+ New"}</Button>
              </div>
            }
            empty={list.loading ? <div className="loading">Loading…</div> : <div className="muted">None yet — create one.</div>}
          />
        </Card>
        {pulling && <PullBorrowerForm onDone={(id) => { list.reload(); if (id) setSelId(id); }} />}
        {creating && <CreateForm onDone={(id) => { setCreating(false); list.reload(); setSelId(id); }} />}
      </div>

      <div>
        {selId ? <Detail id={selId} onChange={() => list.reload()} /> :
          <Card>
            <EmptyState
              glyph="◴"
              title="Select a counterparty to open its profile"
              sub="Pick one from the list on the left or use + New to onboard a new obligor — KYC/KYB, CDD tiering, UBO graph and screening live inside."
            />
          </Card>}
      </div>
    </div>
  );

  function CreateForm({ onDone }: { onDone: (id: number) => void }) {
    const [f, setF] = useState<any>({
      legalName: "", legalForm: "PRIVATE_LTD", registrationNo: "", jurisdiction: "IN-RBI",
      segment: "MID_CORPORATE", sector: "MANUFACTURING", country: "IN",
      listedEntity: false, regulatedFi: false, pep: false, adverseMedia: false,
      highRiskJurisdiction: false, complexOwnership: false,
    });
    const [busy, setBusy] = useState(false);
    const flag = (k: string) => (
      <label className="inline" style={{ fontSize: 13 }}>
        <input type="checkbox" style={{ width: "auto" }} checked={f[k]} onChange={(e) => setF({ ...f, [k]: e.target.checked })} /> {k}
      </label>
    );
    const submit = async () => {
      setBusy(true);
      try { const cp = await counterparty.create(f, actor); notify(`Onboarded ${cp.legalName} · CDD ${cp.cddTier}`); onDone(cp.id); }
      catch (e: any) { notify(e.message, true); } finally { setBusy(false); }
    };
    return (
      <Card title="New counterparty">
        <Field label="Legal name"><input value={f.legalName} onChange={(e) => setF({ ...f, legalName: e.target.value })} /></Field>
        <div className="grid cols-2">
          <Field label="Jurisdiction">
            <select value={f.jurisdiction} onChange={(e) => setF({ ...f, jurisdiction: e.target.value })}>
              {jurisdictions.map((j: any) => <option key={j.code} value={j.code}>{j.code}</option>)}
            </select>
          </Field>
          <Field label="Segment">
            <select value={f.segment} onChange={(e) => setF({ ...f, segment: e.target.value })}>
              {segments.map((s) => <option key={s.code} value={s.code}>{s.label}</option>)}
            </select>
          </Field>
          <Field label="Sector"><input value={f.sector} onChange={(e) => setF({ ...f, sector: e.target.value })} /></Field>
          <Field label="Country"><input value={f.country} onChange={(e) => setF({ ...f, country: e.target.value })} /></Field>
        </div>
        <div className="sub">Risk flags (drive CDD intensity &amp; screening)</div>
        <div className="grid cols-2" style={{ gap: 6 }}>
          {flag("listedEntity")}{flag("regulatedFi")}{flag("pep")}{flag("adverseMedia")}{flag("highRiskJurisdiction")}{flag("complexOwnership")}
        </div>
        <div className="spacer" />
        <Button onClick={submit} busy={busy} disabled={!f.legalName}>Onboard</Button>
      </Card>
    );
  }

  // CRM as system-of-record: pull a borrower and create it as a GOVERNED PROSPECT.
  function PullBorrowerForm({ onDone }: { onDone: (id?: number) => void }) {
    const [crmId, setCrmId] = useState("");
    const [busy, setBusy] = useState(false);
    const [result, setResult] = useState<any>(null);
    const submit = async () => {
      setBusy(true);
      try {
        const r = await initiation.pullBorrower(crmId.trim() ? { crmId: crmId.trim() } : {}, actor);
        setResult(r);
        notify(
          r.created ? `Governed prospect created: ${r.counterpartyRef}`
            : r.matchedExisting ? "Idempotent re-pull — counterparty already created from this CRM id"
            : `Linked to existing counterparty (${r.dedupMatches} dedup match${r.dedupMatches === 1 ? "" : "es"})`,
        );
        onDone(r.counterpartyId);
      } catch (e: any) { notify(e.message, true); }
      finally { setBusy(false); }
    };
    return (
      <Card title="Pull borrower from CRM"
        sub="CRM run as the system-of-record for obligor creation. A pull always yields a governed PROSPECT — never an approved obligor.">
        <GovFlow ai="CRM PULL" human="HUMAN PROMOTES → OBLIGOR"
          note="dedup · negative-check · idempotency · audit all fire" />
        <div className="spacer" />
        <Field label="CRM borrower id" hint="Leave blank to pull the default sample borrower (simulated source).">
          <input value={crmId} onChange={(e) => setCrmId(e.target.value)} placeholder="e.g. CRM-1001" />
        </Field>
        <Button onClick={submit} busy={busy}>Pull from CRM</Button>
        {result && (
          <div className="grid" style={{ marginTop: 12, gap: 8 }}>
            <div className="btnrow" style={{ flexWrap: "wrap" }}>
              <Badge kind="warn">PROSPECT</Badge>
              <Badge kind={statusTone(result.lifecycleStatus)}>{result.lifecycleStatus}</Badge>
              {result.created && <Badge kind="ok">CREATED</Badge>}
              {result.matchedExisting && <Badge kind="info">IDEMPOTENT RE-PULL</Badge>}
              {!result.created && !result.matchedExisting && result.dedupMatches > 0 && <Badge kind="info">DEDUP-LINKED</Badge>}
              {result.negativeHit && <Badge kind="bad">NEGATIVE-LIST HIT</Badge>}
            </div>
            <div className="kv">
              <div className="k">Reference</div><div className="v">{result.counterpartyRef}</div>
              <div className="k">CRM id</div><div className="v">{result.crmId || "—"}</div>
              <div className="k">Record type</div><div className="v">{result.recordType}</div>
              <div className="k">Dedup matches</div><div className="v">{result.dedupMatches}</div>
            </div>
            <div className="gate">{result.message}</div>
          </div>
        )}
      </Card>
    );
  }

  // Auto data fetch — advisory INPUTS pulled from source systems (credit bureau + CRM).
  // Every pull carries provenance and is non-authoritative; it never moves a figure of record.
  function AutoFetch({ id }: { id: number }) {
    const bureau = useAsync<any>(() => sourceIngest.latestBureau(id).catch(() => null), [id]);
    const crm = useAsync<any>(() => sourceIngest.latestCrm(id).catch(() => null), [id]);
    const [busyB, setBusyB] = useState(false);
    const [busyC, setBusyC] = useState(false);

    const pullBureau = async () => {
      setBusyB(true);
      try { const r = await sourceIngest.pullBureau(id, actor); notify(r.duplicate ? "Idempotent replay — bureau report already ingested" : "Bureau report pulled (advisory input)"); bureau.reload(); }
      catch (e: any) { notify(e.message, true); } finally { setBusyB(false); }
    };
    const pullCrm = async () => {
      setBusyC(true);
      try { const r = await sourceIngest.pullCrm(id, actor); notify(r.duplicate ? "Idempotent replay — CRM profile already ingested" : "CRM profile pulled (advisory input)"); crm.reload(); }
      catch (e: any) { notify(e.message, true); } finally { setBusyC(false); }
    };

    const b = bureau.data;
    const p = crm.data;
    return (
      <Card title="Auto data fetch"
        sub="Pull credit-bureau + CRM data from source systems. Simulated by default — no external system needed."
        right={<AiBadge label="ADVISORY INPUT" />}>
        <div className="gate">
          Fetched data is an advisory INPUT carrying provenance. A pull never moves the authoritative
          rating, pricing or CDD figure of record. <Unchanged label="FIGURES OF RECORD · UNCHANGED" />
        </div>

        <div className="grid cols-2" style={{ marginTop: 10 }}>
          {/* ---- credit bureau ---- */}
          <div className="card" style={{ margin: 0 }}>
            <div className="flexbetween">
              <div><h3 style={{ margin: 0 }}>Credit bureau</h3><div className="sub">Score · tradelines · delinquencies</div></div>
              <Button kind="subtle" busy={busyB} onClick={pullBureau}>Pull bureau report</Button>
            </div>
            {bureau.loading ? <div className="loading" /> : b ? (
              <>
                <ProvChips rec={b} />
                <div className="kv" style={{ marginTop: 8 }}>
                  <div className="k">Bureau score</div>
                  <div className="v">{b.creditScore ?? "—"} {b.scoreModel && <small className="prov">{b.scoreModel}</small>}</div>
                  <div className="k">Inquiries (6m)</div><div className="v">{b.inquiriesLast6m}</div>
                  <div className="k">Delinquencies (24m)</div><div className="v">{b.delinquenciesLast24m}</div>
                  <div className="k">Open tradelines</div><div className="v">{b.openTradelines}</div>
                  <div className="k">Total outstanding</div><div className="v">{fmt.money(b.totalOutstanding)}</div>
                  {b.oldestAccountMonths != null && (<><div className="k">Oldest account</div><div className="v">{b.oldestAccountMonths} mo</div></>)}
                </div>
              </>
            ) : (
              <EmptyState glyph="↧" title="No bureau report yet" sub="Pull a report to populate this panel." />
            )}
          </div>

          {/* ---- CRM ---- */}
          <div className="card" style={{ margin: 0 }}>
            <div className="flexbetween">
              <div><h3 style={{ margin: 0 }}>CRM profile</h3><div className="sub">Relationship · products · segment</div></div>
              <Button kind="subtle" busy={busyC} onClick={pullCrm}>Pull CRM profile</Button>
            </div>
            {crm.loading ? <div className="loading" /> : p ? (
              <>
                <ProvChips rec={p} />
                <div className="kv" style={{ marginTop: 8 }}>
                  <div className="k">CRM id</div><div className="v">{p.crmId || "—"}</div>
                  <div className="k">Account</div><div className="v">{p.accountName || "—"}</div>
                  <div className="k">Relationship mgr</div><div className="v">{p.relationshipManager || "—"}</div>
                  <div className="k">Segment</div><div className="v">{p.segment || "—"}</div>
                  <div className="k">Relationship value</div><div className="v">{fmt.money(p.relationshipValue)}</div>
                  <div className="k">Lifecycle stage</div><div className="v">{p.lifecycleStage || "—"}</div>
                  {(p.productsHeld || []).length > 0 && (<><div className="k">Products</div><div className="v">{p.productsHeld.join(", ")}</div></>)}
                </div>
              </>
            ) : (
              <EmptyState glyph="↧" title="No CRM profile yet" sub="Pull a profile to populate this panel." />
            )}
          </div>
        </div>
      </Card>
    );
  }

  // Provenance chip row: figure → source → version trace on every ingested record.
  function ProvChips({ rec }: { rec: any }) {
    return (
      <div className="prov-chips">
        <span className="prov-chip">source · {rec.sourceSystem}</span>
        {rec.sourceVendor && <span className="prov-chip">vendor · {rec.sourceVendor}</span>}
        {rec.payloadVersion && <span className="prov-chip">v · {rec.payloadVersion}</span>}
        {rec.sourceReference && <span className="prov-chip">ref · {rec.sourceReference}</span>}
        <span className="prov-chip">retrieved · {fmt.dateTime(rec.retrievedAt)}</span>
      </div>
    );
  }

  function Detail({ id, onChange }: { id: number; onChange: () => void }) {
    const cp = useAsync(() => counterparty.get(id), [id]);
    const hits = useAsync(() => counterparty.screening(id), [id]);
    const ubo = useAsync(() => counterparty.ubo(id), [id]);
    const [uboText, setUboText] = useState(SAMPLE_UBO);

    const act = async (fn: () => Promise<any>, ok: string) => {
      try { await fn(); notify(ok); cp.reload(); hits.reload(); ubo.reload(); onChange(); }
      catch (e: any) { notify(e.message, true); }
    };
    const ingestFeed = async () => {
      const c = cp.data;
      const envelope = {
        source: "SANCTIONS_SCREENING", vendor: "WorldCheck", idempotencyKey: `WC-${id}-001`, payloadVersion: "2024-06",
        payload: {
          entityName: c.legalName,
          matches: [
            { list: "OFAC", name: c.legalName, score: 0.71, risk: "HIGH", fields: ["name", `country:${c.country}`] },
            { list: "PEP", name: c.legalName, score: 0.55, risk: "MEDIUM", fields: ["name"] },
          ],
        },
      };
      try {
        const r = await counterparty.ingestScreening(id, envelope, actor);
        notify(r.duplicate ? "Idempotent replay — feed already ingested" : `Vendor feed ingested: ${r.message}`);
        hits.reload();
      } catch (e: any) { notify(e.message, true); }
    };
    const c = cp.data;
    if (!c) return <Card title="Loading…"><div className="loading" /></Card>;

    return (
      <div className="grid">
        <Card title={c.legalName} sub={`${c.reference} · ${c.jurisdiction} · ${c.segment}`}
          right={<Badge kind={statusTone(c.kycStatus)}>{c.kycStatus}</Badge>}>
          <div className="kv">
            <div className="k">CDD tier</div><div className="v">{c.cddTier}</div>
            <div className="k">Re-KYC due</div><div className="v">{fmt.date(c.reKycDueDate)}</div>
            <div className="k">Sector</div><div className="v">{c.sector}</div>
            <div className="k">Verified by</div><div className="v">{c.verifiedBy || "—"}</div>
          </div>
          <div className="gate">HITL gate: final CDD risk-tier sign-off requires a named human and no open hits ≥ MEDIUM.</div>
          <div className="btnrow">
            <Button onClick={() => act(() => counterparty.runScreening(id, actor), "Screening run")}>Run screening</Button>
            <Button kind="ghost" disabled={c.kycStatus === "VERIFIED"}
              onClick={() => act(() => counterparty.verifyKyc(id, actor), "KYC verified")}>Verify KYC</Button>
            <Button kind="danger" disabled={c.lifecycleStatus === "CLOSED"}
              onClick={() => {
                const reason = window.prompt("Close relationship — reason?");
                if (reason) act(() => counterparty.close(id, { reason }, actor), "Relationship closed");
              }}>{c.lifecycleStatus === "CLOSED" ? "Closed" : "Close relationship"}</Button>
          </div>
        </Card>

        <AutoFetch id={id} />

        <Card title="Screening hits" sub="Each hit cites matched fields; disposition is a named human action (no auto-clear ≥ SEVERE)."
          right={<Button kind="subtle" onClick={ingestFeed}>Ingest vendor feed</Button>}>
          {(hits.data || []).length === 0 ? <div className="muted">No hits — run screening.</div> : (
            <table>
              <thead><tr><th>Source</th><th className="num">Score</th><th>Severity</th><th>Disposition</th></tr></thead>
              <tbody>
                {hits.data!.map((h: any) => (
                  <tr key={h.id}>
                    <td>{h.listSource}<br /><small className="prov" title={h.aiRationale}>{(h.matchedAttributes || []).join(", ")}</small></td>
                    <td className="num">{h.matchScore.toFixed(2)}</td>
                    <td><Badge kind={h.severity === "SEVERE" ? "bad" : h.severity === "HIGH" ? "warn" : "info"}>{h.severity}</Badge></td>
                    <td>
                      {h.disposition === "OPEN" ? (
                        <div className="btnrow">
                          <button className="btn subtle" style={{ fontSize: 11, padding: "4px 8px" }}
                            onClick={() => act(() => counterparty.disposition(h.id, { disposition: "FALSE_POSITIVE", note: "reviewed" }, actor), "Dispositioned")}>False+</button>
                          <button className="btn danger" style={{ fontSize: 11, padding: "4px 8px" }}
                            onClick={() => {
                              if (window.confirm("Escalate this hit to the MLRO? The disposition cannot be reopened."))
                                act(() => counterparty.disposition(h.id, { disposition: "ESCALATED", note: "to MLRO" }, actor), "Escalated");
                            }}>Escalate</button>
                        </div>
                      ) : <Badge kind={statusTone(h.disposition)}>{h.disposition}</Badge>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Card>

        <Card title="UBO ownership graph" sub="Effective ownership via path multiplication; ≥10% flagged; low-confidence routed to review.">
          {(ubo.data || []).length > 0 && (
            <table>
              <thead><tr><th>Node</th><th>Type</th><th className="num">Effective %</th><th>Flags</th></tr></thead>
              <tbody>
                {ubo.data!.map((n: any) => (
                  <tr key={n.id}>
                    <td>{n.name} <small className="prov">{n.nodeKey}</small></td>
                    <td>{n.nodeType}</td>
                    <td className="num">{n.nodeType === "PERSON" ? (n.effectiveOwnership * 100).toFixed(1) + "%" : "—"}</td>
                    <td>{n.ubo && <Badge kind="warn">UBO</Badge>} {n.needsReview && <Badge kind="bad">review</Badge>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          <div className="spacer" />
          <Field label="Declared structure (editable JSON)">
            <textarea rows={8} className="mono" value={uboText} onChange={(e) => setUboText(e.target.value)} />
          </Field>
          <Button kind="ghost" onClick={() => {
            try { const body = JSON.parse(uboText); act(() => counterparty.resolveUbo(id, body, actor), "UBO resolved"); }
            catch { notify("Invalid JSON", true); }
          }}>Resolve UBO graph</Button>
        </Card>
      </div>
    );
  }
}
