import { useState } from "react";
import { counterparty } from "../api";
import { useApp } from "../app-context";
import { Badge, Button, Card, Field, statusTone, useAsync } from "../ui";

const SEGMENTS = ["MID_CORPORATE", "LARGE_CORPORATE", "SME", "PROJECT_FINANCE", "TRADE_FINANCE", "FINANCIAL_INSTITUTION"];

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

export default function Counterparties() {
  const { actor, notify } = useApp();
  const list = useAsync(() => counterparty.list(), []);
  const [selId, setSelId] = useState<number | null>(null);
  const [creating, setCreating] = useState(false);

  return (
    <div className="grid cols-2">
      <div className="grid">
        <Card title="Counterparties" right={<Button kind="ghost" onClick={() => setCreating((c) => !c)}>{creating ? "Close" : "+ New"}</Button>}>
          {list.loading ? <div className="loading">Loading…</div> : (
            <table>
              <thead><tr><th>Name</th><th>Segment</th><th>CDD</th><th>KYC</th></tr></thead>
              <tbody>
                {(list.data || []).map((c: any) => (
                  <tr key={c.id} className="rowlink" onClick={() => setSelId(c.id)}>
                    <td>{c.legalName}<br /><small className="prov">{c.reference}</small></td>
                    <td>{c.segment}</td>
                    <td><Badge kind={c.cddTier === "ENHANCED" ? "warn" : "info"}>{c.cddTier}</Badge></td>
                    <td><Badge kind={statusTone(c.kycStatus)}>{c.kycStatus}</Badge></td>
                  </tr>
                ))}
                {(list.data || []).length === 0 && <tr><td colSpan={4} className="muted">None yet — create one.</td></tr>}
              </tbody>
            </table>
          )}
        </Card>
        {creating && <CreateForm onDone={(id) => { setCreating(false); list.reload(); setSelId(id); }} />}
      </div>

      <div>
        {selId ? <Detail id={selId} onChange={() => list.reload()} /> :
          <Card title="Select a counterparty" sub="KYC/KYB · CDD tiering · UBO graph · screening — PRD Stage 1.">
            <div className="muted">Pick a counterparty on the left, or create one.</div>
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
              <option>IN-RBI</option><option>AE-CBUAE</option>
            </select>
          </Field>
          <Field label="Segment">
            <select value={f.segment} onChange={(e) => setF({ ...f, segment: e.target.value })}>
              {SEGMENTS.map((s) => <option key={s}>{s}</option>)}
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
            <div className="k">Re-KYC due</div><div className="v">{c.reKycDueDate}</div>
            <div className="k">Sector</div><div className="v">{c.sector}</div>
            <div className="k">Verified by</div><div className="v">{c.verifiedBy || "—"}</div>
          </div>
          <div className="gate">HITL gate: final CDD risk-tier sign-off requires a named human and no open hits ≥ MEDIUM.</div>
          <div className="btnrow">
            <Button onClick={() => act(() => counterparty.runScreening(id, actor), "Screening run")}>Run screening</Button>
            <Button kind="ghost" disabled={c.kycStatus === "VERIFIED"}
              onClick={() => act(() => counterparty.verifyKyc(id, actor), "KYC verified")}>Verify KYC</Button>
          </div>
        </Card>

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
                          <button className="btn subtle" style={{ fontSize: 11, padding: "4px 8px" }}
                            onClick={() => act(() => counterparty.disposition(h.id, { disposition: "ESCALATED", note: "to MLRO" }, actor), "Escalated")}>Escalate</button>
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
