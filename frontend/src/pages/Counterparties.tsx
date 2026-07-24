import { useState } from "react";
import { config, counterparty, fmt, initiation, sourceIngest } from "../api";
import { useApp } from "../app-context";
import {
  AiBadge, Badge, Button, Card, type Col, DataTable, EmptyState, Field, GovFlow,
  humanize, InfoDot, Modal, QuickCreate, SimChip, statusTone, Tabs, Unchanged, useAsync,
} from "../ui";
import { useCodes } from "../code-values";
import { differsFromCreator } from "../authz";

// A SAMPLE ownership structure that pre-fills the UBO editor — a placeholder to edit, NOT real
// data. The graph is whatever a human declares here; the backend runs a deterministic ownership
// roll-up on it (no registry pull, no AI).
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

/** RISK_FLAG recordKey -> the typed boolean field on the create request (the historical 6). */
const KNOWN_FLAG_FIELD: Record<string, string> = {
  PEP: "pep", ADVERSE_MEDIA: "adverseMedia", HIGH_RISK_JURISDICTION: "highRiskJurisdiction",
  COMPLEX_OWNERSHIP: "complexOwnership", LISTED_ENTITY: "listedEntity", REGULATED_FI: "regulatedFi",
};
/** Fallback catalogue mirroring the seeded RISK_FLAG master, used if the fetch is empty. */
const FALLBACK_FLAGS = [
  { recordKey: "PEP", payload: { label: "Politically Exposed Person", cddImpact: "ENHANCED", defaultSeverity: "HIGH" } },
  { recordKey: "ADVERSE_MEDIA", payload: { label: "Adverse Media", cddImpact: "ENHANCED", defaultSeverity: "MEDIUM" } },
  { recordKey: "HIGH_RISK_JURISDICTION", payload: { label: "High-Risk Jurisdiction", cddImpact: "ENHANCED", defaultSeverity: "HIGH" } },
  { recordKey: "COMPLEX_OWNERSHIP", payload: { label: "Complex Ownership", cddImpact: "ENHANCED" } },
  { recordKey: "LISTED_ENTITY", payload: { label: "Listed Entity", cddImpact: "SIMPLIFIED" } },
  { recordKey: "REGULATED_FI", payload: { label: "Regulated Financial Institution", cddImpact: "SIMPLIFIED" } },
];

/** Plain-English "what does this flag do" line for the tooltip / impact hint. Honest about which
 *  effect is wired to THIS master (screening) vs authored elsewhere (CDD tiering = CDD_TIERS pack). */
function flagImpact(p: any): string {
  const screen = p?.defaultSeverity
    ? `Screening: raises a ${p.defaultSeverity} advisory hit (configurable here in the RISK_FLAG master). `
    : "No screening hit. ";
  const cdd = p?.cddImpact === "ENHANCED" ? "CDD: contributes to Enhanced CDD via the jurisdiction's CDD_TIERS pack."
    : p?.cddImpact === "SIMPLIFIED" ? "CDD: Simplified-CDD eligible per the CDD_TIERS pack."
    : "No CDD-tier impact.";
  return `${screen}${cdd} Never moves the authoritative rating, pricing or CDD figure of record.`;
}

/** A counterparty is prospect-stage until a named human promotes it to an obligor. */
function isProspect(c: any): boolean {
  return c?.recordType === "PROSPECT" || c?.lifecycleStatus === "DRAFT";
}

