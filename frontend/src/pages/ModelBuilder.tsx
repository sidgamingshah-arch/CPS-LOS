/**
 * Model Builder — author and govern scoring-model definitions (MODEL_DEFINITION
 * master). A model is selected by jurisdiction/sector/segment and holds sections
 * (qualitative + quantitative) of typed questions (DROPDOWN / INPUT / NUMBER /
 * ITERATIVE) with visibility rules, min/max-answered constraints, master-driven
 * options and a weighted composite -> band. Definitions go through maker-checker;
 * the resolve tester shows which model a given deal profile would get.
 */
import { useEffect, useMemo, useState } from "react";
import { masters, models } from "../api";
import { useApp } from "../app-context";
import { Badge, Button, Card, DeterministicBadge, EmptyState, Field, GovFlow, HumanBadge, useAsync } from "../ui";

const STARTER = {
  modelKey: "my-model-v1",
  displayName: "My Model",
  selector: { jurisdiction: null, sector: null, segment: null },
  constraints: { minAnswered: 2, maxAnswered: 30, mandatory: ["q_lead"] },
  scoring: { bands: [{ band: "STRONG", min: 67 }, { band: "ADEQUATE", min: 45 }, { band: "WEAK", min: 0 }] },
  sections: [
    {
      key: "QUALITATIVE", kind: "QUALITATIVE", label: "Qualitative", weight: 0.5,
      questions: [
        { key: "q_lead", type: "DROPDOWN", label: "Management quality", weight: 1.0, required: true,
          options: [{ label: "Strong", score: 90 }, { label: "Adequate", score: 60 }, { label: "Weak", score: 30 }] },
      ],
    },
    {
      key: "QUANTITATIVE", kind: "QUANTITATIVE", label: "Quantitative", weight: 0.5,
      questions: [
        { key: "leverage", type: "NUMBER", label: "Net leverage (x)", weight: 1.0, required: true,
          scoreBands: [{ max: 2, score: 90 }, { max: 3.5, score: 60 }, { max: 99, score: 30 }] },
      ],
    },
  ],
};

