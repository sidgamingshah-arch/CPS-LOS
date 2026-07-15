import { useEffect, useState } from "react";
import { config, fmt } from "../api";
import { useApp } from "../app-context";
import { Badge, Button, Card, Field, useAsync } from "../ui";

const TYPES = [
  "CAPITAL_SA", "ECRA_MAPPING", "RATING_PD_MAP", "LGD_MAP",
  "PROVISIONING", "DOA_MATRIX", "CDD_TIERS", "EXPOSURE_LIMITS", "PRICING",
  "WORKFLOW_DEFINITION",
];

export default function RulePacks() {
  const { data: jurisdictions, loading } = useAsync(() => config.jurisdictions(), []);
  const [active, setActive] = useState<string>("IN-RBI");
  const [packs, setPacks] = useState<Record<string, any>>({});
  const [sel, setSel] = useState<string>("CAPITAL_SA");
  const { actor, notify } = useApp();
  const [rev, setRev] = useState(0);
  const { data: drafts } = useAsync<any[]>(() => config.drafts(), [rev]);
  const [proposing, setProposing] = useState(false);
  const reload = () => setRev((r) => r + 1);
  const run = async (fn: () => Promise<any>, ok: string) => {
    try { await fn(); notify(ok); reload(); } catch (e: any) { notify(e.message, true); }
  };

  useEffect(() => {
    let alive = true;
    Promise.all(TYPES.map((t) => config.pack(active, t).then((p) => [t, p]).catch(() => [t, null])))
      .then((entries) => { if (alive) setPacks(Object.fromEntries(entries as any)); });
    return () => { alive = false; };
  }, [active, rev]);

  if (loading) return <div className="loading">Loading jurisdictions…</div>;
  const profile = (jurisdictions || []).find((j: any) => j.code === active);
  const pack = packs[sel];

  return (
    <div className="grid">
      <Card title="Regulatory abstraction layer" sub="A new regime is an overlay (rule-pack data), never a code branch — PRD §10.">
        <div className="btnrow">
          {(jurisdictions || []).map((j: any) => (
            <button key={j.code} className={`btn ${active === j.code ? "" : "subtle"}`} onClick={() => setActive(j.code)}>
              {j.code}
            </button>
          ))}
        </div>
      </Card>

      <Card title="Rule-pack lifecycle · dual sign-off"
        sub="Author a new pack version, then route it through policy + model-risk sign-off. A draft activates and supersedes the prior version only once BOTH controls sign — two distinct humans, neither the author."
        right={<Button kind="ghost" onClick={() => setProposing((o) => !o)}>{proposing ? "Cancel" : "+ Propose new pack version"}</Button>}>
        {(drafts || []).length === 0
          ? <div className="muted">No drafts awaiting sign-off.</div>
          : (
          <div className="table-scroll">
          <table>
            <thead><tr><th>Pack</th><th>Juris · Type</th><th>Ver</th><th>Effective</th><th>Policy</th><th>Model-risk</th><th>Sign off</th></tr></thead>
            <tbody>
              {(drafts || []).map((p: any) => (
                <tr key={p.id}>
                  <td><b>{p.code}</b></td>
                  <td className="mono"><small>{p.jurisdiction} · {p.type}</small></td>
                  <td className="mono">v{p.version}</td>
                  <td className="mono"><small>{fmt.date(p.effectiveFrom)}</small></td>
                  <td className="mono"><small>{p.policySignedOffBy || "—"}</small></td>
                  <td className="mono"><small>{p.modelRiskSignedOffBy || "—"}</small></td>
                  <td>
                    <div className="btnrow">
                      <button className="btn subtle" style={{ fontSize: 11, padding: "3px 8px" }} disabled={!!p.policySignedOffBy}
                        onClick={() => run(() => config.signoff(p.id, "policy", actor), "Policy sign-off recorded")}>Policy ✓</button>
                      <button className="btn subtle" style={{ fontSize: 11, padding: "3px 8px" }} disabled={!!p.modelRiskSignedOffBy}
                        onClick={() => run(() => config.signoff(p.id, "model-risk", actor), "Model-risk sign-off recorded")}>Model-risk ✓</button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          </div>
        )}
      </Card>

      {proposing && (
        <ProposePack jurisdiction={active} actor={actor} notify={notify}
          onDone={() => { setProposing(false); reload(); }} />
      )}

      {profile && (
        <div className="grid cols-2">
          <Card title={`Jurisdiction profile · ${profile.code}`} sub={profile.name}>
            <div className="kv">
              <div className="k">Capital approach</div><div className="v">{profile.capitalApproach}</div>
              <div className="k">Capital ruleset</div><div className="v mono">{profile.capitalRuleset}</div>
              <div className="k">ECRA mapping</div><div className="v mono">{profile.ecraMapping}</div>
              <div className="k">CVA approach</div><div className="v">{profile.cvaApproach || "—"}</div>
              <div className="k">Due diligence required</div><div className="v">{String(profile.dueDiligenceRequired)}</div>
              <div className="k">Provisioning</div><div className="v">{(profile.provisioningFrameworks || []).join(", ")}</div>
              <div className="k">Reported provision</div><div className="v mono">{profile.reportedProvisionPolicy}</div>
              <div className="k">Large exposure</div><div className="v mono">{profile.exposureLimits}</div>
              <div className="k">Data residency</div><div className="v">{profile.dataResidency}</div>
              <div className="k">Reporting pack</div><div className="v mono">{profile.reportingPack}</div>
            </div>
          </Card>

          <Card title="Rule packs" sub="Versioned, effective-dated, dual sign-off (policy + model risk).">
            <div className="btnrow" style={{ marginBottom: 12 }}>
              {TYPES.map((t) => (
                <button key={t} className={`btn ${sel === t ? "" : "subtle"}`} style={{ fontSize: 11 }} onClick={() => setSel(t)}>
                  {t}
                </button>
              ))}
            </div>
            {pack ? (
              <>
                <div className="flexbetween" style={{ marginBottom: 8 }}>
                  <span className="mono">{pack.code} · v{pack.version} · eff {fmt.date(pack.effectiveFrom)}</span>
                  <div className="btnrow">
                    {pack.active ? <Badge kind="ok">ACTIVE</Badge> : <Badge>superseded</Badge>}
                    {pack.fullySignedOff
                      ? <Badge kind="ok">Dual sign-off ✓</Badge>
                      : <Badge kind="warn">Awaiting sign-off</Badge>}
                  </div>
                </div>
                <small className="prov">
                  Policy: {pack.policySignedOffBy || "—"} · Model risk: {pack.modelRiskSignedOffBy || "—"}
                </small>
                <pre className="trace" style={{ marginTop: 10 }}>{JSON.stringify(pack.payload, null, 2)}</pre>
              </>
            ) : <div className="muted">No pack of this type for {active}.</div>}
          </Card>
        </div>
      )}
    </div>
  );
}

function ProposePack({ jurisdiction, actor, notify, onDone }:
                     { jurisdiction: string; actor: string; notify: (t: string, e?: boolean) => void; onDone: () => void }) {
  const [type, setType] = useState(TYPES[0]);
  const [code, setCode] = useState("");
  const [effectiveFrom, setEffectiveFrom] = useState("");
  const [payloadText, setPayloadText] = useState("{\n  \n}");
  const submit = async () => {
    if (!code.trim()) { notify("Pack code required", true); return; }
    if (!effectiveFrom) { notify("Effective-from date required", true); return; }
    let payload: any;
    try { payload = JSON.parse(payloadText); }
    catch (e: any) { notify("Payload must be valid JSON: " + e.message, true); return; }
    try {
      await config.createRulePack({ code: code.trim(), type, jurisdiction, effectiveFrom, payload }, actor);
      notify(`Proposed ${jurisdiction}/${type} pack ${code} (draft — awaiting dual sign-off)`);
      onDone();
    } catch (e: any) { notify(e.message, true); }
  };
  return (
    <Card title={`Propose new pack version · ${jurisdiction}`}
      sub="Created inactive & unsigned. Activates (superseding the prior version) only after policy AND model-risk sign off — the author cannot be a signer.">
      <div className="grid cols-3">
        <Field label="Pack type">
          <select value={type} onChange={(e) => setType(e.target.value)}>
            {TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
          </select>
        </Field>
        <Field label="Pack code">
          <input value={code} onChange={(e) => setCode(e.target.value)} placeholder="e.g. rbi_pricing_v2" />
        </Field>
        <Field label="Effective from">
          <input type="date" value={effectiveFrom} onChange={(e) => setEffectiveFrom(e.target.value)} />
        </Field>
      </div>
      <Field label="Payload (JSON)">
        <textarea value={payloadText} onChange={(e) => setPayloadText(e.target.value)}
          rows={10} style={{ fontFamily: "ui-monospace, Menlo, monospace", fontSize: 12 }} />
      </Field>
      <Button onClick={submit}>Submit draft for sign-off</Button>
    </Card>
  );
}
