import { useEffect, useState } from "react";
import { config } from "../api";
import { Badge, Card, useAsync } from "../ui";

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

  useEffect(() => {
    let alive = true;
    Promise.all(TYPES.map((t) => config.pack(active, t).then((p) => [t, p]).catch(() => [t, null])))
      .then((entries) => { if (alive) setPacks(Object.fromEntries(entries as any)); });
    return () => { alive = false; };
  }, [active]);

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
                  <span className="mono">{pack.code} · v{pack.version}</span>
                  {pack.fullySignedOff
                    ? <Badge kind="ok">Dual sign-off ✓</Badge>
                    : <Badge kind="warn">Awaiting sign-off</Badge>}
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