export default function ModelBuilder() {
  const { actor, notify } = useApp();
  const active = useAsync(() => masters.list("MODEL_DEFINITION"), []);
  const pending = useAsync(() => masters.pending(), []);
  const [recordKey, setRecordKey] = useState("my-model-v1");
  const [jurisdiction, setJurisdiction] = useState("");
  const [json, setJson] = useState(JSON.stringify(STARTER, null, 2));
  const [tester, setTester] = useState({ jurisdiction: "IN-RBI", sector: "", segment: "MID_CORPORATE" });
  const [resolved, setResolved] = useState<any>(null);

  const pendingDefs = useMemo(
    () => (pending.data || []).filter((r: any) => r.masterType === "MODEL_DEFINITION"),
    [pending.data],
  );

  const reload = () => { active.reload(); pending.reload(); };

  function loadInto(rec: any) {
    setRecordKey(rec.recordKey);
    setJurisdiction(rec.jurisdiction || "");
    setJson(JSON.stringify(rec.payload, null, 2));
  }

  async function submit() {
    let payload: any;
    try { payload = JSON.parse(json); }
    catch (e: any) { notify("Invalid JSON: " + e.message, true); return; }
    try {
      const body: any = { recordKey: recordKey.trim(), payload };
      if (jurisdiction.trim()) body.jurisdiction = jurisdiction.trim();
      const m = await masters.submit("MODEL_DEFINITION", body, actor);
      notify(`Submitted ${recordKey} (PENDING_APPROVAL · id ${m.id}) — needs a checker`);
      reload();
    } catch (e: any) { notify(e.message, true); }
  }

  async function runTester() {
    try {
      const r = await models.resolveDefinition(tester.jurisdiction || undefined,
        tester.sector || undefined, tester.segment || undefined);
      setResolved(r);
    } catch (e: any) { setResolved(null); notify(e.message, true); }
  }

  useEffect(() => { runTester(); /* eslint-disable-next-line */ }, []);

  return (
    <div className="grid">
      <div className="gov-banner">
        <strong>MODEL BUILDER</strong>
        <span style={{ marginLeft: 12 }}>
          Configure scoring models as data — sections (qualitative + quantitative), typed questions
          (dropdown / input / number / iterative), visibility rules, min/max-answered constraints,
          master-driven options, weighted composite. Definitions are maker-checker governed.
        </span>
        <span style={{ marginLeft: 16 }}>
          <GovFlow ai="USER · CONFIGURES" human="HUMAN · APPROVES DEFINITION" note="SCORING DETERMINISTIC" />
        </span>
      </div>

      <div className="grid cols-2">
        <Card title="Model definitions" sub={`${(active.data || []).length} active`}>
          {(active.data || []).length === 0
            ? <EmptyState title="No models yet" sub="Author one on the right." />
            : (
              <table className="table">
                <thead><tr><th>Model</th><th>Selector</th><th>v</th><th /></tr></thead>
                <tbody>
                  {(active.data || []).map((r: any) => (
                    <tr key={r.id}>
                      <td><b>{r.payload?.modelKey || r.recordKey}</b><div className="muted" style={{ fontSize: 11 }}>{r.payload?.displayName}</div></td>
                      <td className="mono" style={{ fontSize: 11 }}>
                        {JSON.stringify(r.payload?.selector || {})}
                      </td>
                      <td className="mono">v{r.version}</td>
                      <td><Button kind="ghost" onClick={() => loadInto(r)}>Edit</Button></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          {pendingDefs.length > 0 && (
            <div style={{ marginTop: 12 }}>
              <b>Pending approval <Badge kind="warn">{pendingDefs.length}</Badge></b>
              <table className="table">
                <tbody>
                  {pendingDefs.map((r: any) => (
                    <tr key={r.id}>
                      <td><b>{r.recordKey}</b> <span className="muted">v{r.version} · maker {r.maker}</span></td>
                      <td><Button kind="subtle" onClick={() => masters.approve(r.id, actor).then(() => { notify("Approved"); reload(); }).catch((e) => notify(e.message, true))}>Approve</Button></td>
                      <td><Button kind="ghost" onClick={() => masters.reject(r.id, actor).then(() => { notify("Rejected"); reload(); }).catch((e) => notify(e.message, true))}>Reject</Button></td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <span className="muted" style={{ fontSize: 11 }}>SoD: the approver must differ from the maker.</span>
            </div>
          )}
        </Card>

        <Card title="Resolve tester" sub="Which model would a deal with this profile get? (most-specific selector wins)">
          <div className="grid cols-3" style={{ alignItems: "end" }}>
            <Field label="Jurisdiction"><input value={tester.jurisdiction} onChange={(e) => setTester({ ...tester, jurisdiction: e.target.value })} /></Field>
            <Field label="Sector"><input value={tester.sector} onChange={(e) => setTester({ ...tester, sector: e.target.value })} placeholder="(any)" /></Field>
            <Field label="Segment"><input value={tester.segment} onChange={(e) => setTester({ ...tester, segment: e.target.value })} /></Field>
          </div>
          <Button kind="subtle" onClick={runTester}>Resolve</Button>
          {resolved && (
            <div className="prov" style={{ marginTop: 8 }}>
              Resolves to <b>{resolved.payload?.modelKey}</b> (v{resolved.version}) — {resolved.payload?.displayName}
              <DeterministicBadge label="MOST-SPECIFIC MATCH" />
            </div>
          )}
        </Card>
      </div>

      <Card title="Author / edit a model definition" sub="The definition is structured JSON; submit goes through maker-checker."
        right={<HumanBadge label="MAKER-CHECKER" />}>
        <div className="grid cols-2" style={{ alignItems: "end" }}>
          <Field label="Record key"><input value={recordKey} onChange={(e) => setRecordKey(e.target.value)} /></Field>
          <Field label="Jurisdiction (optional override)"><input value={jurisdiction} onChange={(e) => setJurisdiction(e.target.value)} placeholder="(default)" /></Field>
        </div>
        <Field label="Definition (JSON)">
          <textarea rows={20} value={json} onChange={(e) => setJson(e.target.value)} style={{ fontFamily: "monospace", fontSize: 12 }} />
        </Field>
        <div className="btnrow">
          <Button kind="primary" onClick={submit}>Submit for approval</Button>
          <Button kind="subtle" onClick={() => setJson(JSON.stringify(STARTER, null, 2))}>Reset to starter</Button>
        </div>
      </Card>
    </div>
  );
}