export default function Counterparties() {
  const { actor, notify } = useApp();
  const list = useAsync(() => counterparty.list(), []);
  const segments = useCodes("SEGMENT");
  const sectors = useCodes("SECTOR");
  const countries = useCodes("COUNTRY");
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

  const sectorOpts = sectors.map((s) => ({ value: s.code, label: s.label }));
  const countryOpts = countries.map((s) => ({ value: s.code, label: s.label }));

  return (
    <div className="grid cols-2">
      <div className="grid">
        <Card title="Counterparties">
          <DataTable
            id="counterparties"
            initialSort={{ key: "id", dir: "desc" }}
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
                <Button kind="subtle" onClick={() => setPulling(true)}>Pull borrower from CRM</Button>
                <QuickCreate
                  buttonLabel="＋ Quick create"
                  buttonKind="subtle"
                  title="Quick-onboard a counterparty"
                  sub="Fast path for a low-risk obligor. Use + New for the full form with risk flags."
                  fields={[
                    { name: "legalName", label: "Legal name", required: true, placeholder: "e.g. Meridian Steel Ltd" },
                    { name: "segment", label: "Segment", type: "select", options: segments.map((s) => ({ value: s.code, label: s.label })) },
                    { name: "jurisdiction", label: "Jurisdiction", type: "select", options: jurisdictions.map((j: any) => ({ value: j.code, label: j.code })) },
                    { name: "sector", label: "Sector", type: "select", options: sectorOpts },
                    { name: "country", label: "Country", type: "select", options: countryOpts },
                  ]}
                  submitLabel="Onboard"
                  onSubmit={async (v) => {
                    const cp = await counterparty.create({
                      legalName: v.legalName.trim(), legalForm: "PRIVATE_LTD", registrationNo: "",
                      jurisdiction: v.jurisdiction || "IN-RBI", segment: v.segment || "MID_CORPORATE",
                      sector: v.sector || "MANUFACTURING", country: v.country || "IN",
                      listedEntity: false, regulatedFi: false, pep: false, adverseMedia: false,
                      highRiskJurisdiction: false, complexOwnership: false,
                    }, actor);
                    notify(`Onboarded ${cp.legalName} · CDD ${cp.cddTier}`);
                    list.reload();
                    setSelId(cp.id);
                  }}
                />
                <Button kind="ghost" onClick={() => setCreating(true)}>+ New</Button>
              </div>
            }
            empty={list.loading ? <div className="loading">Loading…</div> : <div className="muted">None yet — create one.</div>}
          />
        </Card>
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

      {creating && (
        <CreateForm
          onClose={() => setCreating(false)}
          onDone={(id) => { setCreating(false); list.reload(); setSelId(id); }}
        />
      )}
      {pulling && (
        <PullBorrowerForm
          onClose={() => setPulling(false)}
          onDone={(id) => { list.reload(); if (id) setSelId(id); }}
        />
      )}
    </div>
  );

  // Full onboarding form — a CENTERED modal (fixes the old inline form that opened far down the
  // page). Risk-flag checkboxes are rendered from the config-driven RISK_FLAG catalogue, each with
  // an inline "what it does" hint.
  function CreateForm({ onClose, onDone }: { onClose: () => void; onDone: (id: number) => void }) {
    const catalogue = useAsync<any[]>(() => counterparty.riskFlags(), []);
    // The master engine serves records alphabetically by recordKey; re-sort by the catalogue's
    // own `order` so the checkboxes render in the intended sequence (matches ScreeningService).
    const flagDefs = [...(catalogue.data && catalogue.data.length ? catalogue.data : FALLBACK_FLAGS)]
      .sort((a: any, b: any) => (a?.payload?.order ?? 999) - (b?.payload?.order ?? 999));
    const [f, setF] = useState<any>({
      legalName: "", legalForm: "PRIVATE_LTD", registrationNo: "", jurisdiction: "IN-RBI",
      segment: "MID_CORPORATE", sector: "MANUFACTURING", country: "IN",
    });
    const [flags, setFlags] = useState<Record<string, boolean>>({});
    const [busy, setBusy] = useState(false);
    const toggle = (k: string) => setFlags((prev) => ({ ...prev, [k]: !prev[k] }));

    const submit = async () => {
      setBusy(true);
      try {
        const body: any = {
          ...f, sector: f.sector, country: f.country,
          listedEntity: false, regulatedFi: false, pep: false, adverseMedia: false,
          highRiskJurisdiction: false, complexOwnership: false, extraRiskFlags: {},
        };
        for (const [key, on] of Object.entries(flags)) {
          if (!on) continue;
          const field = KNOWN_FLAG_FIELD[key];
          if (field) body[field] = true; else body.extraRiskFlags[key] = true;
        }
        const cp = await counterparty.create(body, actor);
        notify(`Onboarded ${cp.legalName} · CDD ${cp.cddTier}`);
        onDone(cp.id);
      } catch (e: any) { notify(e.message, true); } finally { setBusy(false); }
    };

    return (
      <Modal
        title="New counterparty" wide onClose={onClose}
        sub="Onboard an obligor — segment, jurisdiction, sector/country and the risk flags that drive CDD intensity + screening."
        footer={<><Button kind="subtle" onClick={onClose}>Cancel</Button>
          <Button onClick={submit} busy={busy} disabled={!f.legalName}>Onboard</Button></>}
      >
        <Field label="Legal name" required>
          <input value={f.legalName} onChange={(e) => setF({ ...f, legalName: e.target.value })} placeholder="e.g. Meridian Steel Ltd" />
        </Field>
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
          <Field label="Sector">
            <select value={f.sector} onChange={(e) => setF({ ...f, sector: e.target.value })}>
              {sectors.map((s) => <option key={s.code} value={s.code}>{s.label}</option>)}
            </select>
          </Field>
          <Field label="Country">
            <select value={f.country} onChange={(e) => setF({ ...f, country: e.target.value })}>
              {countries.map((s) => <option key={s.code} value={s.code}>{s.label}</option>)}
            </select>
          </Field>
        </div>
        <div className="sub" style={{ marginTop: 6 }}>
          Risk flags <InfoDot text="Each flag's advisory screening (severity/score/list) is configurable in the RISK_FLAG master; CDD-tier triggers are authored in the jurisdiction's CDD_TIERS pack. Flags never move an authoritative rating, pricing or CDD figure of record." />
        </div>
        <div className="grid cols-2" style={{ gap: 6 }}>
          {flagDefs.map((r: any) => (
            <label key={r.recordKey} className="inline" style={{ fontSize: 13 }}
              title={flagImpact(r.payload)}>
              <input type="checkbox" style={{ width: "auto" }}
                checked={!!flags[r.recordKey]} onChange={() => toggle(r.recordKey)} />{" "}
              {r.payload?.label || humanize(r.recordKey)}
              {r.payload?.cddImpact === "ENHANCED" && <> <Badge kind="warn">ENHANCED</Badge></>}
              {r.payload?.cddImpact === "SIMPLIFIED" && <> <Badge kind="info">SIMPLIFIED</Badge></>}
            </label>
          ))}
        </div>
      </Modal>
    );
  }

  // CRM as system-of-record: pull a borrower and create it as a GOVERNED PROSPECT (centered modal).
  function PullBorrowerForm({ onClose, onDone }: { onClose: () => void; onDone: (id?: number) => void }) {
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
      <Modal
        title="Pull borrower from CRM" onClose={onClose}
        sub="CRM is the system-of-record for obligor creation. A pull always yields a governed PROSPECT — a named human then promotes it to an obligor."
        footer={<><Button kind="subtle" onClick={onClose}>Close</Button>
          <Button onClick={submit} busy={busy}>Pull from CRM</Button></>}
      >
        <GovFlow ai="CRM PULL" human="HUMAN PROMOTES → OBLIGOR"
          note="dedup · negative-check · idempotency · audit all fire" />
        <div className="spacer" />
        <Field label="CRM borrower id" hint="Leave blank to pull the default sample borrower.">
          <input value={crmId} onChange={(e) => setCrmId(e.target.value)} placeholder="e.g. CRM-1001" />
        </Field>
        <div style={{ marginTop: 4 }}><SimChip title="The CRM source is simulated by default (no external system). Set helix.crm.fetch.base-url at deployment for a live pull." /> CRM fetch is a dummy integration until a live CRM base-url is configured.</div>
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
      </Modal>
    );
  }

  // Auto data fetch — advisory INPUTS pulled from (simulated) source systems (credit bureau + CRM).
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
        sub="Pull credit-bureau + CRM data from source systems."
        right={<span className="btnrow"><SimChip title="Bureau + CRM pulls are simulated stand-ins by default (no external system). Live connectors wire in at deployment." /><AiBadge label="ADVISORY INPUT" /></span>}>
        <div className="prov-chips" style={{ marginBottom: 4 }}>
          <span className="prov-chip">Advisory input <InfoDot text="Fetched data is an advisory INPUT carrying provenance. A pull never moves the authoritative rating, pricing or CDD figure of record." /></span>
          <Unchanged label="FIGURES OF RECORD · UNCHANGED" />
        </div>

        <div className="grid cols-2" style={{ marginTop: 10 }}>
          {/* ---- credit bureau ---- */}
          <div className="card" style={{ margin: 0 }}>
            <div className="flexbetween">
              <div><h3 style={{ margin: 0 }}>Credit bureau <SimChip /></h3><div className="sub">Score · tradelines · delinquencies</div></div>
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
              <div><h3 style={{ margin: 0 }}>CRM profile <SimChip /></h3><div className="sub">Relationship · products · segment</div></div>
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
    const [tab, setTab] = useState("profile");

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

    const prospect = isProspect(c);

    return (
      <div className="grid">
        <Card title={c.legalName} sub={`${c.reference} · ${c.jurisdiction} · ${c.segment}`}
          right={<span className="btnrow">
            {prospect && <Badge kind="warn">PROSPECT</Badge>}
            <Badge kind={statusTone(c.kycStatus)}>{c.kycStatus}</Badge>
          </span>}>
          <Tabs
            active={tab} onChange={setTab}
            tabs={[
              { key: "profile", label: "Profile & CDD" },
              { key: "screening", label: `Screening${(hits.data || []).length ? ` · ${hits.data!.length}` : ""}` },
              { key: "ubo", label: "UBO graph" },
              { key: "fetch", label: "Data fetch" },
            ]}
          />

          {tab === "profile" && (
            <div role="tabpanel" id="tabpanel-profile" aria-labelledby="tab-profile">
              <div className="kv">
                <div className="k">Record type</div>
                <div className="v">{prospect ? "Prospect (draft lead)" : "Obligor"} · {humanize(c.lifecycleStatus)}</div>
                <div className="k">CDD tier</div>
                <div className="v">{c.cddTier} <InfoDot text="CDD tier is derived deterministically from the jurisdiction's CDD_TIERS rule pack (ENHANCED/SIMPLIFIED triggers, configurable via maker-checker). Deterministic; never moves an authoritative figure." /></div>
                <div className="k">Re-KYC due</div><div className="v">{fmt.date(c.reKycDueDate)}</div>
                <div className="k">Sector</div><div className="v">{humanize(c.sector) || "—"}</div>
                <div className="k">Country</div><div className="v">{c.country || "—"}</div>
                <div className="k">Created by</div><div className="v">{c.createdBy || c.rmId || "—"}</div>
                <div className="k">Verified by</div><div className="v">{c.verifiedBy || "—"}</div>
              </div>

              {prospect ? (
                <ProspectActions id={id} c={c} act={act} />
              ) : (
                <>
                  <div className="prov-chips" style={{ marginTop: 8 }}>
                    <span className="prov-chip">HITL sign-off <InfoDot text="Final CDD risk-tier sign-off is a named-human action: it requires a different human than the creator (segregation of duties) and blocks while any screening hit ≥ MEDIUM is open." /></span>
                  </div>
                  <div className="btnrow" style={{ marginTop: 8 }}>
                    <Button onClick={() => { setTab("screening"); act(() => counterparty.runScreening(id, actor), "Screening run"); }}>Run screening</Button>
                    <span className="authz-gate"
                      title={differsFromCreator(actor, c.createdBy) ? undefined
                        : "Requires a different signer than the creator — segregation of duties"}>
                      <Button kind="ghost" disabled={c.kycStatus === "VERIFIED" || !differsFromCreator(actor, c.createdBy)}
                        onClick={() => act(() => counterparty.verifyKyc(id, actor), "KYC verified — CDD tier signed off")}>Sign off CDD / Verify KYC</Button>
                    </span>
                    <Button kind="danger" disabled={c.lifecycleStatus === "CLOSED"}
                      onClick={() => {
                        const reason = window.prompt("Close relationship — reason?");
                        if (reason) act(() => counterparty.close(id, { reason }, actor), "Relationship closed");
                      }}>{c.lifecycleStatus === "CLOSED" ? "Closed" : "Close relationship"}</Button>
                  </div>
                </>
              )}
            </div>
          )}

          {tab === "screening" && (
            <div role="tabpanel" id="tabpanel-screening" aria-labelledby="tab-screening">
              <div className="flexbetween" style={{ marginBottom: 8 }}>
                <div className="sub">Disposition is a named-human action (no auto-clear ≥ SEVERE). <SimChip title="Run screening self-generates advisory hits from the counterparty's flags — a demo stand-in for a real screening vendor. Ingest a vendor feed for the authoritative path." /> self-screening</div>
                <div className="btnrow">
                  <Button kind="subtle" onClick={() => act(() => counterparty.runScreening(id, actor), "Screening run")}>Run screening</Button>
                  <Button kind="subtle" onClick={ingestFeed}>Ingest vendor feed <SimChip label="DEMO" title="Injects a canned WorldCheck sample via the canonical ingest contract; no external call." /></Button>
                </div>
              </div>
              {(hits.data || []).length === 0 ? <div className="muted">No hits — run screening.</div> : (
                <table>
                  <thead><tr><th>Source</th><th className="num">Score</th><th>Severity</th><th>Rationale</th><th>Disposition</th></tr></thead>
                  <tbody>
                    {hits.data!.map((h: any) => (
                      <tr key={h.id}>
                        <td>{h.listSource}<br /><small className="prov">{(h.matchedAttributes || []).join(", ")}</small></td>
                        <td className="num">{h.matchScore.toFixed(2)}</td>
                        <td><Badge kind={h.severity === "SEVERE" ? "bad" : h.severity === "HIGH" ? "warn" : "info"}>{h.severity}</Badge></td>
                        <td><RationaleCell hit={h} onSaved={() => hits.reload()} /></td>
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
            </div>
          )}

          {tab === "ubo" && (
            <div role="tabpanel" id="tabpanel-ubo" aria-labelledby="tab-ubo">
              <div className="sub" style={{ marginBottom: 8 }}>
                Effective ownership via path multiplication; ≥10% flagged UBO; low-confidence routed to review.
                <InfoDot text="The graph is the ownership structure you declare below — not pulled from any registry and not AI-generated. The backend runs a deterministic ownership roll-up on it." />
              </div>
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
              <Field label="Declared ownership structure (editable JSON)"
                hint="Pre-filled with a SAMPLE placeholder — replace with the counterparty's declared structure.">
                <textarea rows={8} className="mono" value={uboText} onChange={(e) => setUboText(e.target.value)} />
              </Field>
              <div style={{ margin: "4px 0" }}><SimChip label="SAMPLE" title="This is a placeholder ownership structure to edit — not real data." /> sample structure — edit before resolving</div>
              <Button kind="ghost" onClick={() => {
                try { const body = JSON.parse(uboText); act(() => counterparty.resolveUbo(id, body, actor), "UBO resolved"); }
                catch { notify("Invalid JSON", true); }
              }}>Resolve UBO graph</Button>
            </div>
          )}

          {tab === "fetch" && (
            <div role="tabpanel" id="tabpanel-fetch" aria-labelledby="tab-fetch">
              <AutoFetch id={id} />
            </div>
          )}
        </Card>
      </div>
    );
  }

  // Prospect-stage actions — the missing promotion step. A prospect can be promoted to an obligor
  // (initiation.approve) by a named human who differs from the creator (SoD, enforced server-side).
  function ProspectActions({ id, c, act }: { id: number; c: any; act: (fn: () => Promise<any>, ok: string) => Promise<void> }) {
    const summary = useAsync<any>(() => initiation.summary(id).catch(() => null), [id]);
    const s = summary.data;
    const blockers: string[] = (s?.blockers || []) as string[];
    return (
      <div style={{ marginTop: 8 }}>
        <div className="gate">
          This is a governed PROSPECT (a draft lead), not yet an obligor. Creating it added the lead;
          a named human now <strong>promotes it to an obligor</strong>. The promoter must differ from
          the creator ({c.createdBy || c.rmId || "—"}) — segregation of duties.
        </div>
        {blockers.length > 0 && (
          <div className="prov-chips" style={{ marginTop: 8 }}>
            {blockers.map((b, i) => <span key={i} className="prov-chip" style={{ borderColor: "var(--warn)", color: "var(--warn)" }}>⚠ {b}</span>)}
          </div>
        )}
        <div className="btnrow" style={{ marginTop: 10 }}>
          <span className="authz-gate"
            title={differsFromCreator(actor, c.createdBy) ? undefined
              : "Requires a different approver than the creator — segregation of duties"}>
            <Button disabled={!differsFromCreator(actor, c.createdBy)}
              onClick={() => act(() => initiation.approve(id, actor), "Prospect promoted to obligor")}>Promote to obligor</Button>
          </span>
          <Button kind="ghost" onClick={() => {
            const reason = window.prompt("Drop this prospect — reason?");
            if (reason) act(() => initiation.decide(id, { proceed: false, reason }, actor), "Prospect dropped");
          }}>Drop prospect</Button>
        </div>
      </div>
    );
  }

  // A single screening-hit rationale cell — shows AI/vendor/human provenance, and when no model
  // drafted a rationale (governed: no simulated text) offers a named-human to enter one.
  function RationaleCell({ hit, onSaved }: { hit: any; onSaved: () => void }) {
    const [editing, setEditing] = useState(false);
    const [text, setText] = useState("");
    const [busy, setBusy] = useState(false);
    const src = hit.rationaleSource || (hit.aiRationale ? "AI" : "NONE");
    const body = src === "HUMAN" ? hit.humanRationale : hit.aiRationale;

    if (src === "AI") return <span title={body}><Badge kind="ai">AI</Badge> <small className="prov">drafted</small></span>;
    if (src === "EXTERNAL") return <span title={body}><Badge kind="info">VENDOR</Badge> <small className="prov">provenance</small></span>;
    if (src === "HUMAN") return <span title={body}><Badge kind="ok">HUMAN</Badge> <small className="prov">{(body || "").slice(0, 24)}{(body || "").length > 24 ? "…" : ""}</small></span>;

    // NONE — no model configured; a named human enters the rationale (no fabricated text).
    if (!editing) {
      return <button className="btn subtle" style={{ fontSize: 11, padding: "4px 8px" }} onClick={() => setEditing(true)}>＋ add rationale</button>;
    }
    return (
      <div style={{ display: "flex", gap: 4, alignItems: "center" }}>
        <input value={text} onChange={(e) => setText(e.target.value)} placeholder="Named-human rationale" style={{ fontSize: 12, width: 160 }} />
        <button className="btn subtle" style={{ fontSize: 11, padding: "4px 8px" }} disabled={busy || !text.trim()}
          onClick={async () => {
            setBusy(true);
            try { await counterparty.setRationale(hit.id, text.trim(), actor); notify("Rationale recorded"); onSaved(); }
            catch (e: any) { notify(e.message, true); } finally { setBusy(false); }
          }}>Save</button>
      </div>
    );
  }
}
